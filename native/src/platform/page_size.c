#include "cryptosteer/platform/page_size.h"
#include <unistd.h>
size_t cryptosteer_runtime_page_size(void) {
    long value = sysconf(_SC_PAGESIZE);
    return value > 0 ? (size_t)value : (size_t)4096;
}
int cryptosteer_is_supported_page_size(size_t value) { return value == 4096U || value == 16384U; }
