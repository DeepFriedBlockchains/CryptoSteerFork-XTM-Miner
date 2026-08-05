#pragma once
#include <string>
namespace cryptosteer {
std::string run_benchmark(std::string const& config_json);
std::string benchmark_progress();
void cancel_benchmark() noexcept;
std::string run_auto_tune(std::string const& config_json);
void cancel_auto_tune() noexcept;
std::string run_native_self_tests();
std::string native_diagnostics();
} // namespace cryptosteer
