#include "cryptosteer/miner_engine.hpp"
#include "cryptosteer/cpu_topology.hpp"
#include "cryptosteer/hex.hpp"
#include "cryptosteer/json.hpp"
#include "cryptosteer/platform/affinity.h"
#include "cryptosteer/platform/page_size.h"
#include "cryptosteer/platform/secure_zero.h"
#include "cryptosteer/stratum_client.hpp"
#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstring>
#include <limits>
#include <random>
#include <sstream>
#include <stdexcept>
#include <utility>

namespace cryptosteer {
namespace {
constexpr char kDonationWallet[] = "44VNzHZAE1HK3NQZRPeDkMQ8pjDzivtq2eAE4iZLuLaSLZiu3wmCswrCUEm7NnWGMUB4k6cK7p7S7EXfJU7FbVTUMczHdSE";
constexpr char kDonationHost[] = "gulf.moneroocean.stream";
constexpr std::uint16_t kDonationPort = 10128;
constexpr char kDonationWorkerPrefix[] = "CryptoSteer";
constexpr std::uint64_t kDonationMinimumSliceNanos = 30'000'000'000ULL;
constexpr std::size_t kMaximumShareQueue = 1024U;
constexpr int kMaximumThreads = 64;
constexpr auto kStatsInterval = std::chrono::milliseconds(200);

void wipe_string(std::string& value) noexcept {
    if (!value.empty()) cryptosteer_secure_zero(value.data(), value.size());
    value.clear();
}

class ScopedPasswordWipe final {
public:
    explicit ScopedPasswordWipe(std::string& value) noexcept : value_(value) { }
    ScopedPasswordWipe(ScopedPasswordWipe const&) = delete;
    ScopedPasswordWipe& operator=(ScopedPasswordWipe const&) = delete;
    ~ScopedPasswordWipe() { wipe_string(value_); }
private:
    std::string& value_;
};

std::string require_string(json::Value const& object, std::string_view key, std::size_t maximum, bool allow_empty = false) {
    std::string value = object.string_or(key);
    if ((!allow_empty && value.empty()) || value.size() > maximum) {
        throw std::invalid_argument(std::string(key) + " is missing or outside its allowed length");
    }
    return value;
}

std::uint64_t nonnegative_u64(json::Value const& object, std::string_view key) {
    auto value = object.find(key);
    if (!value) return 0U;
    if (value->is_string()) {
        auto const& text = value->as_string();
        if (text.empty() || text.size() > 20U || !std::all_of(text.begin(), text.end(), [](char c) { return c >= '0' && c <= '9'; })) {
            throw std::invalid_argument(std::string(key) + " is not an unsigned integer");
        }
        try { return std::stoull(text); }
        catch (...) { throw std::invalid_argument(std::string(key) + " is outside the unsigned 64-bit range"); }
    }
    if (value->is_number()) {
        auto signed_value = value->as_i64();
        return signed_value < 0 ? 0U : static_cast<std::uint64_t>(signed_value);
    }
    throw std::invalid_argument(std::string(key) + " has an invalid type");
}

Endpoint parse_endpoint(json::Value const& value) {
    if (!value.is_object()) throw std::invalid_argument("pool endpoint must be an object");
    Endpoint endpoint;
    endpoint.host = require_string(value, "host", 253);
    auto port = value.i64_or("port", 0);
    if (port < 1 || port > 65535) throw std::invalid_argument("pool endpoint port must be between 1 and 65535");
    endpoint.port = static_cast<std::uint16_t>(port);
    endpoint.tls = value.bool_or("tls", true);
    endpoint.server_name = value.string_or("serverName");
    endpoint.pinned_sha256 = value.string_or("pinnedSha256");
    if (endpoint.server_name.size() > 253U || endpoint.pinned_sha256.size() > 128U) throw std::invalid_argument("pool TLS metadata is too long");
    return endpoint;
}

std::string bool_text(bool value) { return value ? "true" : "false"; }
std::string json_quote(std::string_view value) { return "\"" + json::escape(value) + "\""; }
std::uint64_t duration_nanos(std::chrono::steady_clock::duration value) {
    auto count = std::chrono::duration_cast<std::chrono::nanoseconds>(value).count();
    return count <= 0 ? 0U : static_cast<std::uint64_t>(count);
}
std::uint64_t unix_millis_now() {
    auto value = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
    return value < 0 ? 0U : static_cast<std::uint64_t>(value);
}

std::string random_donation_worker() {
    static constexpr char alphabet[] = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    std::random_device device;
    std::array<unsigned int, 14> entropy{};
    for (auto& value : entropy) value = device();
    std::string worker = std::string(kDonationWorkerPrefix) + '-';
    worker.reserve(worker.size() + 14U);
    for (unsigned int value : entropy) worker.push_back(alphabet[value % (sizeof(alphabet) - 1U)]);
    return worker;
}
} // namespace

Config parse_config(std::string const& json_text) {
    if (json_text.empty() || json_text.size() > 256U * 1024U) throw std::invalid_argument("native configuration is empty or too large");
    auto root = json::parse(json_text);
    if (!root.is_object()) throw std::invalid_argument("native configuration root must be an object");
    Config config;
    config.profile_id = require_string(root, "profileId", 128);
    config.profile_name = require_string(root, "profileName", 128);
    config.username = require_string(root, "username", 512);
    config.password = root.string_or("password");
    config.worker = root.string_or("worker");
    config.rig_id = root.string_or("rigId");
    if (config.password.size() > 512U || config.worker.size() > 128U || config.rig_id.size() > 128U) throw std::invalid_argument("pool authentication field is too long");
    config.keep_alive = root.bool_or("keepAlive", true);
    config.connect_timeout_seconds = static_cast<int>(std::clamp<std::int64_t>(root.i64_or("connectTimeoutSeconds", 15), 5, 120));
    config.retry_limit = static_cast<int>(std::clamp<std::int64_t>(root.i64_or("retryLimit", 12), 0, 1000));
    config.allow_insecure_tls = root.bool_or("allowInsecureTls", false);
    config.developer_wallet = require_string(root, "developerWallet", 256);
    if (config.developer_wallet != kDonationWallet) throw std::invalid_argument("donation wallet does not match this signed build");
    auto donation_pool = root.find("donationPool");
    if (!donation_pool || !donation_pool->is_object()
        || donation_pool->string_or("host") != kDonationHost
        || donation_pool->i64_or("port", 0) != kDonationPort
        || donation_pool->bool_or("tls", true)
        || donation_pool->string_or("workerPrefix") != kDonationWorkerPrefix) {
        throw std::invalid_argument("donation pool configuration does not match this signed build");
    }
    config.donation_basis_points = static_cast<int>(std::clamp<std::int64_t>(root.i64_or("donationBasisPoints", 200), 0, 9999));
    if (config.donation_basis_points != 200 && config.donation_basis_points != 100) throw std::invalid_argument("this build permits only the disclosed 2.00% or earned 1.00% donation tiers");
    config.persisted_user_nanos = nonnegative_u64(root, "persistedUserNanos");
    config.persisted_donation_nanos = nonnegative_u64(root, "persistedDonationNanos");

    auto endpoints = root.find("endpoints");
    if (!endpoints || !endpoints->is_array() || endpoints->as_array().empty() || endpoints->as_array().size() > 16U) {
        throw std::invalid_argument("one to sixteen pool endpoints are required");
    }
    for (auto const& value : endpoints->as_array()) config.endpoints.push_back(parse_endpoint(value));

    auto tuning = root.find("tuning");
    if (!tuning || !tuning->is_object()) throw std::invalid_argument("tuning configuration is missing");
    config.tuning.thread_count = static_cast<int>(std::clamp<std::int64_t>(tuning->i64_or("threadCount", 0), 0, kMaximumThreads));
    config.tuning.cpu_utilization_percent = static_cast<int>(std::clamp<std::int64_t>(tuning->i64_or("cpuUtilizationPercent", 75), 10, 100));
    config.tuning.duty_cycle_percent = static_cast<int>(std::clamp<std::int64_t>(tuning->i64_or("dutyCyclePercent", 100), 10, 100));
    config.tuning.randomx_mode = tuning->string_or("randomXMode", "AUTO");
    config.tuning.jit_mode = tuning->string_or("jitMode", "AUTO");
    if (config.tuning.randomx_mode != "AUTO" && config.tuning.randomx_mode != "LIGHT" && config.tuning.randomx_mode != "FULL") throw std::invalid_argument("invalid RandomX mode");
    if (config.tuning.jit_mode != "AUTO" && config.tuning.jit_mode != "ENABLED" && config.tuning.jit_mode != "DISABLED") throw std::invalid_argument("invalid JIT mode");
    if (auto cpus = tuning->find("preferredCpus"); cpus && cpus->is_array()) {
        if (cpus->as_array().size() > static_cast<std::size_t>(kMaximumThreads)) throw std::invalid_argument("too many preferred CPU entries");
        for (auto const& value : cpus->as_array()) {
            int cpu = static_cast<int>(value.as_i64());
            if (cpu < 0 || cpu >= 1024) throw std::invalid_argument("preferred CPU index is invalid");
            config.tuning.preferred_cpus.push_back(cpu);
        }
    }
    return config;
}

MinerEngine::MinerEngine(std::shared_ptr<EngineCallbacks> callbacks) : callbacks_(std::move(callbacks)) {
    if (!callbacks_) throw std::invalid_argument("engine callbacks are required");
}

MinerEngine::~MinerEngine() {
    stop();
    wipe_string(config_.password);
}

bool MinerEngine::start(Config config) {
    std::lock_guard lock(lifecycle_mutex_);
    if (running_.load(std::memory_order_acquire)) return false;
    wipe_string(config_.password);
    config_ = std::move(config);
    wipe_string(config.password);
    scheduler_ = std::make_unique<DonationScheduler>(config_.persisted_user_nanos, config_.persisted_donation_nanos, static_cast<std::uint32_t>(config_.donation_basis_points));
    runtime_.hashes.store(0, std::memory_order_relaxed);
    runtime_.accepted.store(0, std::memory_order_relaxed);
    runtime_.rejected.store(0, std::memory_order_relaxed);
    runtime_.stale.store(0, std::memory_order_relaxed);
    runtime_.last_accepted_millis.store(0, std::memory_order_relaxed);
    runtime_.last_job_millis.store(0, std::memory_order_relaxed);
    runtime_.latency_millis.store(0, std::memory_order_relaxed);
    runtime_.user_nanos.store(scheduler_->user_nanos(), std::memory_order_relaxed);
    runtime_.donation_nanos.store(scheduler_->donation_nanos(), std::memory_order_relaxed);
    {
        std::lock_guard share_lock(share_mutex_);
        shares_.clear();
    }
    stats_previous_total_ = 0;
    stats_previous_workers_.clear();
    smoothed_hashrate_ = 0.0;
    smoothed_thread_hashrates_.clear();
    session_start_user_nanos_ = scheduler_->user_nanos();
    session_start_donation_nanos_ = scheduler_->donation_nanos();
    stats_previous_at_ = std::chrono::steady_clock::now();
    stop_requested_.store(false, std::memory_order_release);
    paused_.store(false, std::memory_order_release);
    pool_connected_.store(false, std::memory_order_release);
    job_received_.store(false, std::memory_order_release);
    running_.store(true, std::memory_order_release);
    session_started_ = std::chrono::steady_clock::now();
    cached_context_.reset();
    cached_seed_.clear();
    active_donation_worker_.clear();
    dataset_progress_.store(0, std::memory_order_relaxed);
    {
        std::lock_guard status_lock(status_mutex_);
        active_state_ = "STARTING";
        status_message_ = "Starting native engine";
        active_pool_.clear();
        last_error_.clear();
    }

    int count = selected_worker_count();
    worker_hashes_.clear();
    workers_.clear();
    cpu_assignments_.clear();
    CpuTopology topology;
    auto ordered = config_.tuning.preferred_cpus.empty() ? topology.recommended_order() : config_.tuning.preferred_cpus;
    for (int index = 0; index < count; ++index) {
        worker_hashes_.push_back(std::make_unique<std::atomic<std::uint64_t>>(0));
        cpu_assignments_.push_back(index < static_cast<int>(ordered.size()) ? ordered[static_cast<std::size_t>(index)] : -1);
    }
    try {
        workers_.reserve(static_cast<std::size_t>(count));
        for (int index = 0; index < count; ++index) workers_.emplace_back(&MinerEngine::worker_loop, this, static_cast<std::size_t>(index));
        control_thread_ = std::thread(&MinerEngine::control_loop, this);
    } catch (...) {
        stop_requested_.store(true, std::memory_order_release);
        work_cv_.notify_all();
        for (auto& worker : workers_) if (worker.joinable()) worker.join();
        workers_.clear();
        running_.store(false, std::memory_order_release);
        wipe_string(config_.password);
        throw;
    }
    safe_state("STARTING", "Native mining engine started");
    return true;
}

bool MinerEngine::pause() {
    if (!running_.load(std::memory_order_acquire)) return false;
    bool expected = false;
    if (!paused_.compare_exchange_strong(expected, true, std::memory_order_acq_rel)) return true;
    clear_work();
    safe_state("PAUSED", "Mining paused");
    work_cv_.notify_all();
    return true;
}

bool MinerEngine::update_donation_basis_points(int basis_points) {
    if (basis_points != 100 && basis_points != 200) return false;
    std::lock_guard lock(lifecycle_mutex_);
    config_.donation_basis_points = basis_points;
    return scheduler_ ? scheduler_->set_basis_points(static_cast<std::uint32_t>(basis_points)) : true;
}

bool MinerEngine::resume() {
    if (!running_.load(std::memory_order_acquire)) return false;
    bool expected = true;
    if (!paused_.compare_exchange_strong(expected, false, std::memory_order_acq_rel)) return true;
    safe_state("CONNECTING", "Reconnecting after pause");
    work_cv_.notify_all();
    return true;
}

void MinerEngine::stop() noexcept {
    std::unique_lock lock(lifecycle_mutex_);
    if (!running_.exchange(false, std::memory_order_acq_rel) && !control_thread_.joinable()) return;
    stop_requested_.store(true, std::memory_order_release);
    paused_.store(false, std::memory_order_release);
    safe_state("STOPPING", "Stopping workers and pool connection");
    clear_work();
    work_cv_.notify_all();
    lock.unlock();
    if (control_thread_.joinable() && control_thread_.get_id() != std::this_thread::get_id()) control_thread_.join();
    for (auto& worker : workers_) if (worker.joinable() && worker.get_id() != std::this_thread::get_id()) worker.join();
    lock.lock();
    workers_.clear();
    worker_hashes_.clear();
    cpu_assignments_.clear();
    cached_context_.reset();
    cached_seed_.clear();
    pool_connected_.store(false, std::memory_order_release);
    job_received_.store(false, std::memory_order_release);
    wipe_string(config_.password);
    {
        std::lock_guard status_lock(status_mutex_);
        active_pool_.clear();
    }
    if (scheduler_) {
        runtime_.user_nanos.store(scheduler_->user_nanos(), std::memory_order_relaxed);
        runtime_.donation_nanos.store(scheduler_->donation_nanos(), std::memory_order_relaxed);
        try { callbacks_->accounting(scheduler_->user_nanos(), scheduler_->donation_nanos()); } catch (...) { }
    }
    safe_state("STOPPED", "Mining stopped");
}

int MinerEngine::selected_worker_count() const {
    int available = std::max(1, cryptosteer_online_cpu_count());
    int requested = config_.tuning.thread_count > 0 ? config_.tuning.thread_count : static_cast<int>(std::ceil(available * (config_.tuning.cpu_utilization_percent / 100.0)));
    return std::clamp(requested, 1, std::min(available, kMaximumThreads));
}

Endpoint MinerEngine::donation_endpoint() const {
    Endpoint endpoint;
    endpoint.host = kDonationHost;
    endpoint.port = kDonationPort;
    endpoint.tls = false;
    endpoint.server_name = kDonationHost;
    return endpoint;
}

void MinerEngine::safe_state(std::string const& state_name, std::string const& message) noexcept {
    {
        std::lock_guard status_lock(status_mutex_);
        active_state_ = state_name;
        status_message_ = message;
        if (state_name == "MINING_USER" || state_name == "MINING_DONATION" || state_name == "STOPPED") last_error_.clear();
    }
    try { callbacks_->state(state_name, message); } catch (...) { }
}

void MinerEngine::safe_log(std::string const& level, std::string const& message) const noexcept {
    try { callbacks_->log(level, message); } catch (...) { }
}

void MinerEngine::clear_work() {
    std::lock_guard lock(work_mutex_);
    work_.reset();
    job_received_.store(false, std::memory_order_release);
    generation_.fetch_add(1, std::memory_order_acq_rel);
}

void MinerEngine::install_job(Job job, bool donation) {
    std::shared_ptr<RandomXContext> context;
    bool initialized_new_context = false;
    if (cached_context_ && cached_seed_ == job.seed_hash) {
        context = cached_context_;
        dataset_progress_.store(100, std::memory_order_relaxed);
    } else {
        initialized_new_context = true;
        dataset_progress_.store(0, std::memory_order_relaxed);
        safe_state("INITIALIZING", "Preparing the " + config_.tuning.randomx_mode + " RandomX cache and dataset");
        context = RandomXContext::create(job.seed_hash, config_.tuning.randomx_mode, config_.tuning.jit_mode, selected_worker_count(), [this](int progress) {
            dataset_progress_.store(std::clamp(progress, 0, 100), std::memory_order_relaxed);
        });
        cached_context_ = context;
        cached_seed_ = job.seed_hash;
    }
    job.generation = generation_.fetch_add(1, std::memory_order_acq_rel) + 1U;
    auto bundle = std::make_shared<WorkBundle>();
    bundle->job = std::move(job);
    bundle->target = compile_hash_target(bundle->job.target_hex);
    bundle->context = std::move(context);
    bundle->donation = donation;
    {
        std::lock_guard lock(work_mutex_);
        work_ = bundle;
    }
    next_nonce_.store(static_cast<std::uint32_t>(std::random_device{}()), std::memory_order_relaxed);
    job_received_.store(true, std::memory_order_release);
    runtime_.last_job_millis.store(unix_millis_now(), std::memory_order_relaxed);
    std::string state_name = donation ? "MINING_DONATION" : "MINING_USER";
    std::string message = donation
        ? "Donation job active as " + active_donation_worker_ + " • " + bundle->context->mode_name()
        : "Pool job active • requested " + config_.tuning.randomx_mode + " • effective " + bundle->context->mode_name();
    if (!initialized_new_context) message += " • reused current dataset";
    safe_state(state_name, message);
    work_cv_.notify_all();
}

void MinerEngine::worker_loop(std::size_t index) {
    if (index < cpu_assignments_.size() && cpu_assignments_[index] >= 0) {
        int rc = cryptosteer_set_current_thread_affinity(cpu_assignments_[index]);
        if (rc != 0) safe_log("WARN", "CPU affinity was unavailable for worker " + std::to_string(index));
    }
    std::shared_ptr<WorkBundle const> current;
    std::shared_ptr<RandomXContext> known_context;
    RandomXContext::Vm vm;
    constexpr int batch_size = 4;
    while (!stop_requested_.load(std::memory_order_acquire)) {
        {
            std::unique_lock lock(work_mutex_);
            work_cv_.wait(lock, [this, &current] {
                return stop_requested_.load(std::memory_order_acquire) || (!paused_.load(std::memory_order_acquire) && work_ && work_ != current);
            });
            if (stop_requested_.load(std::memory_order_acquire)) break;
            current = work_;
        }
        if (!current) continue;
        try {
            if (!vm || known_context != current->context) {
                vm = current->context->create_vm();
                known_context = current->context;
            }
            std::vector<std::uint8_t> pending_blob = current->job.blob;
            std::vector<std::uint8_t> next_blob = current->job.blob;
            std::array<std::uint8_t, 32> hash{};
            std::uint32_t pending_nonce = next_nonce_.fetch_add(1, std::memory_order_relaxed);
            std::memcpy(pending_blob.data() + current->job.nonce_offset, &pending_nonce, sizeof(pending_nonce));
            randomx_calculate_hash_first(vm.get(), pending_blob.data(), pending_blob.size());
            bool has_pending_hash = true;

            auto record_hash = [this, index, &current](std::array<std::uint8_t, 32> const& output, std::uint32_t nonce) {
                runtime_.hashes.fetch_add(1, std::memory_order_relaxed);
                worker_hashes_[index]->fetch_add(1, std::memory_order_relaxed);
                if (hash_meets_target(output, current->target)) {
                    Share share{current->job.job_id, nonce, hex_encode(output), current->job.generation};
                    std::lock_guard share_lock(share_mutex_);
                    if (shares_.size() < kMaximumShareQueue) shares_.push_back(std::move(share));
                    else runtime_.stale.fetch_add(1, std::memory_order_relaxed);
                }
            };

            while (!stop_requested_.load(std::memory_order_acquire) && !paused_.load(std::memory_order_acquire)) {
                std::shared_ptr<WorkBundle const> observed;
                { std::lock_guard lock(work_mutex_); observed = work_; }
                if (observed != current) break;
                auto batch_started = std::chrono::steady_clock::now();
                for (int batch = 0; batch < batch_size; ++batch) {
                    std::uint32_t next_nonce = next_nonce_.fetch_add(1, std::memory_order_relaxed);
                    std::memcpy(next_blob.data() + current->job.nonce_offset, &next_nonce, sizeof(next_nonce));
                    randomx_calculate_hash_next(vm.get(), next_blob.data(), next_blob.size(), hash.data());
                    record_hash(hash, pending_nonce);
                    pending_blob.swap(next_blob);
                    pending_nonce = next_nonce;
                }
                int duty = config_.tuning.duty_cycle_percent;
                if (duty < 100) {
                    auto active = std::chrono::steady_clock::now() - batch_started;
                    auto sleep = active * (100 - duty) / duty;
                    if (sleep > std::chrono::milliseconds(0)) std::this_thread::sleep_for(sleep);
                }
            }

            if (has_pending_hash) {
                randomx_calculate_hash_last(vm.get(), hash.data());
                std::shared_ptr<WorkBundle const> observed;
                { std::lock_guard lock(work_mutex_); observed = work_; }
                if (observed == current && !stop_requested_.load(std::memory_order_acquire) && !paused_.load(std::memory_order_acquire)) {
                    record_hash(hash, pending_nonce);
                }
                has_pending_hash = false;
                (void)has_pending_hash;
            }
        } catch (std::exception const& exception) {
            vm.reset();
            known_context.reset();
            safe_log("ERROR", "Worker " + std::to_string(index) + " stopped its current job: " + exception.what());
            std::this_thread::sleep_for(std::chrono::milliseconds(250));
        }
    }
}

void MinerEngine::drain_shares(StratumClient& client, std::uint64_t generation) {
    std::deque<Share> local;
    {
        std::lock_guard lock(share_mutex_);
        local.swap(shares_);
    }
    for (auto const& share : local) {
        if (share.job_generation != generation) {
            runtime_.stale.fetch_add(1, std::memory_order_relaxed);
            continue;
        }
        try { (void)client.submit(share); }
        catch (std::exception const& exception) {
            safe_log("WARN", "Share submission failed: " + std::string(exception.what()));
            runtime_.stale.fetch_add(1, std::memory_order_relaxed);
        }
    }
}

void MinerEngine::account_elapsed(bool donation, std::chrono::steady_clock::duration elapsed) {
    if (!scheduler_) return;
    auto nanos = duration_nanos(elapsed);
    if (nanos == 0U) return;
    if (donation) scheduler_->add_donation(nanos); else scheduler_->add_user(nanos);
    runtime_.user_nanos.store(scheduler_->user_nanos(), std::memory_order_relaxed);
    runtime_.donation_nanos.store(scheduler_->donation_nanos(), std::memory_order_relaxed);
}

void MinerEngine::control_loop() {
    int failures = 0;
    int donation_failures = 0;
    std::size_t endpoint_index = 0;
    auto donation_retry_after = std::chrono::steady_clock::time_point::min();
    auto last_stats = std::chrono::steady_clock::now();
    auto last_accounting_callback = last_stats;
    try {
        while (!stop_requested_.load(std::memory_order_acquire)) {
            if (paused_.load(std::memory_order_acquire)) {
                clear_work();
                pool_connected_.store(false, std::memory_order_release);
                std::unique_lock lock(work_mutex_);
                work_cv_.wait_for(lock, std::chrono::seconds(1), [this] { return stop_requested_.load() || !paused_.load(); });
                continue;
            }
            auto iteration_started = std::chrono::steady_clock::now();
            bool donation = scheduler_ && scheduler_->donation_due(kDonationMinimumSliceNanos)
                && iteration_started >= donation_retry_after;
            std::uint64_t donation_stop_at = 0U;
            if (donation && scheduler_) {
                const std::uint64_t budget = std::min(scheduler_->donation_allowance(), kDonationMinimumSliceNanos);
                donation_stop_at = scheduler_->donation_nanos() + budget;
            }
            Endpoint endpoint = donation ? donation_endpoint() : config_.endpoints[endpoint_index % config_.endpoints.size()];
            Config connection_config = config_;
            ScopedPasswordWipe password_wipe(connection_config.password);
            std::string login = donation ? kDonationWallet : config_.username;
            std::string rig = !config_.rig_id.empty() ? config_.rig_id : config_.worker;
            if (donation) {
                active_donation_worker_ = random_donation_worker();
                rig = active_donation_worker_;
                wipe_string(connection_config.password);
                connection_config.password = active_donation_worker_;
                connection_config.allow_insecure_tls = false;
            }
            StratumClient client;
            std::string pool_name = endpoint.host + ":" + std::to_string(endpoint.port);
            {
                std::lock_guard status_lock(status_mutex_);
                active_pool_ = pool_name;
            }
            pool_connected_.store(false, std::memory_order_release);
            job_received_.store(false, std::memory_order_release);
            safe_state("CONNECTING", std::string(donation ? "Connecting donation period to " : "Connecting to ") + pool_name);
            try {
                auto connect_started = std::chrono::steady_clock::now();
                auto initial_job = client.connect_and_login(connection_config, endpoint, login, rig);
                runtime_.latency_millis.store(static_cast<std::uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - connect_started).count()), std::memory_order_relaxed);
                pool_connected_.store(true, std::memory_order_release);
                {
                    std::lock_guard status_lock(status_mutex_);
                    last_error_.clear();
                }
                failures = 0;
                if (donation) donation_failures = 0;
                if (initial_job) install_job(std::move(*initial_job), donation);
                else safe_state("CONNECTING", "Pool login accepted; waiting for a RandomX job");
                auto last_tick = std::chrono::steady_clock::now();
                auto last_keepalive = last_tick;
                while (!stop_requested_.load(std::memory_order_acquire) && !paused_.load(std::memory_order_acquire) && client.connected()) {
                    auto event = client.read_event(100);
                    auto now = std::chrono::steady_clock::now();
                    std::shared_ptr<WorkBundle const> current;
                    { std::lock_guard lock(work_mutex_); current = work_; }
                    if (current) account_elapsed(donation, now - last_tick);
                    last_tick = now;
                    if (event.kind == PoolEventKind::Job && event.job) install_job(std::move(*event.job), donation);
                    else if (event.kind == PoolEventKind::Accepted) {
                        runtime_.accepted.fetch_add(1, std::memory_order_relaxed);
                        runtime_.last_accepted_millis.store(unix_millis_now(), std::memory_order_relaxed);
                    } else if (event.kind == PoolEventKind::Rejected) {
                        runtime_.rejected.fetch_add(1, std::memory_order_relaxed);
                        safe_log("WARN", "Pool rejected a request: " + event.message);
                    } else if (event.kind == PoolEventKind::Message && !event.message.empty()) safe_log("INFO", event.message);
                    if (current) drain_shares(client, current->job.generation);
                    if (config_.keep_alive && now - last_keepalive >= std::chrono::seconds(30)) {
                        client.keepalive();
                        last_keepalive = now;
                    }
                    if (now - last_stats >= kStatsInterval) {
                        publish_stats();
                        last_stats = now;
                    }
                    if (now - last_accounting_callback >= std::chrono::seconds(10) && scheduler_) {
                        callbacks_->accounting(scheduler_->user_nanos(), scheduler_->donation_nanos());
                        last_accounting_callback = now;
                    }
                    if (donation && scheduler_ && scheduler_->donation_nanos() >= donation_stop_at) break;
                    if (!donation && scheduler_ && scheduler_->donation_due(kDonationMinimumSliceNanos)
                        && now >= donation_retry_after) break;
                }
                client.close();
                pool_connected_.store(false, std::memory_order_release);
                clear_work();
                if (!donation) endpoint_index = 0;
            } catch (std::exception const& exception) {
                client.close();
                pool_connected_.store(false, std::memory_order_release);
                clear_work();
                if (!donation) {
                    std::lock_guard status_lock(status_mutex_);
                    last_error_ = exception.what();
                }
                safe_log(donation ? "WARN" : "ERROR", std::string(donation ? "Donation pool connection failed; returning to user mining without increasing the fee: " : "Pool connection failed: ") + exception.what());
                if (donation) {
                    ++donation_failures;
                    int exponent = std::min(donation_failures, 5);
                    donation_retry_after = std::chrono::steady_clock::now()
                        + std::min(std::chrono::seconds(30 * (1 << exponent)), std::chrono::seconds(1800));
                } else {
                    ++failures;
                    ++endpoint_index;
                    if (config_.retry_limit > 0 && failures > config_.retry_limit) {
                        std::string error_text;
                        {
                            std::lock_guard status_lock(status_mutex_);
                            error_text = last_error_;
                        }
                        safe_state("ERROR", "Pool retry limit reached: " + error_text);
                        stop_requested_.store(true, std::memory_order_release);
                        break;
                    }
                    safe_state("CONNECTING", "Retrying the user pool after: " + std::string(exception.what()));
                }
                int exponent = std::min(failures, 6);
                auto delay = donation ? std::chrono::seconds(1) : std::chrono::seconds(1 << exponent);
                auto until = std::chrono::steady_clock::now() + std::min(delay, std::chrono::seconds(60));
                while (!stop_requested_.load(std::memory_order_acquire) && std::chrono::steady_clock::now() < until) std::this_thread::sleep_for(std::chrono::milliseconds(100));
            }
        }
    } catch (std::exception const& exception) {
        {
            std::lock_guard status_lock(status_mutex_);
            last_error_ = exception.what();
        }
        safe_state("ERROR", exception.what());
        safe_log("ERROR", "Native control loop stopped: " + std::string(exception.what()));
    } catch (...) {
        {
            std::lock_guard status_lock(status_mutex_);
            last_error_ = "Unknown native control-loop failure";
        }
        safe_state("ERROR", "Unknown native control-loop failure");
    }
    pool_connected_.store(false, std::memory_order_release);
    clear_work();
    if (scheduler_) {
        try { callbacks_->accounting(scheduler_->user_nanos(), scheduler_->donation_nanos()); } catch (...) { }
    }
    publish_stats();
    work_cv_.notify_all();
}

