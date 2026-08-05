#include "cryptosteer/donation_scheduler.hpp"
#include <algorithm>
#include <limits>

namespace cryptosteer {
namespace {
std::uint64_t maximum_donation_for(std::uint64_t user_nanos, std::uint32_t basis_points) noexcept {
    if (basis_points == 0U || basis_points >= 10000U) return 0U;
    std::uint64_t denominator = 10000U - basis_points;
    std::uint64_t quotient = user_nanos / denominator;
    std::uint64_t remainder = user_nanos % denominator;
    std::uint64_t maximum = 0;
    if (quotient > std::numeric_limits<std::uint64_t>::max() / basis_points) {
        maximum = std::numeric_limits<std::uint64_t>::max();
    } else {
        maximum = quotient * basis_points;
        std::uint64_t remainder_part = (remainder * basis_points) / denominator;
        maximum = std::numeric_limits<std::uint64_t>::max() - maximum < remainder_part
            ? std::numeric_limits<std::uint64_t>::max()
            : maximum + remainder_part;
    }
    return maximum;
}
} // namespace

DonationScheduler::DonationScheduler(std::uint64_t user_nanos, std::uint64_t donation_nanos, std::uint32_t basis_points)
    : user_(user_nanos), donation_(0), basis_points_(basis_points == 100U ? 100U : 200U) {
    // Persisted counters are untrusted. The historical counter may legitimately
    // be above the earned 1% tier because the account began at 2%. Preserve that
    // real history while clamping corruption to the highest compiled lifetime cap.
    donation_ = std::min(donation_nanos, maximum_donation_for(user_, 200U));
}
std::uint64_t DonationScheduler::donation_allowance() const noexcept {
    auto basis_points = basis_points_.load(std::memory_order_acquire);
    std::uint64_t maximum = maximum_donation_for(user_, basis_points);
    return maximum > donation_ ? maximum - donation_ : 0U;
}
bool DonationScheduler::donation_due(std::uint64_t minimum_slice_nanos) const noexcept { return donation_allowance() >= minimum_slice_nanos; }
void DonationScheduler::add_user(std::uint64_t nanos) noexcept { user_ = std::numeric_limits<std::uint64_t>::max() - user_ < nanos ? std::numeric_limits<std::uint64_t>::max() : user_ + nanos; }
bool DonationScheduler::set_basis_points(std::uint32_t basis_points) noexcept {
    if (basis_points != 100U && basis_points != 200U) return false;
    basis_points_.store(basis_points, std::memory_order_release);
    return true;
}
std::uint32_t DonationScheduler::basis_points() const noexcept { return basis_points_.load(std::memory_order_acquire); }
void DonationScheduler::add_donation(std::uint64_t nanos) noexcept {
    std::uint64_t grant = donation_allowance(); donation_ += std::min(nanos, grant);
}
std::uint64_t DonationScheduler::user_nanos() const noexcept { return user_; }
std::uint64_t DonationScheduler::donation_nanos() const noexcept { return donation_; }
double DonationScheduler::percentage() const noexcept {
    auto total = static_cast<long double>(user_) + static_cast<long double>(donation_);
    return total <= 0.0L ? 0.0 : static_cast<double>(static_cast<long double>(donation_) * 100.0L / total);
}
} // namespace cryptosteer
