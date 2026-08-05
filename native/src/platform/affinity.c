#define _GNU_SOURCE
#include "cryptosteer/platform/affinity.h"
#include <errno.h>
#include <sched.h>
#include <unistd.h>

/*
 * glibc implements CPU_ISSET/CPU_SET with internal unsigned array indexes,
 * while their public API accepts a validated signed CPU index. GCC reports a
 * sign-conversion warning inside those system macros under our strict warning
 * policy. Keep the suppression limited to these two wrappers; Clang/Android
 * does not require it.
 */
static int cryptosteer_cpu_is_set(int cpu, const cpu_set_t* set) {
#if defined(__GNUC__) && !defined(__clang__)
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wsign-conversion"
#endif
    int result = CPU_ISSET(cpu, set);
#if defined(__GNUC__) && !defined(__clang__)
#pragma GCC diagnostic pop
#endif
    return result;
}

static void cryptosteer_cpu_set(int cpu, cpu_set_t* set) {
#if defined(__GNUC__) && !defined(__clang__)
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wsign-conversion"
#endif
    CPU_SET(cpu, set);
#if defined(__GNUC__) && !defined(__clang__)
#pragma GCC diagnostic pop
#endif
}

size_t cryptosteer_allowed_cpu_indices(int* output, size_t capacity) {
    cpu_set_t set;
    CPU_ZERO(&set);
    if (sched_getaffinity(0, sizeof(set), &set) == 0) {
        size_t count = 0U;
        for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu) {
            if (!cryptosteer_cpu_is_set(cpu, &set)) continue;
            if (output && count < capacity) output[count] = cpu;
            ++count;
        }
        if (count > 0U) return count;
    }
    long online = sysconf(_SC_NPROCESSORS_ONLN);
    size_t count = online > 0 && online <= CPU_SETSIZE ? (size_t)online : 1U;
    if (output) {
        size_t written = count < capacity ? count : capacity;
        for (size_t index = 0; index < written; ++index) output[index] = (int)index;
    }
    return count;
}

int cryptosteer_set_current_thread_affinity(int cpu_index) {
    if (cpu_index < 0 || cpu_index >= CPU_SETSIZE) return EINVAL;
    cpu_set_t set;
    CPU_ZERO(&set);
    cryptosteer_cpu_set(cpu_index, &set);
    return sched_setaffinity(0, sizeof(set), &set) == 0 ? 0 : errno;
}

int cryptosteer_online_cpu_count(void) {
    size_t count = cryptosteer_allowed_cpu_indices(NULL, 0U);
    return count > 0U && count <= 1024U ? (int)count : 1;
}
