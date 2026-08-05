#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'
# shellcheck source=common.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/common.sh"
ROOT=$(repo_root)
require_command python3

# Prefer the unversioned Clang driver, but support Ubuntu releases that only
# install versioned binaries such as clang-21/clang++-21. Do not silently fall
# back to the Android NDK compiler: these checks intentionally validate the
# first-party sources against the Linux host toolchain and host JNI headers.
HOST_CLANG="${HOST_CLANG:-}"
HOST_CLANGXX="${HOST_CLANGXX:-}"
if [[ -z "$HOST_CLANG" || -z "$HOST_CLANGXX" ]]; then
  for version in "" 21 20 19 18 17 16 15; do
    cc_name="clang${version:+-$version}"
    cxx_name="clang++${version:+-$version}"
    if command -v "$cc_name" >/dev/null 2>&1 && command -v "$cxx_name" >/dev/null 2>&1; then
      HOST_CLANG=$(command -v "$cc_name")
      HOST_CLANGXX=$(command -v "$cxx_name")
      break
    fi
  done
fi
[[ -x "$HOST_CLANG" && -x "$HOST_CLANGXX" ]] || die "A host Clang/Clang++ pair is required. Run scripts/setup-linux-builder.sh."
log "Using host compilers: $HOST_CLANG and $HOST_CLANGXX"
TEMP_DIR=$(mktemp -d)
STUBS="$TEMP_DIR/stubs"
mkdir -p "$STUBS"

cat > "$STUBS/randomx.h" <<'HEADER'
#pragma once
#include <stddef.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef enum randomx_flags {
  RANDOMX_FLAG_DEFAULT = 0,
  RANDOMX_FLAG_LARGE_PAGES = 1,
  RANDOMX_FLAG_HARD_AES = 2,
  RANDOMX_FLAG_FULL_MEM = 4,
  RANDOMX_FLAG_JIT = 8,
  RANDOMX_FLAG_SECURE = 16
} randomx_flags;
typedef struct randomx_cache randomx_cache;
typedef struct randomx_dataset randomx_dataset;
typedef struct randomx_vm randomx_vm;
randomx_flags randomx_get_flags(void);
randomx_cache* randomx_alloc_cache(randomx_flags);
void randomx_init_cache(randomx_cache*, const void*, size_t);
void randomx_release_cache(randomx_cache*);
randomx_dataset* randomx_alloc_dataset(randomx_flags);
unsigned long randomx_dataset_item_count(void);
void randomx_init_dataset(randomx_dataset*, randomx_cache*, unsigned long, unsigned long);
void randomx_release_dataset(randomx_dataset*);
randomx_vm* randomx_create_vm(randomx_flags, randomx_cache*, randomx_dataset*);
void randomx_destroy_vm(randomx_vm*);
void randomx_calculate_hash(randomx_vm*, const void*, size_t, void*);
void randomx_calculate_hash_first(randomx_vm*, const void*, size_t);
void randomx_calculate_hash_next(randomx_vm*, const void*, size_t, void*);
void randomx_calculate_hash_last(randomx_vm*, void*);
#ifdef __cplusplus
}
#endif
HEADER

cat > "$STUBS/hwloc.h" <<'HEADER'
#pragma once
#ifdef __cplusplus
extern "C" {
#endif
typedef struct hwloc_topology* hwloc_topology_t;
typedef enum hwloc_obj_type_t { HWLOC_OBJ_PU = 3 } hwloc_obj_type_t;
typedef struct hwloc_obj { unsigned os_index; } *hwloc_obj_t;
int hwloc_topology_init(hwloc_topology_t*);
int hwloc_topology_load(hwloc_topology_t);
void hwloc_topology_destroy(hwloc_topology_t);
unsigned hwloc_get_api_version(void);
int hwloc_get_nbobjs_by_type(hwloc_topology_t, hwloc_obj_type_t);
hwloc_obj_t hwloc_get_obj_by_type(hwloc_topology_t, hwloc_obj_type_t, unsigned);
#ifdef __cplusplus
}
#endif
HEADER

