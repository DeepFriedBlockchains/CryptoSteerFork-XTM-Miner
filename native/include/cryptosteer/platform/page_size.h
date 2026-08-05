#pragma once
#include <stddef.h>
#ifdef __cplusplus
extern "C" {
#endif
size_t cryptosteer_runtime_page_size(void);
int cryptosteer_is_supported_page_size(size_t value);
#ifdef __cplusplus
}
#endif
