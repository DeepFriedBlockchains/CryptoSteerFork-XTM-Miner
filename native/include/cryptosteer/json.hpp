#pragma once
#include <cstdint>
#include <map>
#include <stdexcept>
#include <string>
#include <string_view>
#include <variant>
#include <vector>

namespace cryptosteer::json {
class Value {
public:
    using Object = std::map<std::string, Value, std::less<>>;
    using Array = std::vector<Value>;
    using Storage = std::variant<std::nullptr_t, bool, double, std::string, Array, Object>;

    Value() : data_(nullptr) {}
    explicit Value(std::nullptr_t) : data_(nullptr) {}
    explicit Value(bool value) : data_(value) {}
    explicit Value(double value) : data_(value) {}
    explicit Value(std::string value) : data_(std::move(value)) {}
    explicit Value(Array value) : data_(std::move(value)) {}
    explicit Value(Object value) : data_(std::move(value)) {}

    [[nodiscard]] bool is_null() const noexcept;
    [[nodiscard]] bool is_bool() const noexcept;
    [[nodiscard]] bool is_number() const noexcept;
    [[nodiscard]] bool is_string() const noexcept;
    [[nodiscard]] bool is_array() const noexcept;
    [[nodiscard]] bool is_object() const noexcept;
    [[nodiscard]] bool as_bool() const;
    [[nodiscard]] double as_number() const;
    [[nodiscard]] std::int64_t as_i64() const;
    [[nodiscard]] std::string const& as_string() const;
    [[nodiscard]] Array const& as_array() const;
    [[nodiscard]] Object const& as_object() const;
    [[nodiscard]] Value const* find(std::string_view key) const noexcept;
    [[nodiscard]] std::string string_or(std::string_view key, std::string fallback = {}) const;
    [[nodiscard]] std::int64_t i64_or(std::string_view key, std::int64_t fallback) const;
    [[nodiscard]] bool bool_or(std::string_view key, bool fallback) const;
private:
    Storage data_;
};

Value parse(std::string_view input);
std::string escape(std::string_view input);
std::string stringify(Value const& value);
} // namespace cryptosteer::json