void MinerEngine::publish_stats() {
    auto now = std::chrono::steady_clock::now();
    double seconds = std::chrono::duration<double>(now - stats_previous_at_).count();
    if (seconds <= 0.0) seconds = 0.001;
    auto total = runtime_.hashes.load(std::memory_order_relaxed);
    std::uint64_t interval_hashes = total >= stats_previous_total_ ? total - stats_previous_total_ : total;
    double raw_instant = static_cast<double>(interval_hashes) / seconds;
    double smoothing = 1.0 - std::exp(-seconds / 0.55);
    if (smoothed_hashrate_ <= 0.0 || total == 0U) smoothed_hashrate_ = raw_instant;
    else smoothed_hashrate_ += smoothing * (raw_instant - smoothed_hashrate_);
    double instant = std::max(0.0, smoothed_hashrate_);
    double uptime = std::chrono::duration<double>(now - session_started_).count();
    auto current_user = runtime_.user_nanos.load(std::memory_order_relaxed);
    auto current_donation = runtime_.donation_nanos.load(std::memory_order_relaxed);
    auto active_user = current_user >= session_start_user_nanos_ ? current_user - session_start_user_nanos_ : 0U;
    auto active_donation = current_donation >= session_start_donation_nanos_ ? current_donation - session_start_donation_nanos_ : 0U;
    long double active_seconds = static_cast<long double>(active_user + active_donation) / 1'000'000'000.0L;
    double average = active_seconds > 0.0L ? static_cast<double>(static_cast<long double>(total) / active_seconds) : 0.0;
    if (stats_previous_workers_.size() != worker_hashes_.size()) stats_previous_workers_.assign(worker_hashes_.size(), 0U);
    if (smoothed_thread_hashrates_.size() != worker_hashes_.size()) smoothed_thread_hashrates_.assign(worker_hashes_.size(), 0.0);
    std::ostringstream thread_rates;
    thread_rates << '[';
    for (std::size_t index = 0; index < worker_hashes_.size(); ++index) {
        auto value = worker_hashes_[index]->load(std::memory_order_relaxed);
        if (index) thread_rates << ',';
        std::uint64_t interval = value >= stats_previous_workers_[index] ? value - stats_previous_workers_[index] : value;
        double raw_rate = static_cast<double>(interval) / seconds;
        if (smoothed_thread_hashrates_[index] <= 0.0 || value == 0U) smoothed_thread_hashrates_[index] = raw_rate;
        else smoothed_thread_hashrates_[index] += smoothing * (raw_rate - smoothed_thread_hashrates_[index]);
        thread_rates << std::max(0.0, smoothed_thread_hashrates_[index]);
        stats_previous_workers_[index] = value;
    }
    thread_rates << ']';
    std::ostringstream cpus;
    cpus << '[';
    for (std::size_t index = 0; index < cpu_assignments_.size(); ++index) {
        if (index) cpus << ',';
        cpus << cpu_assignments_[index];
    }
    cpus << ']';
    std::shared_ptr<WorkBundle const> current;
    { std::lock_guard lock(work_mutex_); current = work_; }
    std::string mode = current ? current->context->mode_name() : (cached_context_ ? cached_context_->mode_name() : "");
    bool jit = current ? current->context->jit_enabled() : (cached_context_ && cached_context_->jit_enabled());
    bool aes = current ? current->context->hardware_aes() : (cached_context_ && cached_context_->hardware_aes());
    std::string state_name;
    std::string message;
    std::string error_text;
    std::string active_pool;
    {
        std::lock_guard status_lock(status_mutex_);
        state_name = active_state_;
        message = status_message_;
        error_text = last_error_;
        active_pool = active_pool_;
    }
    std::string frame = "{\"state\":" + json_quote(state_name) +
        ",\"statusMessage\":" + json_quote(message) +
        ",\"hashrate\":" + std::to_string(instant) +
        ",\"averageHashrate\":" + std::to_string(average) +
        ",\"hashes\":" + std::to_string(total) +
        ",\"accepted\":" + std::to_string(runtime_.accepted.load()) +
        ",\"rejected\":" + std::to_string(runtime_.rejected.load()) +
        ",\"stale\":" + std::to_string(runtime_.stale.load()) +
        ",\"uptimeSeconds\":" + std::to_string(static_cast<std::uint64_t>(std::max(0.0, uptime))) +
        ",\"poolLatencyMillis\":" + std::to_string(runtime_.latency_millis.load()) +
        ",\"activePool\":" + json_quote(active_pool) +
        ",\"poolConnected\":" + bool_text(pool_connected_.load(std::memory_order_acquire)) +
        ",\"jobReceived\":" + bool_text(job_received_.load(std::memory_order_acquire)) +
        ",\"workers\":" + std::to_string(worker_hashes_.size()) +
        ",\"pageSize\":" + std::to_string(cryptosteer_runtime_page_size()) +
        ",\"jitEnabled\":" + bool_text(jit) +
        ",\"hardwareAes\":" + bool_text(aes) +
        ",\"requestedRandomXMode\":" + json_quote(config_.tuning.randomx_mode) +
        ",\"randomXMode\":" + json_quote(mode) +
        ",\"datasetProgress\":" + std::to_string(dataset_progress_.load()) +
        ",\"lastJobAtMillis\":" + std::to_string(runtime_.last_job_millis.load()) +
        ",\"lastAcceptedAtMillis\":" + std::to_string(runtime_.last_accepted_millis.load()) +
        ",\"lastError\":" + json_quote(error_text) +
        ",\"userMiningNanos\":" + std::to_string(runtime_.user_nanos.load()) +
        ",\"donationMiningNanos\":" + std::to_string(runtime_.donation_nanos.load()) +
        ",\"threadHashrates\":" + thread_rates.str() +
        ",\"cpuAssignments\":" + cpus.str() +
        ",\"updatedAtMillis\":" + std::to_string(unix_millis_now()) + "}";
    try { callbacks_->stats(frame); } catch (...) { }
    stats_previous_total_ = total;
    stats_previous_at_ = now;
}

