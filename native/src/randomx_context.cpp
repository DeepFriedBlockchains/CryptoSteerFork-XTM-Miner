#include "cryptosteer/randomx_context.hpp"
#include <algorithm>
#include <atomic>
#include <fstream>
#include <stdexcept>
#include <thread>

namespace cryptosteer {
namespace {
unsigned bits(randomx_flags value) { return static_cast<unsigned>(value); }
randomx_flags with_flag(randomx_flags value, randomx_flags flag) { return static_cast<randomx_flags>(bits(value) | bits(flag)); }
randomx_flags without_flag(randomx_flags value, randomx_flags flag) { return static_cast<randomx_flags>(bits(value) & ~bits(flag)); }
bool has_flag(randomx_flags value, randomx_flags flag) { return (bits(value) & bits(flag)) != 0U; }
} // namespace

void RandomXContext::VmDeleter::operator()(randomx_vm* vm) const noexcept { if (vm) randomx_destroy_vm(vm); }
RandomXContext::~RandomXContext() {
    if (dataset_) randomx_release_dataset(dataset_);
    if (cache_) randomx_release_cache(cache_);
}

std::uint64_t RandomXContext::available_memory_bytes() {
    std::ifstream input("/proc/meminfo");
    std::string key; std::uint64_t value = 0; std::string unit;
    while (input >> key >> value >> unit) {
        if (key == "MemAvailable:") return value * 1024ULL;
    }
    return 0;
}

std::shared_ptr<RandomXContext> RandomXContext::create(std::span<std::uint8_t const> seed, std::string mode, std::string jit_mode, int initializer_threads, Progress progress) {
    if (seed.empty() || seed.size() > 1024U) throw std::invalid_argument("RandomX seed is invalid");
    auto context = std::shared_ptr<RandomXContext>(new RandomXContext());
    context->seed_.assign(seed.begin(), seed.end());
    context->flags_ = randomx_get_flags();
    if (jit_mode == "DISABLED") {
        context->flags_ = without_flag(without_flag(context->flags_, RANDOMX_FLAG_JIT), RANDOMX_FLAG_SECURE);
    } else if (jit_mode == "ENABLED" && !has_flag(context->flags_, RANDOMX_FLAG_JIT)) {
        context->flags_ = with_flag(context->flags_, RANDOMX_FLAG_JIT);
    }
    if (has_flag(context->flags_, RANDOMX_FLAG_JIT)) context->flags_ = with_flag(context->flags_, RANDOMX_FLAG_SECURE);

    auto initialize_cache = [&context]() {
        context->cache_ = randomx_alloc_cache(context->flags_);
        if (!context->cache_) return false;
        randomx_init_cache(context->cache_, context->seed_.data(), context->seed_.size());
        return true;
    };
    bool cache_ready = initialize_cache();
    if (cache_ready && has_flag(context->flags_, RANDOMX_FLAG_JIT)) {
        randomx_vm* probe = randomx_create_vm(context->flags_, context->cache_, nullptr);
        if (probe) {
            randomx_destroy_vm(probe);
        } else {
            randomx_release_cache(context->cache_);
            context->cache_ = nullptr;
            cache_ready = false;
        }
    }
    if (!cache_ready && has_flag(context->flags_, RANDOMX_FLAG_JIT)) {
        context->flags_ = without_flag(without_flag(context->flags_, RANDOMX_FLAG_JIT), RANDOMX_FLAG_SECURE);
        cache_ready = initialize_cache();
    }
    if (!cache_ready) throw std::runtime_error("RandomX cache allocation failed in JIT and portable modes");
    if (progress) progress(10);

    bool request_full = mode == "FULL";
    bool allow_full = mode != "LIGHT";
    constexpr std::uint64_t safe_full_memory_floor = 3ULL * 1024ULL * 1024ULL * 1024ULL;
    if (allow_full && (request_full || available_memory_bytes() >= safe_full_memory_floor)) {
        randomx_flags full_flags = with_flag(context->flags_, RANDOMX_FLAG_FULL_MEM);
        context->dataset_ = randomx_alloc_dataset(full_flags);
        if (context->dataset_) {
            context->flags_ = full_flags;
            context->initialize_dataset(std::max(1, initializer_threads), progress);
        } else if (request_full) {
            throw std::runtime_error("RandomX full-memory dataset allocation failed; choose light or automatic mode");
        }
    }
    if (!context->dataset_ && progress) progress(100);
    return context;
}

void RandomXContext::initialize_dataset(int threads, Progress const& progress) {
    unsigned long count = randomx_dataset_item_count();
    int worker_count = std::max(1, std::min<int>(threads, static_cast<int>(std::thread::hardware_concurrency() == 0U ? 1U : std::thread::hardware_concurrency())));
    std::atomic<unsigned long> completed{0};
    std::vector<std::thread> workers;
    workers.reserve(static_cast<std::size_t>(worker_count));
    for (int index = 0; index < worker_count; ++index) {
        unsigned long begin = count * static_cast<unsigned long>(index) / static_cast<unsigned long>(worker_count);
        unsigned long end = count * static_cast<unsigned long>(index + 1) / static_cast<unsigned long>(worker_count);
        workers.emplace_back([this, begin, end, &completed, count, progress] {
            constexpr unsigned long chunk = 32768UL;
            for (unsigned long current = begin; current < end; current += chunk) {
                unsigned long items = std::min(chunk, end - current);
                randomx_init_dataset(dataset_, cache_, current, items);
                unsigned long done = completed.fetch_add(items, std::memory_order_relaxed) + items;
                if (progress) progress(10 + static_cast<int>((done * 90UL) / std::max(1UL, count)));
            }
        });
    }
    for (auto& worker : workers) worker.join();
    if (progress) progress(100);
}

RandomXContext::Vm RandomXContext::create_vm() const {
    randomx_vm* vm = randomx_create_vm(flags_, cache_, dataset_);
    if (!vm) throw std::runtime_error("RandomX VM allocation failed");
    return Vm(vm);
}
bool RandomXContext::jit_enabled() const noexcept { return has_flag(flags_, RANDOMX_FLAG_JIT); }
bool RandomXContext::hardware_aes() const noexcept { return has_flag(flags_, RANDOMX_FLAG_HARD_AES); }
} // namespace cryptosteer
