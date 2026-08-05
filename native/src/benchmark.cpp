#include "cryptosteer/benchmark.hpp"
#include "cryptosteer/cpu_topology.hpp"
#include "cryptosteer/donation_scheduler.hpp"
#include "cryptosteer/hex.hpp"
#include "cryptosteer/json.hpp"
#include "cryptosteer/platform/affinity.h"
#include "cryptosteer/platform/page_size.h"
#include "cryptosteer/platform/secure_zero.h"
#include "cryptosteer/randomx_context.hpp"
#include "cryptosteer/stratum_client.hpp"
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cstring>
#include <deque>
#include <hwloc.h>
#include <memory>
#include <mutex>
#include <openssl/crypto.h>
#include <random>
#include <randomx.h>
#include <sstream>
#include <stdexcept>
#include <thread>
#include <uv.h>
#include <vector>

namespace cryptosteer {
namespace {
std::atomic<bool> g_auto_tune_cancelled{false};
std::atomic<bool> g_benchmark_cancelled{false};
std::atomic<int> g_benchmark_phase{0};
std::atomic<std::uint64_t> g_benchmark_hashes{0};
std::atomic<std::int64_t> g_benchmark_started_nanos{0};
std::atomic<int> g_benchmark_target_seconds{0};

class WorkerJoinGuard final {
public:
    WorkerJoinGuard(std::atomic<bool>& stop, std::vector<std::thread>& workers) noexcept
        : stop_(stop), workers_(workers) { }

    WorkerJoinGuard(WorkerJoinGuard const&) = delete;
    WorkerJoinGuard& operator=(WorkerJoinGuard const&) = delete;

    ~WorkerJoinGuard() { join(); }

    void join() noexcept {
        if (joined_) return;
        stop_.store(true, std::memory_order_release);
        for (auto& worker : workers_) {
            if (worker.joinable()) worker.join();
        }
        joined_ = true;
    }

private:
    std::atomic<bool>& stop_;
    std::vector<std::thread>& workers_;
    bool joined_ = false;
};

std::int64_t steady_nanos_now() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

std::string boolean(bool value) { return value ? "true" : "false"; }
std::string json_quote(std::string_view value) { return "\"" + json::escape(value) + "\""; }
std::uint64_t unix_millis_now() {
    auto value = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
    return value < 0 ? 0U : static_cast<std::uint64_t>(value);
}

struct TuneCandidate {
    std::string mode;
    int threads = 1;
    std::uint64_t hashes = 0;
    double hashrate = 0.0;
    long long initialization_millis = 0;
    bool jit = false;
    bool hardware_aes = false;
    std::vector<int> cpus;
    std::uint64_t shares_submitted = 0;
};

Endpoint parse_tune_endpoint(json::Value const& value) {
    if (!value.is_object()) throw std::invalid_argument("auto-tune endpoint must be an object");
    Endpoint endpoint;
    endpoint.host = value.string_or("host");
    auto port = value.i64_or("port", 0);
    if (endpoint.host.empty() || endpoint.host.size() > 253U || port < 1 || port > 65535) {
        throw std::invalid_argument("auto-tune pool endpoint is invalid");
    }
    endpoint.port = static_cast<std::uint16_t>(port);
    endpoint.tls = value.bool_or("tls", true);
    endpoint.server_name = value.string_or("serverName");
    endpoint.pinned_sha256 = value.string_or("pinnedSha256");
    return endpoint;
}

class LiveTuneSession final {
public:
    LiveTuneSession(Config config, std::vector<Endpoint> endpoints, std::string login, std::string rig)
        : config_(std::move(config)), endpoints_(std::move(endpoints)), login_(std::move(login)), rig_(std::move(rig)) { }

    LiveTuneSession(LiveTuneSession const&) = delete;
    LiveTuneSession& operator=(LiveTuneSession const&) = delete;

    ~LiveTuneSession() {
        stop();
        if (!config_.password.empty()) cryptosteer_secure_zero(config_.password.data(), config_.password.size());
    }

    void start() {
        std::string last_error;
        for (std::size_t index = 0; index < endpoints_.size(); ++index) {
            try {
                auto initial = client_.connect_and_login(config_, endpoints_[index], login_, rig_);
                active_endpoint_ = endpoints_[index].host + ":" + std::to_string(endpoints_[index].port);
                connected_.store(true, std::memory_order_release);
                if (initial) install_job(std::move(*initial));
                pump_ = std::thread(&LiveTuneSession::pump_loop, this);
                return;
            } catch (std::exception const& exception) {
                client_.close();
                last_error = exception.what();
            }
        }
        throw std::runtime_error("Unable to connect auto-tune to any configured pool: " + last_error);
    }

