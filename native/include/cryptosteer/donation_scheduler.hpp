#pragma once
#include <atomic>
#include <cstdint>
namespace cryptosteer {
class DonationScheduler {
public:
    DonationScheduler(std::uint64_t user_nanos, std::uint64_t donation_nanos, std::uint32_t basis_points = 200);
    [[nodiscard]] bool donation_due(std::uint64_t minimum_slice_nanos = 1'000'000'000ULL) const noexcept;
    [[nodiscard]] std::uint64_t donation_allowance() const noexcept;
    void add_user(std::uint64_t nanos) noexcept;
    bool set_basis_points(std::uint32_t basis_points) noexcept;
    [[nodiscard]] std::uint32_t basis_points() const noexcept;
    void add_donation(std::uint64_t nanos) noexcept;
    [[nodiscard]] std::uint64_t user_nanos() const noexcept;
    [[nodiscard]] std::uint64_t donation_nanos() const noexcept;
    [[nodiscard]] double percentage() const noexcept;
private:
    std::uint64_t user_;
    std::uint64_t donation_;
    std::atomic<std::uint32_t> basis_points_;
};
} // namespace cryptosteer
