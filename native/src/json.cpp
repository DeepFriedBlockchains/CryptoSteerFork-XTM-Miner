#include "cryptosteer/json.hpp"
#include <charconv>
#include <cstdlib>
#include <cmath>
#include <iomanip>
#include <limits>
#include <sstream>

namespace cryptosteer::json {
namespace {
class Parser {
public:
    explicit Parser(std::string_view text) : text_(text) {}
    Value run() {
        skip_ws();
        Value value = parse_value(0);
        skip_ws();
        if (pos_ != text_.size()) fail("unexpected trailing data");
        return value;
    }
private:
    static constexpr std::size_t kMaxDepth = 64;
    static constexpr std::size_t kMaxString = 1024 * 1024;
    static constexpr std::size_t kMaxMembers = 65536;
    std::string_view text_;
    std::size_t pos_ = 0;

    [[noreturn]] void fail(char const* message) const {
        throw std::runtime_error(std::string("JSON parse error at byte ") + std::to_string(pos_) + ": " + message);
    }
    void skip_ws() {
        while (pos_ < text_.size()) {
            char c = text_[pos_];
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') ++pos_; else break;
        }
    }
    char take() {
        if (pos_ >= text_.size()) fail("unexpected end of input");
        return text_[pos_++];
    }
    bool consume(char expected) {
        if (pos_ < text_.size() && text_[pos_] == expected) { ++pos_; return true; }
        return false;
    }
    void expect_literal(std::string_view literal) {
        if (text_.substr(pos_, literal.size()) != literal) fail("invalid literal");
        pos_ += literal.size();
    }
    Value parse_value(std::size_t depth) {
        if (depth > kMaxDepth) fail("maximum nesting depth exceeded");
        skip_ws();
        if (pos_ >= text_.size()) fail("missing value");
        switch (text_[pos_]) {
            case 'n': expect_literal("null"); return Value(nullptr);
            case 't': expect_literal("true"); return Value(true);
            case 'f': expect_literal("false"); return Value(false);
            case '"': return Value(parse_string());
            case '[': return Value(parse_array(depth + 1));
            case '{': return Value(parse_object(depth + 1));
            default: return Value(parse_number());
        }
    }
    static void append_utf8(std::string& out, std::uint32_t cp) {
        if (cp <= 0x7fU) out.push_back(static_cast<char>(cp));
        else if (cp <= 0x7ffU) {
            out.push_back(static_cast<char>(0xc0U | (cp >> 6U)));
            out.push_back(static_cast<char>(0x80U | (cp & 0x3fU)));
        } else if (cp <= 0xffffU) {
            out.push_back(static_cast<char>(0xe0U | (cp >> 12U)));
            out.push_back(static_cast<char>(0x80U | ((cp >> 6U) & 0x3fU)));
            out.push_back(static_cast<char>(0x80U | (cp & 0x3fU)));
        } else if (cp <= 0x10ffffU) {
            out.push_back(static_cast<char>(0xf0U | (cp >> 18U)));
            out.push_back(static_cast<char>(0x80U | ((cp >> 12U) & 0x3fU)));
            out.push_back(static_cast<char>(0x80U | ((cp >> 6U) & 0x3fU)));
            out.push_back(static_cast<char>(0x80U | (cp & 0x3fU)));
        } else throw std::runtime_error("invalid Unicode code point");
    }
    std::uint32_t parse_hex4() {
        std::uint32_t value = 0;
        for (int i = 0; i < 4; ++i) {
            char c = take();
            value <<= 4U;
            if (c >= '0' && c <= '9') value |= static_cast<std::uint32_t>(c - '0');
            else if (c >= 'a' && c <= 'f') value |= static_cast<std::uint32_t>(c - 'a' + 10);
            else if (c >= 'A' && c <= 'F') value |= static_cast<std::uint32_t>(c - 'A' + 10);
            else fail("invalid Unicode escape");
        }
        return value;
    }
    std::string parse_string() {
        if (take() != '"') fail("expected string");
        std::string out;
        while (true) {
            char c = take();
            if (c == '"') break;
            if (static_cast<unsigned char>(c) < 0x20U) fail("control character in string");
            if (c != '\\') out.push_back(c);
            else {
                char e = take();
                switch (e) {
                    case '"': out.push_back('"'); break; case '\\': out.push_back('\\'); break;
                    case '/': out.push_back('/'); break; case 'b': out.push_back('\b'); break;
                    case 'f': out.push_back('\f'); break; case 'n': out.push_back('\n'); break;
                    case 'r': out.push_back('\r'); break; case 't': out.push_back('\t'); break;
                    case 'u': {
                        std::uint32_t cp = parse_hex4();
                        if (cp >= 0xd800U && cp <= 0xdbffU) {
                            if (take() != '\\' || take() != 'u') fail("missing low surrogate");
                            std::uint32_t low = parse_hex4();
                            if (low < 0xdc00U || low > 0xdfffU) fail("invalid low surrogate");
                            cp = 0x10000U + ((cp - 0xd800U) << 10U) + (low - 0xdc00U);
                        }
                        append_utf8(out, cp);
                        break;
                    }
                    default: fail("invalid escape sequence");
                }
            }
            if (out.size() > kMaxString) fail("string is too large");
        }
        return out;
    }
    double parse_number() {
        std::size_t start = pos_;
        consume('-');
        if (consume('0')) { }
        else {
            if (pos_ >= text_.size() || text_[pos_] < '1' || text_[pos_] > '9') fail("invalid number");
            while (pos_ < text_.size() && text_[pos_] >= '0' && text_[pos_] <= '9') ++pos_;
        }
        if (consume('.')) {
            if (pos_ >= text_.size() || text_[pos_] < '0' || text_[pos_] > '9') fail("invalid fraction");
            while (pos_ < text_.size() && text_[pos_] >= '0' && text_[pos_] <= '9') ++pos_;
        }
        if (pos_ < text_.size() && (text_[pos_] == 'e' || text_[pos_] == 'E')) {
            ++pos_; if (pos_ < text_.size() && (text_[pos_] == '+' || text_[pos_] == '-')) ++pos_;
            if (pos_ >= text_.size() || text_[pos_] < '0' || text_[pos_] > '9') fail("invalid exponent");
            while (pos_ < text_.size() && text_[pos_] >= '0' && text_[pos_] <= '9') ++pos_;
        }
        double value = 0;
        auto text = text_.substr(start, pos_ - start);
        std::string copy(text);
        char* end = nullptr;
        value = std::strtod(copy.c_str(), &end);
        if (end != copy.c_str() + copy.size() || !std::isfinite(value)) fail("number out of range");
        return value;
    }
    Value::Array parse_array(std::size_t depth) {
        take(); skip_ws();
        Value::Array out;
        if (consume(']')) return out;
        while (true) {
            if (out.size() >= kMaxMembers) fail("array has too many elements");
            out.emplace_back(parse_value(depth)); skip_ws();
            if (consume(']')) return out;
            if (!consume(',')) fail("expected comma in array");
            skip_ws();
        }
    }
    Value::Object parse_object(std::size_t depth) {
        take(); skip_ws();
        Value::Object out;
        if (consume('}')) return out;
        while (true) {
            if (out.size() >= kMaxMembers) fail("object has too many members");
            if (pos_ >= text_.size() || text_[pos_] != '"') fail("expected object key");
            std::string key = parse_string(); skip_ws();
            if (!consume(':')) fail("expected colon after object key");
            skip_ws();
            out.insert_or_assign(std::move(key), parse_value(depth)); skip_ws();
            if (consume('}')) return out;
            if (!consume(',')) fail("expected comma in object");
            skip_ws();
        }
    }
};

void stringify_into(std::string& out, Value const& value) {
    if (value.is_null()) out += "null";
    else if (value.is_bool()) out += value.as_bool() ? "true" : "false";
    else if (value.is_number()) {
        std::ostringstream stream; stream << std::setprecision(17) << value.as_number(); out += stream.str();
    } else if (value.is_string()) { out.push_back('"'); out += escape(value.as_string()); out.push_back('"'); }
    else if (value.is_array()) {
        out.push_back('['); bool first = true;
        for (auto const& item : value.as_array()) { if (!first) out.push_back(','); first = false; stringify_into(out, item); }
        out.push_back(']');
    } else {
        out.push_back('{'); bool first = true;
        for (auto const& [key, item] : value.as_object()) {
            if (!first) out.push_back(',');
            first = false;
            out.push_back('"'); out += escape(key); out += "\":";
            stringify_into(out, item);
        }
        out.push_back('}');
    }
}
} // namespace

bool Value::is_null() const noexcept { return std::holds_alternative<std::nullptr_t>(data_); }
bool Value::is_bool() const noexcept { return std::holds_alternative<bool>(data_); }
bool Value::is_number() const noexcept { return std::holds_alternative<double>(data_); }
bool Value::is_string() const noexcept { return std::holds_alternative<std::string>(data_); }
bool Value::is_array() const noexcept { return std::holds_alternative<Array>(data_); }
bool Value::is_object() const noexcept { return std::holds_alternative<Object>(data_); }
bool Value::as_bool() const { return std::get<bool>(data_); }
double Value::as_number() const { return std::get<double>(data_); }
std::int64_t Value::as_i64() const {
    double v = as_number();
    if (v < -9223372036854775808.0 || v >= 9223372036854775808.0 || std::floor(v) != v)
        throw std::runtime_error("JSON number is not a signed 64-bit integer");
    return static_cast<std::int64_t>(v);
}
std::string const& Value::as_string() const { return std::get<std::string>(data_); }
Value::Array const& Value::as_array() const { return std::get<Array>(data_); }
Value::Object const& Value::as_object() const { return std::get<Object>(data_); }
Value const* Value::find(std::string_view key) const noexcept {
    if (!is_object()) return nullptr;
    auto const& object = std::get<Object>(data_);
    auto it = object.find(key);
    return it == object.end() ? nullptr : &it->second;
}
std::string Value::string_or(std::string_view key, std::string fallback) const {
    auto value = find(key); return value && value->is_string() ? value->as_string() : std::move(fallback);
}
std::int64_t Value::i64_or(std::string_view key, std::int64_t fallback) const {
    auto value = find(key); return value && value->is_number() ? value->as_i64() : fallback;
}
bool Value::bool_or(std::string_view key, bool fallback) const {
    auto value = find(key); return value && value->is_bool() ? value->as_bool() : fallback;
}
Value parse(std::string_view input) { return Parser(input).run(); }
std::string escape(std::string_view input) {
    std::string out; out.reserve(input.size() + 8);
    static constexpr char hex[] = "0123456789abcdef";
    for (char raw : input) {
        auto c = static_cast<unsigned char>(raw);
        switch (c) {
            case '"': out += "\\\""; break; case '\\': out += "\\\\"; break;
            case '\b': out += "\\b"; break; case '\f': out += "\\f"; break;
            case '\n': out += "\\n"; break; case '\r': out += "\\r"; break; case '\t': out += "\\t"; break;
            default:
                if (c < 0x20U) { out += "\\u00"; out.push_back(hex[c >> 4U]); out.push_back(hex[c & 0x0fU]); }
                else out.push_back(static_cast<char>(c));
        }
    }
    return out;
}
std::string stringify(Value const& value) { std::string out; stringify_into(out, value); return out; }
} // namespace cryptosteer::json
