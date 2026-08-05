#pragma once

#include "cryptosteer/donation_scheduler.hpp"
#include "cryptosteer/hex.hpp"
#include "cryptosteer/model.hpp"
#include "cryptosteer/randomx_context.hpp"
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace cryptosteer {

class EngineCallbacks {
public:
    virtual ~EngineCallbacks() = default;
    virtual void state(std::string const& state, std::string const& message) = 0;
    virtual void stats(std::string const& json) = 0;
    virtual void log(std::string const& level, std::string const& message) = 0;
    virtual void accounting(std::uint64_t user_nanos, std::uint64_t donation_nanos) = 0;
};

Config parse_config(std::string const& json_text);

class MinerEngine final {
public:
    explicit MinerEngine(std::shared_ptr<EngineCallbacks> callbacks);
    ~MinerEngine();
    MinerEngine(MinerEngine const&) = delete;
    MinerEngine& operator=(MinerEngine const&) = delete;

    bool start(Config config);
    bool pause();
    bool resume();
    bool update_donation_basis_points(int basis_points);
    void stop() noexcept;
    [[nodiscard]] std::string diagnostics() const;

private:
    struct WorkBundle {
        Job job;
        HashTarget target;
        std::shared_ptr<RandomXContext> context;
        bool donation = false;
    };

    void control_loop();
    void worker_loop(std::size_t index);
    void publish_stats();
    void install_job(Job job, bool donation);
    void clear_work();
    void drain_shares(class StratumClient& client, std::uint64_t generation);
    void account_elapsed(bool donation, std::chrono::steady_clock::duration elapsed);
    [[nodiscard]] int selected_worker_count() const;
    [[nodiscard]] Endpoint donation_endpoint() const;
    void safe_state(std::string const& state, std::string const& message = {}) noexcept;
    void safe_log(std::string const& level, std::string const& message) const noexcept;

    std::shared_ptr<EngineCallbacks> callbacks_;
    mutable std::mutex lifecycle_mutex_;
    mutable std::mutex status_mutex_;
    std::mutex work_mutex_;
    std::condition_variable work_cv_;
    std::mutex share_mutex_;
    std::deque<Share> shares_;
    Config config_;
    std::unique_ptr<DonationScheduler> scheduler_;
    RuntimeStats runtime_;
    std::atomic<bool> running_{false};
    std::atomic<bool> paused_{false};
    std::atomic<bool> stop_requested_{false};
    std::atomic<bool> pool_connected_{false};
    std::atomic<bool> job_received_{false};
    std::atomic<std::uint64_t> generation_{0};
    std::atomic<std::uint32_t> next_nonce_{0};
    std::shared_ptr<WorkBundle const> work_;
    std::shared_ptr<RandomXContext> cached_context_;
    std::vector<std::uint8_t> cached_seed_;
    std::thread control_thread_;
    std::vector<std::thread> workers_;
    std::vector<std::unique_ptr<std::atomic<std::uint64_t>>> worker_hashes_;
    std::vector<int> cpu_assignments_;
    std::chrono::steady_clock::time_point session_started_{};
    std::uint64_t stats_previous_total_ = 0;
    std::vector<std::uint64_t> stats_previous_workers_;
    double smoothed_hashrate_ = 0.0;
    std::vector<double> smoothed_thread_hashrates_;
    std::uint64_t session_start_user_nanos_ = 0;
    std::uint64_t session_start_donation_nanos_ = 0;
    std::chrono::steady_clock::time_point stats_previous_at_{};
    std::atomic<int> dataset_progress_{0};
    std::string active_pool_;
    std::string active_state_ = "STOPPED";
    std::string status_message_;
    std::string last_error_;
    std::string active_donation_worker_;
};

} // namespace cryptosteer
