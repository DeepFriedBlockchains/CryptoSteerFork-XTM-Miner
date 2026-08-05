#include "cryptosteer/cpu_topology.hpp"
#include "cryptosteer/platform/affinity.h"
#include <algorithm>
#include <fstream>
#include <limits>
#include <unordered_set>
#include <hwloc.h>
#include <sstream>

namespace cryptosteer {
namespace {
long read_long(std::string const& path) {
    std::ifstream input(path); long value = -1; if (input) input >> value; return value;
}
}
CpuTopology::CpuTopology() {
    hwloc_topology_t topology = nullptr;
    if (hwloc_topology_init(&topology) == 0 && hwloc_topology_load(topology) == 0) topology_ = topology;
    else if (topology) hwloc_topology_destroy(topology);

    std::vector<int> allowed(static_cast<std::size_t>(std::max(1, cryptosteer_online_cpu_count())));
    std::size_t allowed_count = cryptosteer_allowed_cpu_indices(allowed.data(), allowed.size());
    if (allowed_count > allowed.size()) {
        allowed.resize(allowed_count);
        allowed_count = cryptosteer_allowed_cpu_indices(allowed.data(), allowed.size());
    }
    allowed.resize(std::min(allowed_count, allowed.size()));
    std::unordered_set<int> allowed_set(allowed.begin(), allowed.end());
    std::vector<int> ordered;

    if (topology_) {
        auto loaded = static_cast<hwloc_topology_t>(topology_);
        int pu_count = hwloc_get_nbobjs_by_type(loaded, HWLOC_OBJ_PU);
        for (int index = 0; index < pu_count; ++index) {
            hwloc_obj_t object = hwloc_get_obj_by_type(loaded, HWLOC_OBJ_PU, static_cast<unsigned>(index));
            if (!object || object->os_index == std::numeric_limits<unsigned>::max()) continue;
            int cpu = static_cast<int>(object->os_index);
            if (allowed_set.contains(cpu) && std::find(ordered.begin(), ordered.end(), cpu) == ordered.end()) ordered.push_back(cpu);
        }
    }
    for (int cpu : allowed) {
        if (std::find(ordered.begin(), ordered.end(), cpu) == ordered.end()) ordered.push_back(cpu);
    }
    if (ordered.empty()) ordered.push_back(0);

    for (int cpu : ordered) {
        long capacity = read_long("/sys/devices/system/cpu/cpu" + std::to_string(cpu) + "/cpu_capacity");
        long frequency = read_long("/sys/devices/system/cpu/cpu" + std::to_string(cpu) + "/cpufreq/cpuinfo_max_freq");
        cpus_.push_back({cpu, capacity > 0 ? static_cast<int>(capacity) : 0, frequency});
    }
}
CpuTopology::~CpuTopology() { if (topology_) hwloc_topology_destroy(static_cast<hwloc_topology_t>(topology_)); }
std::vector<CpuInfo> CpuTopology::cpus() const { return cpus_; }
std::vector<int> CpuTopology::recommended_order() const {
    auto values = cpus_;
    std::stable_sort(values.begin(), values.end(), [](CpuInfo const& a, CpuInfo const& b) {
        if (a.capacity != b.capacity) return a.capacity > b.capacity;
        if (a.max_frequency_khz != b.max_frequency_khz) return a.max_frequency_khz > b.max_frequency_khz;
        return a.logical_index < b.logical_index;
    });
    std::vector<int> out; out.reserve(values.size()); for (auto const& cpu : values) out.push_back(cpu.logical_index); return out;
}
std::string CpuTopology::description() const {
    std::ostringstream out; out << cpus_.size() << " online CPUs";
    if (topology_) out << ", hwloc " << hwloc_get_api_version();
    return out.str();
}
} // namespace cryptosteer
