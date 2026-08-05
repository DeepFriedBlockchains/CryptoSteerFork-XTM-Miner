#include "cryptosteer/stratum_client.hpp"
#include "cryptosteer/hex.hpp"
#include <algorithm>
#include <chrono>
#include <stdexcept>

namespace cryptosteer {
namespace {
constexpr std::size_t kMaximumPendingSubmissions = 4096U;

std::uint64_t numeric_id(json::Value const* value) {
    if (!value) return 0;
    if (value->is_number()) return static_cast<std::uint64_t>(std::max<std::int64_t>(0, value->as_i64()));
    if (value->is_string()) {
        try { return std::stoull(value->as_string()); } catch (...) { return 0; }
    }
    return 0;
}
std::string rpc_request(std::uint64_t id, std::string_view method, std::string_view params) {
    return "{\"id\":" + std::to_string(id) + ",\"jsonrpc\":\"2.0\",\"method\":\"" + json::escape(method) + "\",\"params\":" + std::string(params) + "}";
}
} // namespace

std::optional<Job> StratumClient::connect_and_login(Config const& config, Endpoint const& endpoint, std::string const& login, std::string const& rig_id) {
    transport_.connect(endpoint, config.connect_timeout_seconds, config.allow_insecure_tls);
    std::string params = "{\"login\":\"" + json::escape(login) + "\",\"pass\":\"" + json::escape(config.password) +
        "\",\"agent\":\"CryptoSteer/" CRYPTOSTEER_VERSION "\",\"rigid\":\"" + json::escape(rig_id) +
        "\",\"algo\":[\"rx/0\"]}";
    transport_.write_line(rpc_request(1, "login", params));
    auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(std::max(5, config.connect_timeout_seconds));
    while (std::chrono::steady_clock::now() < deadline) {
        std::string line;
        if (!transport_.read_line(line, 1000)) continue;
        PoolEvent event = parse_message(line, true);
        if (event.kind == PoolEventKind::Rejected) throw std::runtime_error("Pool login rejected: " + event.message);
        if (event.job) return event.job;
        if (!session_id_.empty()) return std::nullopt;
    }
    throw std::runtime_error("Timed out waiting for pool login response");
}

Job StratumClient::parse_job_value(json::Value const& value) {
    if (!value.is_object()) throw std::runtime_error("pool job is not an object");
    Job job;
    job.job_id = value.string_or("job_id", value.string_or("jobId"));
    std::string blob = value.string_or("blob");
    job.target_hex = value.string_or("target");
    std::string seed = value.string_or("seed_hash", value.string_or("seedHash"));
    job.height = static_cast<std::uint64_t>(std::max<std::int64_t>(0, value.i64_or("height", 0)));
    job.nonce_offset = static_cast<std::uint32_t>(std::max<std::int64_t>(0, value.i64_or("nonce_offset", 39)));
    if (job.job_id.empty() || blob.empty() || job.target_hex.empty() || seed.empty()) throw std::runtime_error("pool job is missing job_id, blob, target, or seed_hash");
    job.blob = hex_decode(blob); job.seed_hash = hex_decode(seed);
    if (job.blob.size() < static_cast<std::size_t>(job.nonce_offset) + 4U) throw std::runtime_error("pool job blob is too short for its nonce offset");
    if (job.blob.size() > 512U) throw std::runtime_error("pool job blob is unexpectedly large");
    if (job.seed_hash.size() != 32U) throw std::runtime_error("pool seed hash must be 32 bytes");
    (void)hex_decode(job.target_hex); // validates format; target width is checked by hash_meets_target
    return job;
}

std::string StratumClient::error_text(json::Value const& value) const {
    if (value.is_string()) return value.as_string();
    if (value.is_object()) {
        std::string message = value.string_or("message");
        if (!message.empty()) return message;
        std::string code = value.string_or("code");
        if (!code.empty()) return code;
    }
    return "unspecified pool error";
}

PoolEvent StratumClient::parse_message(std::string const& line, bool during_login) {
    json::Value root = json::parse(line);
    if (!root.is_object()) throw std::runtime_error("pool response is not a JSON object");
    std::uint64_t id = numeric_id(root.find("id"));
    if (auto error = root.find("error"); error && !error->is_null()) {
        pending_submissions_.erase(id);
        return {PoolEventKind::Rejected, std::nullopt, error_text(*error), id};
    }
    std::string method = root.string_or("method");
    if (method == "job") {
        auto params = root.find("params");
        if (!params) throw std::runtime_error("job notification has no params");
        return {PoolEventKind::Job, parse_job_value(*params), {}, id};
    }
    if (method == "set_difficulty") return {PoolEventKind::Difficulty, std::nullopt, "Pool difficulty updated", id};
    if (method == "keepalived" || method == "keepalive") return {PoolEventKind::Message, std::nullopt, "Pool keepalive acknowledged", id};

    auto result = root.find("result");
    if (during_login && id == 1U && result && result->is_object()) {
        session_id_ = result->string_or("id");
        std::string status = result->string_or("status", "OK");
        if (status != "OK" && status != "ok") return {PoolEventKind::Rejected, std::nullopt, status, id};
        if (auto job = result->find("job")) return {PoolEventKind::Job, parse_job_value(*job), {}, id};
        return {PoolEventKind::Message, std::nullopt, "Pool login accepted", id};
    }
    auto pending = pending_submissions_.find(id);
    if (pending != pending_submissions_.end()) {
        pending_submissions_.erase(pending);
        bool accepted = true; std::string message = "OK";
        if (result && result->is_object()) {
            message = result->string_or("status", "OK");
            accepted = message == "OK" || message == "ok" || result->bool_or("accepted", false);
        } else if (result && result->is_bool()) accepted = result->as_bool();
        return {accepted ? PoolEventKind::Accepted : PoolEventKind::Rejected, std::nullopt, message, id};
    }
    return {PoolEventKind::Message, std::nullopt, "Pool message received", id};
}

PoolEvent StratumClient::read_event(int timeout_millis) {
    std::string line;
    if (!transport_.read_line(line, timeout_millis)) return {};
    return parse_message(line, false);
}

std::uint64_t StratumClient::submit(Share const& share) {
    if (session_id_.empty()) throw std::runtime_error("cannot submit a share before pool login completes");
    std::uint64_t id = next_request_id_++;
    std::string nonce = hex_encode(std::span<std::uint8_t const>(reinterpret_cast<std::uint8_t const*>(&share.nonce), sizeof(share.nonce)));
#if __BYTE_ORDER__ == __ORDER_BIG_ENDIAN__
    std::reverse(nonce.begin(), nonce.end());
#endif
    std::string params = "{\"id\":\"" + json::escape(session_id_) + "\",\"job_id\":\"" + json::escape(share.job_id) +
        "\",\"nonce\":\"" + nonce + "\",\"result\":\"" + json::escape(share.result_hex) + "\"}";
    if (pending_submissions_.size() >= kMaximumPendingSubmissions) {
        throw std::runtime_error("pool share-response queue is full");
    }
    pending_submissions_[id] = share.job_generation;
    try {
        transport_.write_line(rpc_request(id, "submit", params));
    } catch (...) {
        pending_submissions_.erase(id);
        throw;
    }
    return id;
}

void StratumClient::keepalive() {
    if (session_id_.empty()) return;
    std::string params = "{\"id\":\"" + json::escape(session_id_) + "\"}";
    transport_.write_line(rpc_request(next_request_id_++, "keepalived", params));
}
void StratumClient::close() noexcept { transport_.close(); session_id_.clear(); pending_submissions_.clear(); }
} // namespace cryptosteer
