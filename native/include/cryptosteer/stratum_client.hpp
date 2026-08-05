#pragma once
#include "cryptosteer/json.hpp"
#include "cryptosteer/model.hpp"
#include "cryptosteer/transport.hpp"
#include <cstdint>
#include <optional>
#include <string>
#include <unordered_map>

namespace cryptosteer {
enum class PoolEventKind { None, Job, Accepted, Rejected, Difficulty, Message };
struct PoolEvent {
    PoolEventKind kind = PoolEventKind::None;
    std::optional<Job> job;
    std::string message;
    std::uint64_t request_id = 0;
};

class StratumClient {
public:
    StratumClient() = default;
    StratumClient(StratumClient const&) = delete;
    StratumClient& operator=(StratumClient const&) = delete;

    std::optional<Job> connect_and_login(Config const& config, Endpoint const& endpoint, std::string const& login, std::string const& rig_id);
    [[nodiscard]] PoolEvent read_event(int timeout_millis);
    std::uint64_t submit(Share const& share);
    void keepalive();
    void close() noexcept;
    [[nodiscard]] std::string peer() const { return transport_.peer(); }
    [[nodiscard]] bool connected() const noexcept { return transport_.connected(); }

private:
    [[nodiscard]] PoolEvent parse_message(std::string const& line, bool during_login);
    [[nodiscard]] Job parse_job_value(json::Value const& value);
    [[nodiscard]] std::string error_text(json::Value const& value) const;

    Transport transport_;
    std::string session_id_;
    std::uint64_t next_request_id_ = 2;
    std::unordered_map<std::uint64_t, std::uint64_t> pending_submissions_;
};
} // namespace cryptosteer