cat > "$STUBS/uv.h" <<'HEADER'
#pragma once
#include <netdb.h>
#include <stdint.h>
#include <sys/socket.h>
#ifdef __cplusplus
extern "C" {
#endif
#define UV_EINVAL (-22)
#define UV_EAI_AGAIN (-3001)
#define UV_RUN_DEFAULT 0
typedef struct uv_loop_s { void* data; } uv_loop_t;
typedef struct uv_handle_s { void* data; } uv_handle_t;
typedef struct uv_getaddrinfo_s { void* data; } uv_getaddrinfo_t;
typedef struct uv_timer_s { void* data; } uv_timer_t;
typedef void (*uv_getaddrinfo_cb)(uv_getaddrinfo_t*, int, struct addrinfo*);
typedef void (*uv_timer_cb)(uv_timer_t*);
typedef void (*uv_close_cb)(uv_handle_t*);
int uv_loop_init(uv_loop_t*);
int uv_loop_close(uv_loop_t*);
int uv_run(uv_loop_t*, int);
int uv_getaddrinfo(uv_loop_t*, uv_getaddrinfo_t*, uv_getaddrinfo_cb, const char*, const char*, const struct addrinfo*);
void uv_freeaddrinfo(struct addrinfo*);
const char* uv_strerror(int);
int uv_ip4_name(const struct sockaddr_in*, char*, size_t);
int uv_ip6_name(const struct sockaddr_in6*, char*, size_t);
int uv_timer_init(uv_loop_t*, uv_timer_t*);
int uv_timer_start(uv_timer_t*, uv_timer_cb, uint64_t, uint64_t);
int uv_timer_stop(uv_timer_t*);
void uv_close(uv_handle_t*, uv_close_cb);
const char* uv_version_string(void);
void uv_sleep(unsigned int);
#ifdef __cplusplus
}
#endif
HEADER

# Minimal OpenSSL declarations for host-only translation-unit syntax checks.
# The Android release still compiles and links against the pinned, source-built
# OpenSSL archive. These stubs deliberately expose only the APIs first-party
# code uses, so the host audit remains independent of host OpenSSL packages.
mkdir -p "$STUBS/openssl"
cat > "$STUBS/openssl/crypto.h" <<'HEADER'
#pragma once
#ifdef __cplusplus
extern "C" {
#endif
#define OPENSSL_VERSION 0
const char* OpenSSL_version(int type);
#ifdef __cplusplus
}
#endif
HEADER

cat > "$STUBS/openssl/err.h" <<'HEADER'
#pragma once
#include <stddef.h>
#ifdef __cplusplus
extern "C" {
#endif
unsigned long ERR_get_error(void);
void ERR_error_string_n(unsigned long error, char* buffer, size_t length);
#ifdef __cplusplus
}
#endif
HEADER

cat > "$STUBS/openssl/ssl.h" <<'HEADER'
#pragma once
#include <stddef.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef struct ssl_ctx_st SSL_CTX;
typedef struct ssl_st SSL;
typedef struct ssl_method_st SSL_METHOD;
typedef struct x509_st X509;
typedef struct x509_verify_param_st X509_VERIFY_PARAM;
typedef struct evp_md_st EVP_MD;
#define TLS1_2_VERSION 0x0303
#define SSL_OP_NO_COMPRESSION 0x00020000UL
#define SSL_VERIFY_NONE 0x00
#define SSL_VERIFY_PEER 0x01
#define SSL_ERROR_WANT_READ 2
#define SSL_ERROR_WANT_WRITE 3
#define SSL_ERROR_ZERO_RETURN 6
#define X509_V_OK 0L
#define EVP_MAX_MD_SIZE 64
const SSL_METHOD* TLS_client_method(void);
SSL_CTX* SSL_CTX_new(const SSL_METHOD* method);
void SSL_CTX_free(SSL_CTX* context);
int SSL_CTX_set_min_proto_version(SSL_CTX* context, int version);
unsigned long SSL_CTX_set_options(SSL_CTX* context, unsigned long options);
void SSL_CTX_set_verify(SSL_CTX* context, int mode, void* callback);
int SSL_CTX_set_default_verify_paths(SSL_CTX* context);
int SSL_CTX_load_verify_locations(SSL_CTX* context, const char* ca_file, const char* ca_path);
SSL* SSL_new(SSL_CTX* context);
void SSL_free(SSL* ssl);
int SSL_set_tlsext_host_name(SSL* ssl, const char* name);
X509_VERIFY_PARAM* SSL_get0_param(SSL* ssl);
int SSL_set1_host(SSL* ssl, const char* hostname);
int SSL_set_fd(SSL* ssl, int fd);
int SSL_connect(SSL* ssl);
long SSL_get_verify_result(const SSL* ssl);
X509* SSL_get1_peer_certificate(const SSL* ssl);
int SSL_read(SSL* ssl, void* buffer, int count);
int SSL_write(SSL* ssl, const void* buffer, int count);
int SSL_get_error(const SSL* ssl, int result);
int SSL_shutdown(SSL* ssl);
int X509_digest(const X509* certificate, const EVP_MD* algorithm, unsigned char* digest, unsigned int* digest_length);
void X509_free(X509* certificate);
const EVP_MD* EVP_sha256(void);
#ifdef __cplusplus
}
#endif
HEADER

