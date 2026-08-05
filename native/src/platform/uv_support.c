#include "cryptosteer/platform/uv_support.h"
#include <arpa/inet.h>
#include <stdio.h>
#include <string.h>
#include <uv.h>

struct resolve_context { struct cryptosteer_resolved_address* output; size_t capacity; size_t count; int status; char* error; size_t error_capacity; };
static void resolved(uv_getaddrinfo_t* request, int status, struct addrinfo* result) {
    struct resolve_context* context = (struct resolve_context*)request->data;
    context->status = status;
    if (status < 0) {
        if (context->error && context->error_capacity) snprintf(context->error, context->error_capacity, "%s", uv_strerror(status));
        return;
    }
    for (struct addrinfo* item = result; item && context->count < context->capacity; item = item->ai_next) {
        struct cryptosteer_resolved_address* out = &context->output[context->count];
        memset(out, 0, sizeof(*out));
        if (item->ai_family == AF_INET) {
            out->family = AF_INET;
            struct sockaddr_in* address = (struct sockaddr_in*)item->ai_addr;
            memcpy(out->bytes, &address->sin_addr, 4U);
            uv_ip4_name(address, out->text, sizeof(out->text));
            ++context->count;
        } else if (item->ai_family == AF_INET6) {
            out->family = AF_INET6;
            struct sockaddr_in6* address = (struct sockaddr_in6*)item->ai_addr;
            memcpy(out->bytes, &address->sin6_addr, 16U);
            uv_ip6_name(address, out->text, sizeof(out->text));
            ++context->count;
        }
    }
    uv_freeaddrinfo(result);
}
int cryptosteer_uv_resolve(const char* host, struct cryptosteer_resolved_address* output, size_t capacity, size_t* count, char* error, size_t error_capacity) {
    if (!host || !output || !count || capacity == 0U) return UV_EINVAL;
    uv_loop_t loop; int rc = uv_loop_init(&loop); if (rc < 0) return rc;
    struct addrinfo hints; memset(&hints, 0, sizeof(hints)); hints.ai_socktype = SOCK_STREAM; hints.ai_family = AF_UNSPEC;
    struct resolve_context context = { output, capacity, 0U, UV_EAI_AGAIN, error, error_capacity };
    uv_getaddrinfo_t request; memset(&request, 0, sizeof(request)); request.data = &context;
    rc = uv_getaddrinfo(&loop, &request, resolved, host, NULL, &hints);
    if (rc == 0) uv_run(&loop, UV_RUN_DEFAULT); else context.status = rc;
    uv_loop_close(&loop); *count = context.count;
    return context.status;
}
struct sleep_context { uv_timer_t timer; };
static void timer_done(uv_timer_t* timer) { uv_timer_stop(timer); uv_close((uv_handle_t*)timer, NULL); }
int cryptosteer_uv_sleep(uint64_t milliseconds) {
    uv_loop_t loop; int rc = uv_loop_init(&loop); if (rc < 0) return rc;
    struct sleep_context context; memset(&context, 0, sizeof(context));
    rc = uv_timer_init(&loop, &context.timer);
    if (rc == 0) rc = uv_timer_start(&context.timer, timer_done, milliseconds, 0U);
    if (rc == 0) uv_run(&loop, UV_RUN_DEFAULT);
    uv_loop_close(&loop); return rc;
}