    void wait_for_job(std::chrono::seconds timeout) {
        auto deadline = std::chrono::steady_clock::now() + timeout;
        while (std::chrono::steady_clock::now() < deadline) {
            if (g_auto_tune_cancelled.load(std::memory_order_acquire)) throw std::runtime_error("Auto-tune cancelled");
            throw_if_failed();
            if (current_job()) return;
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
        }
        throw std::runtime_error("The pool accepted login but did not provide a RandomX job before the timeout");
    }

    [[nodiscard]] std::shared_ptr<Job const> current_job() const {
        std::lock_guard lock(job_mutex_);
        return job_;
    }

    void queue_share(Share share) {
        std::lock_guard lock(share_mutex_);
        if (shares_.size() < 1024U) shares_.push_back(std::move(share));
        else stale_.fetch_add(1, std::memory_order_relaxed);
    }

    [[nodiscard]] std::uint32_t next_nonce() noexcept {
        return next_nonce_.fetch_add(1, std::memory_order_relaxed);
    }

    [[nodiscard]] bool healthy() const noexcept {
        return connected_.load(std::memory_order_acquire) && !failed_.load(std::memory_order_acquire);
    }

    void throw_if_failed() const {
        if (!failed_.load(std::memory_order_acquire)) return;
        std::lock_guard lock(error_mutex_);
        throw std::runtime_error(error_.empty() ? "The live pool connection stopped" : error_);
    }

    [[nodiscard]] std::uint64_t accepted() const noexcept { return accepted_.load(std::memory_order_relaxed); }
    [[nodiscard]] std::uint64_t rejected() const noexcept { return rejected_.load(std::memory_order_relaxed); }
    [[nodiscard]] std::uint64_t stale() const noexcept { return stale_.load(std::memory_order_relaxed); }
    [[nodiscard]] std::uint64_t jobs_received() const noexcept { return jobs_received_.load(std::memory_order_relaxed); }
    [[nodiscard]] std::string const& active_endpoint() const noexcept { return active_endpoint_; }

    void stop() noexcept {
        stop_.store(true, std::memory_order_release);
        if (pump_.joinable() && pump_.get_id() != std::this_thread::get_id()) pump_.join();
        client_.close();
        connected_.store(false, std::memory_order_release);
    }

private:
    void install_job(Job job) {
        job.generation = generation_.fetch_add(1, std::memory_order_acq_rel) + 1U;
        {
            std::lock_guard lock(job_mutex_);
            job_ = std::make_shared<Job const>(std::move(job));
        }
        next_nonce_.store(static_cast<std::uint32_t>(std::random_device{}()), std::memory_order_relaxed);
        jobs_received_.fetch_add(1, std::memory_order_relaxed);
    }

    void drain_shares() {
        std::deque<Share> local;
        {
            std::lock_guard lock(share_mutex_);
            local.swap(shares_);
        }
        auto active = current_job();
        for (auto const& share : local) {
            if (!active || share.job_generation != active->generation) {
                stale_.fetch_add(1, std::memory_order_relaxed);
                continue;
            }
            try { (void)client_.submit(share); }
            catch (std::exception const&) { stale_.fetch_add(1, std::memory_order_relaxed); }
        }
    }

    void pump_loop() noexcept {
        auto last_keepalive = std::chrono::steady_clock::now();
        try {
            while (!stop_.load(std::memory_order_acquire) && !g_auto_tune_cancelled.load(std::memory_order_acquire)) {
                PoolEvent event = client_.read_event(50);
                if (event.kind == PoolEventKind::Job && event.job) install_job(std::move(*event.job));
                else if (event.kind == PoolEventKind::Accepted) accepted_.fetch_add(1, std::memory_order_relaxed);
                else if (event.kind == PoolEventKind::Rejected) rejected_.fetch_add(1, std::memory_order_relaxed);
                drain_shares();
                auto now = std::chrono::steady_clock::now();
                if (now - last_keepalive >= std::chrono::seconds(30)) {
                    client_.keepalive();
                    last_keepalive = now;
                }
            }
            drain_shares();
        } catch (std::exception const& exception) {
            {
                std::lock_guard lock(error_mutex_);
                error_ = exception.what();
            }
            failed_.store(true, std::memory_order_release);
        } catch (...) {
            {
                std::lock_guard lock(error_mutex_);
                error_ = "Unknown live-pool auto-tune failure";
            }
            failed_.store(true, std::memory_order_release);
        }
        connected_.store(false, std::memory_order_release);
    }