cat > "$STUBS/openssl/x509v3.h" <<'HEADER'
#pragma once
#include <openssl/ssl.h>
#ifdef __cplusplus
extern "C" {
#endif
int X509_VERIFY_PARAM_set1_ip_asc(X509_VERIFY_PARAM* parameter, const char* ip_address);
#ifdef __cplusplus
}
#endif
HEADER

# Minimal Android-flavored JNI C++ API. Android's JavaVM overload accepts
# JNIEnv**, while desktop JDK headers commonly expose void**. Compiling against
# both forms prevents either environment from hiding a signature regression.
mkdir -p "$STUBS/android-jni"
cat > "$STUBS/android-jni/jni.h" <<'HEADER'
#pragma once
#include <cstdarg>
#include <cstdint>
#define JNIEXPORT
#define JNICALL
#define JNI_OK 0
#define JNI_EDETACHED (-2)
#define JNI_VERSION_1_6 0x00010006
#define JNI_TRUE 1
#define JNI_FALSE 0
using jint = std::int32_t;
using jlong = std::int64_t;
using jboolean = std::uint8_t;
struct _jobject;
using jobject = _jobject*;
using jclass = jobject;
using jstring = jobject;
using jmethodID = void*;
class JNIEnv {
public:
  const char* GetStringUTFChars(jstring, jboolean*);
  void ReleaseStringUTFChars(jstring, const char*);
  jstring NewStringUTF(const char*);
  jclass FindClass(const char*);
  jint ThrowNew(jclass, const char*);
  jobject NewGlobalRef(jobject);
  jclass GetObjectClass(jobject);
  jmethodID GetMethodID(jclass, const char*, const char*);
  void DeleteGlobalRef(jobject);
  void DeleteLocalRef(jobject);
  void CallVoidMethod(jobject, jmethodID, ...);
  jboolean ExceptionCheck();
  void ExceptionDescribe();
  void ExceptionClear();
};
class JavaVM {
public:
  jint GetEnv(void**, jint);
  jint AttachCurrentThread(JNIEnv**, void*);
  jint DetachCurrentThread();
};
HEADER

JAVA_HOME_DETECTED="${JAVA_HOME:-}"
if [[ -z "$JAVA_HOME_DETECTED" ]] && command -v javac >/dev/null 2>&1; then
  JAVA_HOME_DETECTED=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")
fi
JNI_ARGS=()
if [[ -n "$JAVA_HOME_DETECTED" && -f "$JAVA_HOME_DETECTED/include/jni.h" ]]; then
  JNI_ARGS+=( -I"$JAVA_HOME_DETECTED/include" )
  [[ -d "$JAVA_HOME_DETECTED/include/linux" ]] && JNI_ARGS+=( -I"$JAVA_HOME_DETECTED/include/linux" )
else
  log "JDK JNI headers unavailable; jni_bridge.cpp will be checked by the Android build."
fi

