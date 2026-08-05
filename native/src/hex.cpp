#include "cryptosteer/hex.hpp"
#include <algorithm>
#include <array>
#include <cstring>
#include <limits>
#include <stdexcept>

namespace cryptosteer {
namespace {
int nibble(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}
template<class T> T load_le(std::uint8_t const* p) {
    T value = 0;
    for (std::size_t i = 0; i < sizeof(T); ++i) value |= static_cast<T>(p[i]) << (8U * i);
    return value;
}
} // namespace
std::vector<std::uint8_t> hex_decode(std::string_view text) {
    if ((text.size() & 1U) != 0U) throw std::invalid_argument("hex input has odd length");
    if (text.size() > 2U * 1024U * 1024U) throw std::invalid_argument("hex input is too large");
    std::vector<std::uint8_t> out(text.size() / 2U);
    for (std::size_t i = 0; i < out.size(); ++i) {
        int hi = nibble(text[i * 2U]); int lo = nibble(text[i * 2U + 1U]);
        if (hi < 0 || lo < 0) throw std::invalid_argument("hex input contains a non-hexadecimal character");
        out[i] = static_cast<std::uint8_t>((hi << 4) | lo);
    }
    return out;
}
std::string hex_encode(std::span<std::uint8_t const> bytes) {
    static constexpr char digits[] = "0123456789abcdef";
    std::string out(bytes.size() * 2U, '0');
    for (std::size_t i = 0; i < bytes.size(); ++i) {
        out[i * 2U] = digits[bytes[i] >> 4U]; out[i * 2U + 1U] = digits[bytes[i] & 0x0fU];
    }
    return out;
}
HashTarget compile_hash_target(std::string_view target_hex) {
    auto target = hex_decode(target_hex);
    HashTarget compiled;
    if (target.size() == 4U) {
        std::uint32_t compact = load_le<std::uint32_t>(target.data());
        if (compact == 0U) throw std::invalid_argument("pool target cannot be zero");
        std::uint64_t divisor = std::numeric_limits<std::uint32_t>::max() / static_cast<std::uint64_t>(compact);
        if (divisor == 0U) throw std::invalid_argument("pool target expansion failed");
        compiled.kind = HashTarget::Kind::Word64;
        compiled.word = std::numeric_limits<std::uint64_t>::max() / divisor;
        return compiled;
    }
    if (target.size() == 8U) {
        std::uint64_t expanded = load_le<std::uint64_t>(target.data());
        if (expanded == 0U) throw std::invalid_argument("pool target cannot be zero");
        compiled.kind = HashTarget::Kind::Word64;
        compiled.word = expanded;
        return compiled;
    }
    if (target.size() == 32U) {
        bool nonzero = false;
        for (std::uint8_t byte : target) nonzero = nonzero || byte != 0U;
        if (!nonzero) throw std::invalid_argument("pool target cannot be zero");
        compiled.kind = HashTarget::Kind::Bytes256;
        std::copy(target.begin(), target.end(), compiled.bytes.begin());
        return compiled;
    }
    throw std::invalid_argument("pool target must contain 4, 8, or 32 bytes");
}

bool hash_meets_target(std::span<std::uint8_t const, 32> hash, HashTarget const& target) noexcept {
    if (target.kind == HashTarget::Kind::Word64) {
        return load_le<std::uint64_t>(hash.data() + 24U) < target.word;
    }
    for (int i = 31; i >= 0; --i) {
        auto index = static_cast<std::size_t>(i);
        if (hash[index] < target.bytes[index]) return true;
        if (hash[index] > target.bytes[index]) return false;
    }
    return false;
}

bool hash_meets_target(std::span<std::uint8_t const, 32> hash, std::string_view target_hex) {
    return hash_meets_target(hash, compile_hash_target(target_hex));
}
} // namespace cryptosteer
