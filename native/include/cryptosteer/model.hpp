#pragma once
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

namespace cryptosteer {
struct Endpoint {
    std::string host;
    std::uint16_t port = 0;
    bool tls = true;
    std::string server_name;
    std::string pinned_sha256;
};
struct Tuning {
    int thread_count = 0;
    int cpu_utilization_percent = 75;
    int duty_cycle_percent = 100;
    std::vector<int> preferred_cpus;
    std::string randomx_mode = "AUTO";
    std::string jit_mode = "AUTO";
};
struct Config {
    std::string profile_id;
    std::string profile_name;
    std::vector<Endpoint> endpoints;
    std::string username;
    std::string password;
    std::string worker;
    std::string rig_id;
    bool keep_alive = true;
    int connect_timeout_seconds = 15;
    int retry_limit = 12;
    bool allow_insecure_tls = false;
    std::string developer_wallet;
    int donation_basis_points = 200;
    std::uint64_t persisted_user_nanos = 0;
    std::uint64_t persisted_donation_nanos = 0;
    Tuning tuning;
};
struct Job {
    std::string job_id;
    std::vector<std::uint8_t> blob;
    std::vector<std::uint8_t> seed_hash;
    std::string target_hex;
    std::uint64_t height = 0;
    std::uint32_t nonce_offset = 39;
    std::uint64_t generation = 0;
};
struct Share {
    std::string job_id;
    std::uint32_t nonce = 0;
    std::string result_hex;
    std::uint64_t job_generation = 0;
};
struct RuntimeStats {
    std::atomic<std::uint64_t> hashes{0};
    std::atomic<std::uint64_t> accepted{0};
    std::atomic<std::uint64_t> rejected{0};
    std::atomic<std::uint64_t> stale{0};
    std::atomic<std::uint64_t> user_nanos{0};
    std::atomic<std::uint64_t> donation_nanos{0};
    std::atomic<std::uint64_t> last_accepted_millis{0};
    std::atomic<std::uint64_t> last_job_millis{0};
    std::atomic<std::uint64_t> latency_millis{0};
};
} // namespace cryptosteer