CXX_ARGS=(
  -std=c++20 -fsyntax-only -Wall -Wextra -Wpedantic -Werror
  -DCRYPTOSTEER_VERSION=\"$PROJECT_VERSION\"
  -I"$ROOT/native/include" -I"$STUBS"
  "${JNI_ARGS[@]}"
)
for source in "$ROOT"/native/src/*.cpp; do
  if [[ "$(basename "$source")" == "jni_bridge.cpp" && ${#JNI_ARGS[@]} -eq 0 ]]; then continue; fi
  "$HOST_CLANGXX" "${CXX_ARGS[@]}" "$source"
done
for source in "$ROOT"/native/src/platform/*.c; do
  "$HOST_CLANG" -std=c17 -fsyntax-only -Wall -Wextra -Wpedantic -Werror -D_POSIX_C_SOURCE=200112L \
    -I"$ROOT/native/include" -I"$STUBS" "$source"
done
log "Native C/C++ translation-unit syntax checks: PASS"

"$HOST_CLANGXX" \
  -std=c++20 -fsyntax-only -Wall -Wextra -Wpedantic -Werror \
  -DANDROID -D__ANDROID__ -DCRYPTOSTEER_VERSION=\"$PROJECT_VERSION\" \
  -I"$STUBS/android-jni" -I"$ROOT/native/include" -I"$STUBS" \
  "$ROOT/native/src/jni_bridge.cpp"
log "Android-signature JNI bridge syntax check: PASS"

# The desktop JDK and Android NDK expose different C++ overloads for
# JavaVM::AttachCurrentThread. Compile the JNI bridge once with the actual NDK
# headers whenever the pinned NDK is available so host-only checks cannot mask
# an Android ABI signature error.
NDK_ROOT="${ANDROID_NDK_ROOT:-}"
if [[ -z "$NDK_ROOT" && -n "${ANDROID_SDK_ROOT:-}" ]]; then
  NDK_ROOT="$ANDROID_SDK_ROOT/ndk/$ANDROID_NDK_VERSION"
fi
if [[ -z "$NDK_ROOT" && -d "${HOME}/Android/Sdk/ndk/$ANDROID_NDK_VERSION" ]]; then
  NDK_ROOT="${HOME}/Android/Sdk/ndk/$ANDROID_NDK_VERSION"
fi
NDK_CLANGXX="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/clang++"
NDK_SYSROOT="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
if [[ -x "$NDK_CLANGXX" && -d "$NDK_SYSROOT" ]]; then
  "$NDK_CLANGXX" \
    --target="aarch64-linux-android${ANDROID_NATIVE_API}" \
    --sysroot="$NDK_SYSROOT" \
    -std=c++20 -fsyntax-only -DANDROID \
    -DCRYPTOSTEER_VERSION=\"$PROJECT_VERSION\" \
    -I"$ROOT/native/include" -I"$STUBS" \
    "$ROOT/native/src/jni_bridge.cpp"
  log "Android NDK JNI bridge syntax check: PASS"
else
  log "Pinned Android NDK unavailable; JNI bridge NDK-header check is deferred to the Android build."
fi

if command -v kotlinc >/dev/null 2>&1 && command -v kotlin >/dev/null 2>&1; then
  KOTLIN_OUT="$TEMP_DIR/kotlin"
  mkdir -p "$KOTLIN_OUT"
  cat > "$TEMP_DIR/DomainSmoke.kt" <<'KOTLIN'
import com.thecryptosteer.xmrminer.domain.*
import com.thecryptosteer.xmrminer.ui.*
fun main() {
    val minute = 60L * 1_000_000_000L
    check(DonationMath.percent(49L * minute, minute) == 2.0)
    check(DonationMath.invariantHolds(49L * minute, minute))
    check(PoolValidator.parseAddress("stratum+tls://pool.example.com:443").tls)
    val validPool = PoolProfile(
        name = "Primary",
        primary = PoolEndpoint("pool.example.com", 443, true),
        username = "wallet-or-pool-user",
        worker = "Pixel10-Max",
    )
    check(PoolValidator.validate(validPool).valid)
    check(!PoolValidator.validate(validPool.copy(worker = "")).valid)
    val snapshot = MiningSnapshot(userMiningNanos = 49L * minute, donationMiningNanos = minute)
    check(snapshot.donationPercent <= 2.0)
    check(formatDuration(5) == "00:05")
    check(formatDuration(65) == "01:05")
    check(formatDuration(3_661) == "1:01:01")
    check(humanMiningSummary(MiningState.CONNECTING, "retrying") == "Reconnecting to pool")
    check(runtimePageSizeLabel(16_384) == "16 KiB runtime")
    val points = (0 until 10_000).map { HashratePoint(it.toLong(), it.toDouble(), it % 7, it % 101) }
    val bounded = decimatePoints(points, 260)
    check(bounded.size <= 260 && bounded.first() == points.first() && bounded.last() == points.last())
    val now = 1_000_000L
    val timed = (0..20).map { index -> HashratePoint(now - index * 60_000L, index.toDouble(), 0, 50) }
    check(chartWindow(timed, ChartRange.LIVE, now).durationMillis == 60_000L)
    check(chartWindow(timed, ChartRange.FIVE_MINUTES, now).points.size < chartWindow(timed, ChartRange.ONE_HOUR, now).points.size)
    check(compactHashrateHistory(points, 256).size <= 256)
    println("Kotlin domain and UI-logic smoke tests: PASS")
}
KOTLIN
  kotlinc \
    "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/domain/DonationMath.kt" \
    "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/domain/Models.kt" \
    "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/domain/PoolValidator.kt" \
    "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui/UiLogic.kt" \
    "$TEMP_DIR/DomainSmoke.kt" -d "$KOTLIN_OUT/domain.jar"
  kotlin -classpath "$KOTLIN_OUT/domain.jar" DomainSmokeKt

  # Compile the Android-independent pool login parser with lightweight stubs.
  # This catches Kotlin front-end errors in the live pool/benchmark path before
  # a full Android SDK build, including prohibited member-extension references.
  mkdir -p "$TEMP_DIR/pool-stubs/com/thecryptosteer/xmrminer" \
           "$TEMP_DIR/pool-stubs/com/thecryptosteer/xmrminer/domain" \
           "$TEMP_DIR/pool-stubs/org/json"
  cat > "$TEMP_DIR/pool-stubs/com/thecryptosteer/xmrminer/BuildConfig.kt" <<'KOTLIN'
package com.thecryptosteer.xmrminer
object BuildConfig { const val VERSION_NAME: String = "syntax-check" }
KOTLIN
  cat > "$TEMP_DIR/pool-stubs/com/thecryptosteer/xmrminer/domain/PoolEndpoint.kt" <<'KOTLIN'
package com.thecryptosteer.xmrminer.domain
data class PoolEndpoint(
    val host: String,
    val port: Int,
    val tls: Boolean = false,
    val serverName: String = "",
    val pinnedSha256: String = "",
)
KOTLIN
  cat > "$TEMP_DIR/pool-stubs/org/json/JsonStubs.kt" <<'KOTLIN'
@file:Suppress("UNUSED_PARAMETER")
package org.json
class JSONArray { fun put(value: Any?): JSONArray = this }
class JSONObject {
    constructor()
    constructor(source: String)
    fun put(name: String, value: Any?): JSONObject = this
    fun optString(name: String): String = ""
    fun optString(name: String, fallback: String): String = fallback
    fun optJSONObject(name: String): JSONObject? = null
    fun optInt(name: String, fallback: Int): Int = fallback
    fun optLong(name: String, fallback: Long): Long = fallback
    fun length(): Int = 0
}
KOTLIN
  kotlinc \
    "$TEMP_DIR/pool-stubs/com/thecryptosteer/xmrminer/BuildConfig.kt" \
    "$TEMP_DIR/pool-stubs/com/thecryptosteer/xmrminer/domain/PoolEndpoint.kt" \
    "$TEMP_DIR/pool-stubs/org/json/JsonStubs.kt" \
    "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/data/PoolConnectionTester.kt" \
    -d "$KOTLIN_OUT/pool-connection-tester.jar"
  log "Pool connection and live-job Kotlin syntax check: PASS"
else
  log "Standalone Kotlin compiler unavailable; Gradle performs Kotlin compilation during the Android build."
fi
