#include "cryptosteer/donation_scheduler.hpp"
#include "cryptosteer/hex.hpp"
#include "cryptosteer/json.hpp"
#include "cryptosteer/platform/affinity.h"
#include "cryptosteer/transport.hpp"
#include <array>
#include <cstdint>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>

namespace {
void check(bool condition, char const* message) {
    if (!condition) throw std::runtime_error(message);
}
constexpr std::uint64_t second = 1'000'000'000ULL;
constexpr std::uint64_t minute = 60ULL * second;

void donation_exact_epoch() {
    cryptosteer::DonationScheduler scheduler(49ULL * minute, 0, 200);
    check(scheduler.donation_allowance() == minute, "check failed: scheduler.donation_allowance() == minute");
    scheduler.add_donation(2ULL * minute);
    check(scheduler.donation_nanos() == minute, "check failed: scheduler.donation_nanos() == minute");
    check(scheduler.percentage() <= 2.0, "check failed: scheduler.percentage() <= 2.0");
}

void donation_short_sessions_and_restarts() {
    cryptosteer::DonationScheduler first(0, 0, 200);
    first.add_user(10ULL * minute);
    check(first.donation_allowance() < 13ULL * second, "check failed: first.donation_allowance() < 13ULL * second");
    first.add_donation(60ULL * second);
    check(first.percentage() <= 2.0, "check failed: first.percentage() <= 2.0");
    auto user = first.user_nanos();
    auto donation = first.donation_nanos();
    cryptosteer::DonationScheduler restarted(user, donation, 200);
    restarted.add_user(39ULL * minute);
    restarted.add_donation(2ULL * minute);
    check(restarted.donation_nanos() == minute, "check failed: restarted.donation_nanos() == minute");
    check(restarted.percentage() <= 2.0, "check failed: restarted.percentage() <= 2.0");
}

void donation_corruption_repair() {
    cryptosteer::DonationScheduler scheduler(49ULL * minute, 9ULL * minute, 200);
    check(scheduler.donation_nanos() == minute, "check failed: scheduler.donation_nanos() == minute");
    check(scheduler.percentage() <= 2.0, "check failed: scheduler.percentage() <= 2.0");
    cryptosteer::DonationScheduler empty(0, UINT64_MAX, 200);
    check(empty.donation_nanos() == 0, "check failed: empty.donation_nanos() == 0");
}

void donation_tier_reduction_preserves_history() {
    cryptosteer::DonationScheduler scheduler(49ULL * minute, minute, 200);
    check(scheduler.percentage() == 2.0, "initial history should report exactly two percent");
    check(scheduler.set_basis_points(100), "one-percent tier must be accepted");
    check(scheduler.donation_allowance() == 0, "lower tier must not donate while historical ratio exceeds one percent");
    check(scheduler.donation_nanos() == minute, "tier reduction must preserve actual donated time");

    cryptosteer::DonationScheduler restarted(scheduler.user_nanos(), scheduler.donation_nanos(), 100);
    check(restarted.donation_nanos() == minute, "restart at one percent must preserve two-percent-era history");
    check(restarted.donation_allowance() == 0, "restart must not reset the lower-tier waiting period");
    restarted.add_user(50ULL * minute);
    check(restarted.donation_allowance() == 0, "99 user minutes plus one donation minute is exactly one percent");
    restarted.add_user(minute);
    check(restarted.donation_allowance() > 0, "new one-percent allowance begins only after the lifetime ratio falls below one percent");
    check(!restarted.set_basis_points(150), "arbitrary server-provided donation tiers must be rejected");
}

void affinity_inventory() {
    std::array<int, 1024> cpus{};
    std::size_t count = cryptosteer_allowed_cpu_indices(cpus.data(), cpus.size());
    check(count >= 1U, "at least one CPU must be available to the current process");
    check(cryptosteer_online_cpu_count() == static_cast<int>(count), "online CPU count must reflect the process affinity mask");
    for (std::size_t index = 1; index < std::min(count, cpus.size()); ++index) {
        check(cpus[index] > cpus[index - 1U], "allowed CPU indices must be unique and ordered");
    }
}

void json_limits_and_round_trip() {
    auto value = cryptosteer::json::parse("{\"name\":\"CryptoSteer\",\"enabled\":true,\"items\":[1,2,3]}");
    check(value.string_or("name") == "CryptoSteer", "check failed: value.string_or(\"name\") == \"CryptoSteer\"");
    check(value.bool_or("enabled", false), "check failed: value.bool_or(\"enabled\", false)");
    check(cryptosteer::json::parse(cryptosteer::json::stringify(value)).is_object(), "check failed: cryptosteer::json::parse(cryptosteer::json::stringify(value)).is_object()");
    bool threw = false;
    try { (void)cryptosteer::json::parse("{\"unterminated\":"); } catch (std::runtime_error const&) { threw = true; }
    check(threw, "check failed: threw");
}

void hex_and_target() {
    std::array<std::uint8_t, 32> hash{};
    std::string encoded = cryptosteer::hex_encode(hash);
    check(encoded.size() == 64, "check failed: encoded.size() == 64");
    check(cryptosteer::hex_decode(encoded).size() == 32, "check failed: cryptosteer::hex_decode(encoded).size() == 32");
    check(cryptosteer::hash_meets_target(hash, "ffffffff"), "zero hash should satisfy the easiest compact target");

    constexpr std::uint32_t compact_target = 0x00068db8U;
    constexpr std::uint64_t expanded_target = std::numeric_limits<std::uint64_t>::max()
        / (std::numeric_limits<std::uint32_t>::max() / static_cast<std::uint64_t>(compact_target));
    auto store_le64 = [](std::uint8_t* destination, std::uint64_t value) {
        for (std::size_t index = 0; index < sizeof(value); ++index) {
            destination[index] = static_cast<std::uint8_t>((value >> (index * 8U)) & 0xffU);
        }
    };
    auto compiled_compact = cryptosteer::compile_hash_target("b88d0600");
    store_le64(hash.data() + 24U, expanded_target - 1U);
    check(cryptosteer::hash_meets_target(hash, "b88d0600"), "compact target expansion should accept the value below the expanded threshold");
    check(cryptosteer::hash_meets_target(hash, compiled_compact), "precompiled compact target should match the validated string target");
    store_le64(hash.data() + 24U, expanded_target);
    check(!cryptosteer::hash_meets_target(hash, "b88d0600"), "compact target comparison must remain strict");

    store_le64(hash.data() + 24U, 0x102030405060707FULL);
    check(cryptosteer::hash_meets_target(hash, "8070605040302010"), "eight-byte target should use the high little-endian hash word");
    store_le64(hash.data() + 24U, 0x1020304050607080ULL);
    check(!cryptosteer::hash_meets_target(hash, "8070605040302010"), "eight-byte target equality must not submit a share");

    bool zero_target_rejected = false;
    try { (void)cryptosteer::hash_meets_target(hash, "00000000"); }
    catch (std::invalid_argument const&) { zero_target_rejected = true; }
    check(zero_target_rejected, "zero target must be rejected");

    bool malformed_hex_rejected = false;
    try { (void)cryptosteer::hex_decode("abc"); }
    catch (std::invalid_argument const&) { malformed_hex_rejected = true; }
    check(malformed_hex_rejected, "odd-length hexadecimal input must be rejected");
}

void transport_poll_semantics() {
    check(cryptosteer::detail::poll_result_to_stream_status(0) == -1, "socket poll timeout must remain a non-fatal stream timeout");
    check(cryptosteer::detail::poll_result_to_stream_status(-1) == 0, "socket hangup/error must map to closed stream");
    check(cryptosteer::detail::poll_result_to_stream_status(1) == 1, "ready socket must proceed to I/O");
}
} // namespace

int main() {
    donation_exact_epoch();
    donation_short_sessions_and_restarts();
    donation_corruption_repair();
    donation_tier_reduction_preserves_history();
    affinity_inventory();
    json_limits_and_round_trip();
    hex_and_target();
    transport_poll_semantics();
    std::cout << "CryptoSteer host native tests: PASS\n";
    return 0;
}