    Config config_;
    std::vector<Endpoint> endpoints_;
    std::string login_;
    std::string rig_;
    StratumClient client_;
    std::thread pump_;
    mutable std::mutex job_mutex_;
    mutable std::mutex error_mutex_;
    std::mutex share_mutex_;
    std::shared_ptr<Job const> job_;
    std::deque<Share> shares_;
    std::atomic<bool> stop_{false};
    std::atomic<bool> connected_{false};
    std::atomic<bool> failed_{false};
    std::atomic<std::uint64_t> generation_{0};
    std::atomic<std::uint32_t> next_nonce_{0};
    std::atomic<std::uint64_t> accepted_{0};
    std::atomic<std::uint64_t> rejected_{0};
    std::atomic<std::uint64_t> stale_{0};
    std::atomic<std::uint64_t> jobs_received_{0};
    std::string error_;
    std::string active_endpoint_;
};

TuneCandidate benchmark_live_candidate(
    LiveTuneSession& session,
    std::shared_ptr<RandomXContext> const& context,
    int threads,
    std::chrono::milliseconds duration,
    std::vector<int> const& ordered_cpus,
    long long initialization_millis
) {
    TuneCandidate result;
    result.mode = context->mode_name();
    result.threads = threads;
    result.initialization_millis = initialization_millis;
    result.jit = context->jit_enabled();
    result.hardware_aes = context->hardware_aes();
    const auto cpu_count = std::min<std::size_t>(
        ordered_cpus.size(),
        static_cast<std::size_t>(std::max(threads, 0))
    );
    result.cpus.reserve(cpu_count);
    for (std::size_t index = 0; index < cpu_count; ++index) {
        result.cpus.push_back(ordered_cpus[index]);
    }

    std::atomic<bool> stop{false};
    std::atomic<bool> worker_failed{false};
    std::atomic<std::uint64_t> hashes{0};
    std::mutex worker_error_mutex;
    std::string worker_error;
    std::vector<std::thread> workers;
    workers.reserve(static_cast<std::size_t>(threads));
    WorkerJoinGuard join_guard(stop, workers);
    auto accepted_before = session.accepted();
    auto started = std::chrono::steady_clock::now();
    for (int index = 0; index < threads; ++index) {
        workers.emplace_back([&, index] {
            try {
                if (index < static_cast<int>(result.cpus.size())) {
                    (void)cryptosteer_set_current_thread_affinity(result.cpus[static_cast<std::size_t>(index)]);
                }
                auto vm = context->create_vm();
                std::shared_ptr<Job const> known_job;
                std::vector<std::uint8_t> blob;
                HashTarget target;
                std::array<std::uint8_t, 32> output{};
                while (!stop.load(std::memory_order_relaxed) && !g_auto_tune_cancelled.load(std::memory_order_acquire)) {
                    auto job = session.current_job();
                    if (!job) {
                        std::this_thread::sleep_for(std::chrono::milliseconds(5));
                        continue;
                    }
                    if (job->seed_hash != context->seed()) throw std::runtime_error("Pool seed changed during this candidate");
                    if (job != known_job) {
                        known_job = job;
                        blob = job->blob;
                        target = compile_hash_target(job->target_hex);
                    }
                    std::uint32_t nonce = session.next_nonce();
                    std::memcpy(blob.data() + job->nonce_offset, &nonce, sizeof(nonce));
                    randomx_calculate_hash(vm.get(), blob.data(), blob.size(), output.data());
                    hashes.fetch_add(1, std::memory_order_relaxed);
                    if (hash_meets_target(output, target)) {
                        session.queue_share(Share{job->job_id, nonce, hex_encode(output), job->generation});
                    }
                }
            } catch (std::exception const& exception) {
                {
                    std::lock_guard lock(worker_error_mutex);
                    if (worker_error.empty()) worker_error = exception.what();
                }
                worker_failed.store(true, std::memory_order_release);
                stop.store(true, std::memory_order_release);
            } catch (...) {
                {
                    std::lock_guard lock(worker_error_mutex);
                    if (worker_error.empty()) worker_error = "Auto-tune worker failed with an unknown exception";
                }
                worker_failed.store(true, std::memory_order_release);
                stop.store(true, std::memory_order_release);
            }
        });
    }
    auto deadline = started + duration;
    while (std::chrono::steady_clock::now() < deadline
        && !g_auto_tune_cancelled.load(std::memory_order_acquire)
        && !worker_failed.load(std::memory_order_acquire)) {
        session.throw_if_failed();
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }
    join_guard.join();
    session.throw_if_failed();
    if (g_auto_tune_cancelled.load(std::memory_order_acquire)) throw std::runtime_error("Auto-tune cancelled");
    if (worker_failed.load(std::memory_order_acquire)) {
        std::lock_guard lock(worker_error_mutex);
        throw std::runtime_error(worker_error.empty() ? "Auto-tune worker failed" : worker_error);
    }
    auto ended = std::chrono::steady_clock::now();
    double elapsed = std::chrono::duration<double>(ended - started).count();
    result.hashes = hashes.load(std::memory_order_relaxed);
    result.hashrate = elapsed > 0.0 ? static_cast<double>(result.hashes) / elapsed : 0.0;
    result.shares_submitted = session.accepted() - accepted_before;
    return result;
}

std::string candidate_json(TuneCandidate const& candidate) {
    std::ostringstream cpus;
    cpus << '[';
    for (std::size_t index = 0; index < candidate.cpus.size(); ++index) {
        if (index) cpus << ',';
        cpus << candidate.cpus[index];
    }
    cpus << ']';
    return "{\"mode\":" + json_quote(candidate.mode) +
        ",\"threads\":" + std::to_string(candidate.threads) +
        ",\"hashes\":" + std::to_string(candidate.hashes) +
        ",\"hashrate\":" + std::to_string(candidate.hashrate) +
        ",\"initializationMillis\":" + std::to_string(candidate.initialization_millis) +
        ",\"jit\":" + boolean(candidate.jit) +
        ",\"hardwareAes\":" + boolean(candidate.hardware_aes) +
        ",\"acceptedShares\":" + std::to_string(candidate.shares_submitted) +
        ",\"preferredCpus\":" + cpus.str() + "}";
}
} // namespace

std::string run_native_self_tests() {
    bool json_ok = false, donation_ok = false, hex_ok = false, vector_ok = false, jit_match = true;
    std::string error;
    try {
        auto parsed = json::parse("{\"a\":[1,true,\"x\"],\"b\":null}");
        json_ok = parsed.is_object() && parsed.find("a") && json::parse(json::stringify(parsed)).is_object();
        DonationScheduler scheduler(49ULL * 60ULL * 1000000000ULL, 0, 200);
        donation_ok = scheduler.donation_allowance() == 60ULL * 1000000000ULL;
        scheduler.add_donation(90ULL * 1000000000ULL);
        donation_ok = donation_ok && scheduler.donation_nanos() == 60ULL * 1000000000ULL && scheduler.percentage() <= 2.0;
        std::array<std::uint8_t, 32> zero{};
        hex_ok = hex_decode(hex_encode(zero)) == std::vector<std::uint8_t>(zero.begin(), zero.end());

        std::string key = "test key 000";
        std::string input = "This is a test";
        auto context = RandomXContext::create(std::span<std::uint8_t const>(reinterpret_cast<std::uint8_t const*>(key.data()), key.size()), "LIGHT", "DISABLED", 1, {});
        auto vm = context->create_vm(); std::array<std::uint8_t, 32> output{};
        randomx_calculate_hash(vm.get(), input.data(), input.size(), output.data());
        std::string interpreter = hex_encode(output);
        vector_ok = interpreter == "639183aae1bf4c9a35884cb46b09cad9175f04efd7684e7262a0ac1c2f0b4e3f";
        try {
            auto jit_context = RandomXContext::create(std::span<std::uint8_t const>(reinterpret_cast<std::uint8_t const*>(key.data()), key.size()), "LIGHT", "ENABLED", 1, {});
            auto jit_vm = jit_context->create_vm(); std::array<std::uint8_t, 32> jit_output{};
            randomx_calculate_hash(jit_vm.get(), input.data(), input.size(), jit_output.data());
            jit_match = jit_output == output;
        } catch (...) { jit_match = true; }
    } catch (std::exception const& exception) { error = exception.what(); }
    bool passed = json_ok && donation_ok && hex_ok && vector_ok && jit_match && error.empty();
    return "{\"passed\":" + boolean(passed) + ",\"json\":" + boolean(json_ok) + ",\"donation\":" + boolean(donation_ok) +
        ",\"hex\":" + boolean(hex_ok) + ",\"randomXVector\":" + boolean(vector_ok) + ",\"jitMatchesInterpreter\":" + boolean(jit_match) +
        ",\"error\":" + json_quote(error) + "}";
}

std::string run_benchmark(std::string const& config_json) {
    g_benchmark_cancelled.store(false, std::memory_order_release);
    g_benchmark_phase.store(1, std::memory_order_release);
    g_benchmark_hashes.store(0U, std::memory_order_release);
    g_benchmark_started_nanos.store(0, std::memory_order_release);

    try {
        auto config = json::parse(config_json);
        int threads = static_cast<int>(std::clamp<std::int64_t>(config.i64_or("threads", 1), 1, 32));
        int seconds = static_cast<int>(std::clamp<std::int64_t>(config.i64_or("seconds", 10), 5, 300));
        std::string mode = config.string_or("mode", "LIGHT");
        std::string jit_mode = config.string_or("jitMode", "AUTO");
        if (mode != "LIGHT" && mode != "FULL") throw std::invalid_argument("benchmark mode must be LIGHT or FULL");
        if (jit_mode != "AUTO" && jit_mode != "ENABLED" && jit_mode != "DISABLED") throw std::invalid_argument("benchmark JIT mode is invalid");
        std::vector<int> preferred_cpus;
        if (auto cpus = config.find("preferredCpus"); cpus && cpus->is_array()) {
            for (auto const& value : cpus->as_array()) {
                int cpu = static_cast<int>(value.as_i64());
                if (cpu < 0 || cpu >= 1024) throw std::invalid_argument("benchmark CPU index is invalid");
                preferred_cpus.push_back(cpu);
            }
        }
        if (!preferred_cpus.empty() && static_cast<int>(preferred_cpus.size()) < threads) threads = static_cast<int>(preferred_cpus.size());
        g_benchmark_target_seconds.store(seconds, std::memory_order_release);

        std::string key = "CryptoSteer RandomX benchmark v1";
        auto started = std::chrono::steady_clock::now();
        auto context = RandomXContext::create(
            std::span<std::uint8_t const>(reinterpret_cast<std::uint8_t const*>(key.data()), key.size()),
            mode,
            jit_mode,
            threads,
            {});
        if (g_benchmark_cancelled.load(std::memory_order_acquire)) throw std::runtime_error("Benchmark cancelled");
        auto initialized = std::chrono::steady_clock::now();
        g_benchmark_started_nanos.store(steady_nanos_now(), std::memory_order_release);
        g_benchmark_phase.store(2, std::memory_order_release);

        std::atomic<bool> stop{false};
        std::atomic<bool> worker_failed{false};
        std::atomic<std::uint64_t> hashes{0};
        std::mutex worker_error_mutex;
        std::string worker_error;
        std::vector<std::thread> workers;
        workers.reserve(static_cast<std::size_t>(threads));
        WorkerJoinGuard join_guard(stop, workers);
        for (int index = 0; index < threads; ++index) {
            workers.emplace_back([&, index] {
                try {
                    if (!preferred_cpus.empty()) {
                        (void)cryptosteer_set_current_thread_affinity(preferred_cpus[static_cast<std::size_t>(index)]);
                    }
                    auto vm = context->create_vm();
                    std::array<std::uint8_t, 76> input{};
                    std::array<std::uint8_t, 32> output{};
                    std::memcpy(input.data(), key.data(), std::min(input.size(), key.size()));
                    std::uint64_t nonce = static_cast<std::uint64_t>(index);
                    while (!stop.load(std::memory_order_relaxed) && !g_benchmark_cancelled.load(std::memory_order_acquire)) {
                        std::memcpy(input.data() + 64U, &nonce, sizeof(nonce));
                        randomx_calculate_hash(vm.get(), input.data(), input.size(), output.data());
                        ++nonce;
                        auto total = hashes.fetch_add(1U, std::memory_order_relaxed) + 1U;
                        g_benchmark_hashes.store(total, std::memory_order_relaxed);
                    }
                } catch (std::exception const& exception) {
                    {
                        std::lock_guard lock(worker_error_mutex);
                        if (worker_error.empty()) worker_error = exception.what();
                    }
                    worker_failed.store(true, std::memory_order_release);
                    stop.store(true, std::memory_order_release);
                } catch (...) {
                    {
                        std::lock_guard lock(worker_error_mutex);
                        if (worker_error.empty()) worker_error = "Benchmark worker failed with an unknown exception";
                    }
                    worker_failed.store(true, std::memory_order_release);
                    stop.store(true, std::memory_order_release);
                }
            });
        }

        auto deadline = initialized + std::chrono::seconds(seconds);
        while (std::chrono::steady_clock::now() < deadline
            && !g_benchmark_cancelled.load(std::memory_order_acquire)
            && !worker_failed.load(std::memory_order_acquire)) {
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
        }
        join_guard.join();
        if (g_benchmark_cancelled.load(std::memory_order_acquire)) throw std::runtime_error("Benchmark cancelled");
        if (worker_failed.load(std::memory_order_acquire)) {
            std::lock_guard lock(worker_error_mutex);
            throw std::runtime_error(worker_error.empty() ? "Benchmark worker failed" : worker_error);
        }

        auto ended = std::chrono::steady_clock::now();
        double elapsed = std::chrono::duration<double>(ended - initialized).count();
        auto initialization_ms = std::chrono::duration_cast<std::chrono::milliseconds>(initialized - started).count();
        std::uint64_t total = hashes.load(std::memory_order_relaxed);
        double rate = elapsed > 0.0 ? static_cast<double>(total) / elapsed : 0.0;
        g_benchmark_phase.store(3, std::memory_order_release);
        std::ostringstream cpu_json;
        cpu_json << '[';
        for (std::size_t index = 0; index < preferred_cpus.size(); ++index) {
            if (index) cpu_json << ',';
            cpu_json << preferred_cpus[index];
        }
        cpu_json << ']';
        auto result = "{\"createdAtMillis\":" + std::to_string(unix_millis_now()) +
            ",\"mode\":" + json_quote(context->mode_name()) + ",\"threads\":" + std::to_string(threads) + ",\"durationSeconds\":" + std::to_string(seconds) +
            ",\"hashes\":" + std::to_string(total) + ",\"hashrate\":" + std::to_string(rate) + ",\"peakHashrate\":" + std::to_string(rate) + ",\"initializationMillis\":" + std::to_string(initialization_ms) +
            ",\"pageSize\":" + std::to_string(cryptosteer_runtime_page_size()) + ",\"jit\":" + boolean(context->jit_enabled()) +
            ",\"hardwareAes\":" + boolean(context->hardware_aes()) + ",\"preferredCpus\":" + cpu_json.str() + "}";
        g_benchmark_phase.store(0, std::memory_order_release);
        return result;
    } catch (...) {
        g_benchmark_phase.store(0, std::memory_order_release);
        throw;
    }
}

std::string benchmark_progress() {
    int phase = g_benchmark_phase.load(std::memory_order_acquire);
    std::int64_t started = g_benchmark_started_nanos.load(std::memory_order_acquire);
    std::int64_t elapsed = phase >= 2 && started > 0 ? std::max<std::int64_t>(0, steady_nanos_now() - started) / 1'000'000LL : 0;
    std::string phase_name = phase == 1 ? "PREPARING" : phase == 2 ? "RUNNING" : phase == 3 ? "FINISHING" : "IDLE";
    if (g_benchmark_cancelled.load(std::memory_order_acquire) && phase != 0) phase_name = "CANCELLING";
    return "{\"phase\":" + json_quote(phase_name) +
        ",\"elapsedMillis\":" + std::to_string(elapsed) +
        ",\"targetSeconds\":" + std::to_string(g_benchmark_target_seconds.load(std::memory_order_acquire)) +
        ",\"hashes\":" + std::to_string(g_benchmark_hashes.load(std::memory_order_acquire)) + "}";
}

void cancel_benchmark() noexcept {
    g_benchmark_cancelled.store(true, std::memory_order_release);
}

std::string run_auto_tune(std::string const& config_json) {
    g_auto_tune_cancelled.store(false, std::memory_order_release);
    auto wall_started = std::chrono::steady_clock::now();
    auto config = json::parse(config_json);
    if (!config.is_object()) throw std::invalid_argument("auto-tune configuration must be an object");
    int requested_seconds = static_cast<int>(std::clamp<std::int64_t>(config.i64_or("durationSeconds", 300), 60, 600));

    Config connection_config;
    connection_config.username = config.string_or("username");
    connection_config.password = config.string_or("password");
    connection_config.worker = config.string_or("worker");
    connection_config.rig_id = config.string_or("rigId", connection_config.worker);
    connection_config.connect_timeout_seconds = static_cast<int>(std::clamp<std::int64_t>(config.i64_or("connectTimeoutSeconds", 15), 3, 120));
    connection_config.retry_limit = static_cast<int>(std::clamp<std::int64_t>(config.i64_or("retryLimit", 12), 0, 100));
    connection_config.allow_insecure_tls = config.bool_or("allowInsecureTls", false);
    if (connection_config.username.empty() || connection_config.username.size() > 512U
        || connection_config.password.empty() || connection_config.password.size() > 512U
        || connection_config.worker.empty() || connection_config.worker.size() > 128U) {
        throw std::invalid_argument("auto-tune pool credentials are missing or too long");
    }
    auto endpoint_values = config.find("endpoints");
    if (!endpoint_values || !endpoint_values->is_array() || endpoint_values->as_array().empty() || endpoint_values->as_array().size() > 16U) {
        throw std::invalid_argument("auto-tune requires one to sixteen pool endpoints");
    }
    std::vector<Endpoint> endpoints;
    endpoints.reserve(endpoint_values->as_array().size());
    for (auto const& value : endpoint_values->as_array()) endpoints.push_back(parse_tune_endpoint(value));

    LiveTuneSession session(
        std::move(connection_config),
        std::move(endpoints),
        config.string_or("username"),
        config.string_or("rigId", config.string_or("worker")));
    session.start();
    session.wait_for_job(std::chrono::seconds(std::max(5, static_cast<int>(config.i64_or("connectTimeoutSeconds", 15)))));

    int available = std::clamp(cryptosteer_online_cpu_count(), 1, 16);
    CpuTopology topology;
    auto ordered = topology.recommended_order();
    if (ordered.empty()) {
        for (int index = 0; index < available; ++index) ordered.push_back(index);
    }
    if (static_cast<int>(ordered.size()) < available) available = static_cast<int>(ordered.size());
    std::vector<int> counts;
    for (int count = 1; count <= available; ++count) counts.push_back(count);

    auto measurement_budget = std::chrono::milliseconds(static_cast<long long>(requested_seconds) * 1'000LL);
    auto measurement_used = std::chrono::milliseconds(0);
    std::vector<TuneCandidate> results;
    std::vector<std::string> mode_errors;
    int total_candidates = std::max(1, static_cast<int>(counts.size()) * 2);
    int completed_candidates = 0;
    for (std::string const& requested_mode : {std::string("FULL"), std::string("LIGHT")}) {
        if (g_auto_tune_cancelled.load(std::memory_order_acquire)) throw std::runtime_error("Auto-tune cancelled");
        if (measurement_used >= measurement_budget) break;
        try {
            auto job = session.current_job();
            if (!job) throw std::runtime_error("The live pool job disappeared before RandomX initialization");
            auto init_started = std::chrono::steady_clock::now();
            auto context = RandomXContext::create(job->seed_hash, requested_mode, "ENABLED", available, {});
            auto initialization_millis = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - init_started).count();
            for (int count : counts) {
                if (g_auto_tune_cancelled.load(std::memory_order_acquire)) throw std::runtime_error("Auto-tune cancelled");
                if (measurement_used >= measurement_budget) break;
                int candidates_left = std::max(1, total_candidates - completed_candidates);
                auto remaining_ms = measurement_budget - measurement_used;
                auto candidate_ms = remaining_ms / candidates_left;
                candidate_ms = std::min(remaining_ms, std::max(candidate_ms, std::chrono::milliseconds(5'000)));
                if (candidate_ms < std::chrono::milliseconds(1'000)) break;
                results.push_back(benchmark_live_candidate(
                    session,
                    context,
                    count,
                    candidate_ms,
                    ordered,
                    initialization_millis));
                measurement_used += candidate_ms;
                ++completed_candidates;
            }
        } catch (std::exception const& exception) {
            if (g_auto_tune_cancelled.load(std::memory_order_acquire)) throw;
            if (!session.healthy()) session.throw_if_failed();
            mode_errors.push_back("{\"mode\":" + json_quote(requested_mode) + ",\"error\":" + json_quote(exception.what()) + "}");
            completed_candidates += static_cast<int>(counts.size());
        }
    }
    session.stop();
    if (g_auto_tune_cancelled.load(std::memory_order_acquire)) throw std::runtime_error("Auto-tune cancelled");
    if (results.empty()) throw std::runtime_error("No RandomX auto-tune candidate completed");
    auto best = std::max_element(results.begin(), results.end(), [](TuneCandidate const& left, TuneCandidate const& right) {
        return left.hashrate < right.hashrate;
    });
    std::ostringstream tests;
    tests << '[';
    for (std::size_t index = 0; index < results.size(); ++index) {
        if (index) tests << ',';
        tests << candidate_json(results[index]);
    }
    tests << ']';
    std::ostringstream errors;
    errors << '[';
    for (std::size_t index = 0; index < mode_errors.size(); ++index) {
        if (index) errors << ',';
        errors << mode_errors[index];
    }
    errors << ']';
    auto elapsed_seconds = std::chrono::duration_cast<std::chrono::seconds>(
        std::chrono::steady_clock::now() - wall_started).count();
    return "{\"createdAtMillis\":" + std::to_string(unix_millis_now()) +
        ",\"pool\":" + json_quote(session.active_endpoint()) +
        ",\"requestedDurationSeconds\":" + std::to_string(requested_seconds) +
        ",\"elapsedSeconds\":" + std::to_string(elapsed_seconds) +
        ",\"availableCpus\":" + std::to_string(available) +
        ",\"pageSize\":" + std::to_string(cryptosteer_runtime_page_size()) +
        ",\"acceptedShares\":" + std::to_string(session.accepted()) +
        ",\"rejectedShares\":" + std::to_string(session.rejected()) +
        ",\"staleShares\":" + std::to_string(session.stale()) +
        ",\"jobsReceived\":" + std::to_string(session.jobs_received()) +
        ",\"best\":" + candidate_json(*best) +
        ",\"tests\":" + tests.str() +
        ",\"modeErrors\":" + errors.str() + "}";
}

void cancel_auto_tune() noexcept {
    g_auto_tune_cancelled.store(true, std::memory_order_release);
}

std::string native_diagnostics() {
    CpuTopology topology;
    randomx_flags flags = randomx_get_flags();
    auto const flag_bits = static_cast<unsigned>(flags);
    auto has_flag = [flag_bits](randomx_flags flag) {
        return (flag_bits & static_cast<unsigned>(flag)) != 0U;
    };
    auto const cpus = topology.cpus();
    auto const recommended = topology.recommended_order();

    std::ostringstream cpu_details;
    cpu_details << '[';
    for (std::size_t index = 0; index < cpus.size(); ++index) {
        if (index) cpu_details << ',';
        auto const& cpu = cpus[index];
        cpu_details << "{\"index\":" << cpu.logical_index
                    << ",\"capacity\":" << cpu.capacity
                    << ",\"maxFrequencyKHz\":" << cpu.max_frequency_khz << '}';
    }
    cpu_details << ']';

    std::ostringstream allowed_cpus;
    allowed_cpus << '[';
    for (std::size_t index = 0; index < cpus.size(); ++index) {
        if (index) allowed_cpus << ',';
        allowed_cpus << cpus[index].logical_index;
    }
    allowed_cpus << ']';

    std::ostringstream recommended_cpus;
    recommended_cpus << '[';
    for (std::size_t index = 0; index < recommended.size(); ++index) {
        if (index) recommended_cpus << ',';
        recommended_cpus << recommended[index];
    }
    recommended_cpus << ']';

    return "{\"nativeVersion\":\"" CRYPTOSTEER_VERSION "\",\"randomXFlags\":" + std::to_string(flag_bits) +
        ",\"jitAvailable\":" + boolean(has_flag(RANDOMX_FLAG_JIT)) +
        ",\"hardwareAesAvailable\":" + boolean(has_flag(RANDOMX_FLAG_HARD_AES)) +
        ",\"libuv\":" + json_quote(uv_version_string()) + ",\"openssl\":" + json_quote(OpenSSL_version(OPENSSL_VERSION)) +
        ",\"hwlocApi\":" + std::to_string(hwloc_get_api_version()) + ",\"pageSize\":" + std::to_string(cryptosteer_runtime_page_size()) +
        ",\"pageSizeSupported\":" + boolean(cryptosteer_is_supported_page_size(cryptosteer_runtime_page_size()) != 0) +
        ",\"cpuTopology\":" + json_quote(topology.description()) +
        ",\"allowedCpus\":" + allowed_cpus.str() +
        ",\"recommendedCpus\":" + recommended_cpus.str() +
        ",\"cpuDetails\":" + cpu_details.str() + "}";
}
} // namespace cryptosteer
