#pragma once
#include "cryptosteer/model.hpp"
#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>

struct ssl_ctx_st;
struct ssl_st;

namespace cryptosteer {
namespace detail {
// poll(2) returns 0 for a timeout and a negative value here for a peer/error
// condition. Stream helpers use -1 for timeout and 0 for a closed socket.
constexpr int poll_result_to_stream_status(int ready) noexcept {
    return ready == 0 ? -1 : (ready < 0 ? 0 : 1);
}
} // namespace detail

class Transport {
public:
    Transport() = default;
    ~Transport();
    Transport(Transport const&) = delete;
    Transport& operator=(Transport const&) = delete;

    void connect(Endpoint const& endpoint, int timeout_seconds, bool allow_insecure_tls);
    void close() noexcept;
    void write_line(std::string_view line, int timeout_millis = 15000);
    [[nodiscard]] bool read_line(std::string& line, int timeout_millis);
    [[nodiscard]] bool connected() const noexcept { return fd_ >= 0; }
    [[nodiscard]] bool tls() const noexcept { return ssl_ != nullptr; }
    [[nodiscard]] std::string peer() const { return peer_; }

private:
    [[nodiscard]] int wait_socket(short events, int timeout_millis) const;
    [[nodiscard]] int raw_read(void* data, std::size_t size, int timeout_millis);
    [[nodiscard]] int raw_write(void const* data, std::size_t size, int timeout_millis);
    void configure_tls(Endpoint const& endpoint, bool allow_insecure_tls);

    int fd_ = -1;
    ssl_ctx_st* context_ = nullptr;
    ssl_st* ssl_ = nullptr;
    std::string peer_;
    std::string input_buffer_;
};
} // namespace cryptosteer
