#include "cryptosteer/platform/secure_zero.h"
void cryptosteer_secure_zero(void* pointer, size_t size) {
    volatile unsigned char* p = (volatile unsigned char*)pointer;
    while (size-- > 0U) *p++ = 0U;
}