std::string MinerEngine::diagnostics() const {
    std::lock_guard lifecycle_lock(lifecycle_mutex_);
    std::string state_name;
    std::string active_pool;
    std::string error_text;
    std::string status_message;
    {
        std::lock_guard status_lock(status_mutex_);
        state_name = active_state_;
        active_pool = active_pool_;
        error_text = last_error_;
        status_message = status_message_;
    }
    std::ostringstream out;
    out << "{\"running\":" << bool_text(running_.load())
        << ",\"paused\":" << bool_text(paused_.load())
        << ",\"state\":" << json_quote(state_name)
        << ",\"statusMessage\":" << json_quote(status_message)
        << ",\"activePool\":" << json_quote(active_pool)
        << ",\"poolConnected\":" << bool_text(pool_connected_.load())
        << ",\"jobReceived\":" << bool_text(job_received_.load())
        << ",\"workers\":" << workers_.size()
        << ",\"requestedRandomXMode\":" << json_quote(config_.tuning.randomx_mode)
        << ",\"effectiveRandomXMode\":" << json_quote(cached_context_ ? cached_context_->mode_name() : "")
        << ",\"donationPool\":" << json_quote(std::string(kDonationHost) + ":" + std::to_string(kDonationPort))
        << ",\"donationWorkerPrefix\":" << json_quote(kDonationWorkerPrefix)
        << ",\"donationTls\":false"
        << ",\"donationBasisPoints\":" << config_.donation_basis_points
        << ",\"lastError\":" << json_quote(error_text) << '}';
    return out.str();
}

} // namespace cryptosteer
