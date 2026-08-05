#pragma once
#include <stddef.h>
#ifdef __cplusplus
extern "C" {
#endif
int cryptosteer_set_current_thread_affinity(int cpu_index);
int cryptosteer_online_cpu_count(void);
size_t cryptosteer_allowed_cpu_indices(int* output, size_t capacity);
#ifdef __cplusplus
}
#endif
