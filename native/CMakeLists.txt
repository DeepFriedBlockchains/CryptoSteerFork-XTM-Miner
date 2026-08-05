cmake_minimum_required(VERSION 3.22.1)
project(cryptosteer_miner VERSION 2.2.1 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 17)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS OFF)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

option(CRYPTOSTEER_ENABLE_LTO "Enable interprocedural optimization" OFF)
option(CRYPTOSTEER_SANITIZERS "Enable address and undefined behavior sanitizers" OFF)
option(CRYPTOSTEER_BENCHMARK_BUILD "Enable benchmark-only instrumentation" OFF)

if(NOT ANDROID)
    message(FATAL_ERROR "The application native library must be configured with the Android NDK toolchain")
endif()
if(NOT DEFINED CRYPTOSTEER_THIRD_PARTY_PREFIX)
    message(FATAL_ERROR "CRYPTOSTEER_THIRD_PARTY_PREFIX must point to the verified Android ARM64 dependency prefix")
endif()

set(TP "${CRYPTOSTEER_THIRD_PARTY_PREFIX}")
foreach(required
    "${TP}/include/randomx.h"
    "${TP}/include/uv.h"
    "${TP}/include/hwloc.h"
    "${TP}/include/openssl/ssl.h"
    "${TP}/lib/librandomx.a"
    "${TP}/lib/libuv_a.a"
    "${TP}/lib/libhwloc.a"
    "${TP}/lib/libssl.a"
    "${TP}/lib/libcrypto.a")
    if(NOT EXISTS "${required}")
        message(FATAL_ERROR "Verified native dependency artifact is missing: ${required}")
    endif()
endforeach()

add_library(randomx STATIC IMPORTED GLOBAL)
set_target_properties(randomx PROPERTIES IMPORTED_LOCATION "${TP}/lib/librandomx.a")
add_library(uv_a STATIC IMPORTED GLOBAL)
set_target_properties(uv_a PROPERTIES IMPORTED_LOCATION "${TP}/lib/libuv_a.a")
add_library(hwloc_a STATIC IMPORTED GLOBAL)
set_target_properties(hwloc_a PROPERTIES IMPORTED_LOCATION "${TP}/lib/libhwloc.a")
add_library(ssl_a STATIC IMPORTED GLOBAL)
set_target_properties(ssl_a PROPERTIES IMPORTED_LOCATION "${TP}/lib/libssl.a")
add_library(crypto_a STATIC IMPORTED GLOBAL)
set_target_properties(crypto_a PROPERTIES IMPORTED_LOCATION "${TP}/lib/libcrypto.a")

add_library(cryptosteer_miner SHARED
    src/benchmark.cpp
    src/donation_scheduler.cpp
    src/hex.cpp
    src/jni_bridge.cpp
    src/json.cpp
    src/miner_engine.cpp
    src/randomx_context.cpp
    src/stratum_client.cpp
    src/transport.cpp
    src/cpu_topology.cpp
    src/platform/affinity.c
    src/platform/page_size.c
    src/platform/secure_zero.c
    src/platform/uv_support.c
)

target_include_directories(cryptosteer_miner PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
)
target_include_directories(cryptosteer_miner SYSTEM PRIVATE
    "${TP}/include"
)
target_compile_definitions(cryptosteer_miner PRIVATE
    CRYPTOSTEER_VERSION="2.2.1"
    OPENSSL_API_COMPAT=0x30500000L
    _FILE_OFFSET_BITS=64
)
target_compile_options(cryptosteer_miner PRIVATE
    -O3 -DNDEBUG -ffunction-sections -fdata-sections -fvisibility=hidden
    -fno-semantic-interposition -fomit-frame-pointer -fstack-protector-strong
    -Wall -Wextra -Wpedantic -Wconversion -Wsign-conversion -Wshadow
    -Wformat=2 -Wundef -Werror=return-type
)
target_link_options(cryptosteer_miner PRIVATE
    -Wl,--gc-sections -Wl,--exclude-libs,ALL
    -Wl,-z,relro -Wl,-z,now -Wl,-z,noexecstack
    -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
)

if(CRYPTOSTEER_ENABLE_LTO)
    include(CheckIPOSupported)
    check_ipo_supported(RESULT ipo_supported OUTPUT ipo_error)
    if(ipo_supported)
        set_property(TARGET cryptosteer_miner PROPERTY INTERPROCEDURAL_OPTIMIZATION TRUE)
    else()
        message(WARNING "Thin/full LTO is unavailable: ${ipo_error}")
    endif()
else()
    # Explicitly override any toolchain or cached IPO default. The pinned
    # RandomX AArch64 template contains a short conditional loop branch, and
    # ThinLTO layout previously made its global relocation exceed +/-1 MiB.
    set_property(TARGET cryptosteer_miner PROPERTY INTERPROCEDURAL_OPTIMIZATION FALSE)
    target_compile_options(cryptosteer_miner PRIVATE -fno-lto)
    target_link_options(cryptosteer_miner PRIVATE -fno-lto)
endif()
if(CRYPTOSTEER_SANITIZERS)
    target_compile_options(cryptosteer_miner PRIVATE -fsanitize=address,undefined -fno-omit-frame-pointer)
    target_link_options(cryptosteer_miner PRIVATE -fsanitize=address,undefined)
endif()

find_library(log_lib log REQUIRED)
find_library(android_lib android REQUIRED)
find_library(dl_lib dl REQUIRED)
find_library(z_lib z REQUIRED)
target_link_libraries(cryptosteer_miner PRIVATE
    randomx uv_a hwloc_a ssl_a crypto_a
    ${log_lib} ${android_lib} ${dl_lib} ${z_lib}
)
