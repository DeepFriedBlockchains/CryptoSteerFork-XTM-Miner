#pragma once
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
struct cryptosteer_resolved_address { int family; uint8_t bytes[16]; char text[64]; };
int cryptosteer_uv_resolve(const char* host, struct cryptosteer_resolved_address* output, size_t capacity, size_t* count, char* error, size_t error_capacity);
int cryptosteer_uv_sleep(uint64_t milliseconds);
#ifdef __cplusplus
}
#endif
