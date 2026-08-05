#pragma once
#include <string>
#include <vector>
namespace cryptosteer {
struct CpuInfo { int logical_index; int capacity; long max_frequency_khz; };
class CpuTopology {
public:
    CpuTopology();
    ~CpuTopology();
    CpuTopology(CpuTopology const&) = delete;
    CpuTopology& operator=(CpuTopology const&) = delete;
    [[nodiscard]] std::vector<CpuInfo> cpus() const;
    [[nodiscard]] std::vector<int> recommended_order() const;
    [[nodiscard]] std::string description() const;
private:
    void* topology_ = nullptr;
    std::vector<CpuInfo> cpus_;
};
} // namespace cryptosteer
