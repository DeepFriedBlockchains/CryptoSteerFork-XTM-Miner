#pragma once
#include <array>
#include <cstdint>
#include <span>
#include <string>
#include <string_view>
#include <vector>
namespace cryptosteer {
struct HashTarget {
    enum class Kind { Word64, Bytes256 };
    Kind kind = Kind::Word64;
    std::uint64_t word = 0;
    std::array<std::uint8_t, 32> bytes{};
};
std::vector<std::uint8_t> hex_decode(std::string_view text);
std::string hex_encode(std::span<std::uint8_t const> bytes);
HashTarget compile_hash_target(std::string_view target_hex);
bool hash_meets_target(std::span<std::uint8_t const, 32> hash, HashTarget const& target) noexcept;
bool hash_meets_target(std::span<std::uint8_t const, 32> hash, std::string_view target_hex);
} // namespace cryptosteer
