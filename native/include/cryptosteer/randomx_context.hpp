#pragma once
#include <cstdint>
#include <functional>
#include <memory>
#include <span>
#include <string>
#include <vector>
#include <randomx.h>

namespace cryptosteer {
class RandomXContext {
public:
    struct VmDeleter { void operator()(randomx_vm* vm) const noexcept; };
    using Vm = std::unique_ptr<randomx_vm, VmDeleter>;
    using Progress = std::function<void(int)>;

    ~RandomXContext();
    RandomXContext(RandomXContext const&) = delete;
    RandomXContext& operator=(RandomXContext const&) = delete;

    static std::shared_ptr<RandomXContext> create(std::span<std::uint8_t const> seed, std::string mode, std::string jit_mode, int initializer_threads, Progress progress);
    [[nodiscard]] Vm create_vm() const;
    [[nodiscard]] bool full_memory() const noexcept { return dataset_ != nullptr; }
    [[nodiscard]] bool jit_enabled() const noexcept;
    [[nodiscard]] bool hardware_aes() const noexcept;
    [[nodiscard]] randomx_flags flags() const noexcept { return flags_; }
    [[nodiscard]] std::string mode_name() const { return full_memory() ? "FULL" : "LIGHT"; }
    [[nodiscard]] std::vector<std::uint8_t> const& seed() const noexcept { return seed_; }

private:
    RandomXContext() = default;
    static std::uint64_t available_memory_bytes();
    void initialize_dataset(int threads, Progress const& progress);

    randomx_cache* cache_ = nullptr;
    randomx_dataset* dataset_ = nullptr;
    randomx_flags flags_ = RANDOMX_FLAG_DEFAULT;
    std::vector<std::uint8_t> seed_;
};
} // namespace cryptosteer
