#include "cryptosteer/transport.hpp"
#include "cryptosteer/hex.hpp"
#include "cryptosteer/platform/uv_support.h"
#include <algorithm>
#include <arpa/inet.h>
#include <array>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <fcntl.h>
#include <openssl/err.h>
#include <openssl/ssl.h>
#include <openssl/x509v3.h>
#include <netinet/in.h>
#include <poll.h>
#include <stdexcept>
#include <string>
#include <sys/socket.h>
#include <unistd.h>

namespace cryptosteer {
namespace {
std::string ssl_error(std::string_view prefix) {
    unsigned long code = ERR_get_error();
    std::array<char, 256> buffer{};
    if (code != 0UL) ERR_error_string_n(code, buffer.data(), buffer.size());
    return std::string(prefix) + (code == 0UL ? "" : ": ") + (code == 0UL ? "unknown TLS error" : buffer.data());
}
std::string normalize_fingerprint(std::string value) {
    value.erase(std::remove_if(value.begin(), value.end(), [](char c) { return c == ':' || c == ' ' || c == '\t'; }), value.end());
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return value;
}
void set_socket_timeout(int fd, int timeout_seconds) {
    timeval timeout{timeout_seconds, 0};
    (void)setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    (void)setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
}

bool is_ip_literal(std::string const& value) noexcept {
    in_addr ipv4{};
    in6_addr ipv6{};
    return inet_pton(AF_INET, value.c_str(), &ipv4) == 1 || inet_pton(AF_INET6, value.c_str(), &ipv6) == 1;
}
} // namespace

Transport::~Transport() { close(); }

int Transport::wait_socket(short events, int timeout_millis) const {
    pollfd descriptor{fd_, events, 0};
    int rc;
    do { rc = poll(&descriptor, 1, timeout_millis); } while (rc < 0 && errno == EINTR);
    if (rc == 0) return 0;
    if (rc < 0) throw std::runtime_error(std::string("socket poll failed: ") + std::strerror(errno));
    if ((descriptor.revents & (POLLERR | POLLHUP | POLLNVAL)) != 0) return -1;
    return 1;
}

void Transport::connect(Endpoint const& endpoint, int timeout_seconds, bool allow_insecure_tls) {
    close();
    if (endpoint.host.empty() || endpoint.host.size() > 253U || endpoint.port == 0U) throw std::invalid_argument("invalid pool endpoint");
    std::array<cryptosteer_resolved_address, 16> addresses{};
    std::size_t count = 0;
    std::array<char, 256> resolve_error{};
    int resolve_rc = cryptosteer_uv_resolve(endpoint.host.c_str(), addresses.data(), addresses.size(), &count, resolve_error.data(), resolve_error.size());
    if (resolve_rc < 0 || count == 0U) throw std::runtime_error(std::string("DNS lookup failed: ") + resolve_error.data());

    std::string last_error = "connection failed";
    for (std::size_t index = 0; index < count; ++index) {
        auto const& address = addresses[index];
        int candidate = socket(address.family, SOCK_STREAM | SOCK_CLOEXEC, IPPROTO_TCP);
        if (candidate < 0) { last_error = std::strerror(errno); continue; }
        int flags = fcntl(candidate, F_GETFL, 0);
        if (flags >= 0) (void)fcntl(candidate, F_SETFL, flags | O_NONBLOCK);
        int rc = -1;
        if (address.family == AF_INET) {
            sockaddr_in target{}; target.sin_family = AF_INET; target.sin_port = htons(endpoint.port);
            std::memcpy(&target.sin_addr, address.bytes, 4U);
            rc = ::connect(candidate, reinterpret_cast<sockaddr*>(&target), sizeof(target));
        } else if (address.family == AF_INET6) {
            sockaddr_in6 target{}; target.sin6_family = AF_INET6; target.sin6_port = htons(endpoint.port);
            std::memcpy(&target.sin6_addr, address.bytes, 16U);
            rc = ::connect(candidate, reinterpret_cast<sockaddr*>(&target), sizeof(target));
        }
        if (rc < 0 && errno == EINPROGRESS) {
            pollfd descriptor{candidate, POLLOUT, 0};
            do { rc = poll(&descriptor, 1, std::max(1, timeout_seconds) * 1000); } while (rc < 0 && errno == EINTR);
            if (rc > 0) {
                int socket_error = 0; socklen_t length = sizeof(socket_error);
                if (getsockopt(candidate, SOL_SOCKET, SO_ERROR, &socket_error, &length) == 0 && socket_error == 0) rc = 0;
                else { errno = socket_error == 0 ? ECONNREFUSED : socket_error; rc = -1; }
            } else { errno = rc == 0 ? ETIMEDOUT : errno; rc = -1; }
        }
        if (rc == 0) {
            if (flags >= 0) (void)fcntl(candidate, F_SETFL, flags & ~O_NONBLOCK);
            fd_ = candidate;
            peer_ = endpoint.host + ":" + std::to_string(endpoint.port);
            set_socket_timeout(fd_, std::max(1, timeout_seconds));
            if (endpoint.tls) configure_tls(endpoint, allow_insecure_tls);
            return;
        }
        last_error = std::strerror(errno);
        ::close(candidate);
    }
    throw std::runtime_error("Unable to connect to " + endpoint.host + ": " + last_error);
}

void Transport::configure_tls(Endpoint const& endpoint, bool allow_insecure_tls) {
    context_ = SSL_CTX_new(TLS_client_method());
    if (!context_) throw std::runtime_error(ssl_error("SSL_CTX_new failed"));
    SSL_CTX_set_min_proto_version(context_, TLS1_2_VERSION);
    SSL_CTX_set_options(context_, SSL_OP_NO_COMPRESSION);
    if (allow_insecure_tls) {
        SSL_CTX_set_verify(context_, SSL_VERIFY_NONE, nullptr);
    } else {
        SSL_CTX_set_verify(context_, SSL_VERIFY_PEER, nullptr);
        bool loaded = SSL_CTX_set_default_verify_paths(context_) == 1;
        static constexpr char const* ca_directories[] = {
            "/apex/com.android.conscrypt/cacerts",
            "/system/etc/security/cacerts",
            "/product/etc/security/cacerts"
        };
        for (char const* directory : ca_directories) loaded = SSL_CTX_load_verify_locations(context_, nullptr, directory) == 1 || loaded;
        if (!loaded) throw std::runtime_error("No Android system CA store could be loaded for TLS verification");
    }
    ssl_ = SSL_new(context_);
    if (!ssl_) throw std::runtime_error(ssl_error("SSL_new failed"));
    std::string server_name = endpoint.server_name.empty() ? endpoint.host : endpoint.server_name;
    bool verify_as_ip = is_ip_literal(server_name);
    if (!verify_as_ip && SSL_set_tlsext_host_name(ssl_, server_name.c_str()) != 1) {
        throw std::runtime_error(ssl_error("Unable to set TLS SNI"));
    }
    if (!allow_insecure_tls) {
        int verify_configured = verify_as_ip
            ? X509_VERIFY_PARAM_set1_ip_asc(SSL_get0_param(ssl_), server_name.c_str())
            : SSL_set1_host(ssl_, server_name.c_str());
        if (verify_configured != 1) throw std::runtime_error(ssl_error("Unable to configure TLS peer-name verification"));
    }
    if (SSL_set_fd(ssl_, fd_) != 1) throw std::runtime_error(ssl_error("SSL_set_fd failed"));
    if (SSL_connect(ssl_) != 1) throw std::runtime_error(ssl_error("TLS handshake failed"));
    if (!allow_insecure_tls && SSL_get_verify_result(ssl_) != X509_V_OK) throw std::runtime_error("TLS certificate validation failed");

    if (!endpoint.pinned_sha256.empty()) {
        X509* certificate = SSL_get1_peer_certificate(ssl_);
        if (!certificate) throw std::runtime_error("TLS peer did not provide a certificate");
        std::array<unsigned char, EVP_MAX_MD_SIZE> digest{}; unsigned int digest_size = 0;
        int ok = X509_digest(certificate, EVP_sha256(), digest.data(), &digest_size);
        X509_free(certificate);
        if (ok != 1 || digest_size != 32U) throw std::runtime_error("Unable to calculate peer certificate fingerprint");
        std::string actual = hex_encode(std::span<std::uint8_t const>(reinterpret_cast<std::uint8_t const*>(digest.data()), digest_size));
        if (actual != normalize_fingerprint(endpoint.pinned_sha256)) throw std::runtime_error("TLS certificate fingerprint does not match the configured pin");
    }
}

int Transport::raw_read(void* data, std::size_t size, int timeout_millis) {
    int ready = wait_socket(POLLIN, timeout_millis);
    int stream_status = detail::poll_result_to_stream_status(ready);
    if (stream_status <= 0) return stream_status;
    if (!ssl_) {
        ssize_t count;
        do { count = ::recv(fd_, data, size, 0); } while (count < 0 && errno == EINTR);
        if (count < 0) throw std::runtime_error(std::string("socket read failed: ") + std::strerror(errno));
        return static_cast<int>(count);
    }
    int count = SSL_read(ssl_, data, static_cast<int>(std::min<std::size_t>(size, static_cast<std::size_t>(INT_MAX))));
    if (count > 0) return count;
    int error = SSL_get_error(ssl_, count);
    if (error == SSL_ERROR_ZERO_RETURN) return 0;
    if (error == SSL_ERROR_WANT_READ || error == SSL_ERROR_WANT_WRITE) return -2;
    throw std::runtime_error(ssl_error("TLS read failed"));
}

int Transport::raw_write(void const* data, std::size_t size, int timeout_millis) {
    int ready = wait_socket(POLLOUT, timeout_millis);
    int stream_status = detail::poll_result_to_stream_status(ready);
    if (stream_status <= 0) return stream_status;
    if (!ssl_) {
        ssize_t count;
        do { count = ::send(fd_, data, size, MSG_NOSIGNAL); } while (count < 0 && errno == EINTR);
        if (count < 0) throw std::runtime_error(std::string("socket write failed: ") + std::strerror(errno));
        return static_cast<int>(count);
    }
    int count = SSL_write(ssl_, data, static_cast<int>(std::min<std::size_t>(size, static_cast<std::size_t>(INT_MAX))));
    if (count > 0) return count;
    int error = SSL_get_error(ssl_, count);
    if (error == SSL_ERROR_WANT_READ || error == SSL_ERROR_WANT_WRITE) return -2;
    throw std::runtime_error(ssl_error("TLS write failed"));
}

void Transport::write_line(std::string_view line, int timeout_millis) {
    if (fd_ < 0) throw std::runtime_error("transport is not connected");
    if (line.size() > 1024U * 1024U) throw std::invalid_argument("outbound pool message is too large");
    std::string frame(line); frame.push_back('\n');
    std::size_t offset = 0;
    while (offset < frame.size()) {
        int count = raw_write(frame.data() + offset, frame.size() - offset, timeout_millis);
        if (count == 0) throw std::runtime_error("pool connection closed while writing");
        if (count == -2) continue;
        if (count < 0) throw std::runtime_error("pool write timed out");
        offset += static_cast<std::size_t>(count);
    }
}

bool Transport::read_line(std::string& line, int timeout_millis) {
    line.clear();
    while (true) {
        auto newline = input_buffer_.find('\n');
        if (newline != std::string::npos) {
            line = input_buffer_.substr(0, newline);
            input_buffer_.erase(0, newline + 1U);
            if (!line.empty() && line.back() == '\r') line.pop_back();
            return true;
        }
        std::array<char, 8192> buffer{};
        int count = raw_read(buffer.data(), buffer.size(), timeout_millis);
        if (count == -2) continue;
        if (count < 0) return false;
        if (count == 0) throw std::runtime_error("pool connection closed");
        input_buffer_.append(buffer.data(), static_cast<std::size_t>(count));
        if (input_buffer_.size() > 2U * 1024U * 1024U) throw std::runtime_error("pool sent an oversized message");
    }
}

void Transport::close() noexcept {
    if (ssl_) { (void)SSL_shutdown(ssl_); SSL_free(ssl_); ssl_ = nullptr; }
    if (context_) { SSL_CTX_free(context_); context_ = nullptr; }
    if (fd_ >= 0) { ::shutdown(fd_, SHUT_RDWR); ::close(fd_); fd_ = -1; }
    peer_.clear(); input_buffer_.clear();
}
} // namespace cryptosteer
