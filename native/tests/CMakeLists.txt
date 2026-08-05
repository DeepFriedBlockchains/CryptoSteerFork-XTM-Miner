cmake_minimum_required(VERSION 3.20)
project(cryptosteer_host_tests LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
add_executable(cryptosteer_host_tests
    host_tests.cpp
    ../src/donation_scheduler.cpp
    ../src/hex.cpp
    ../src/json.cpp
    ../src/platform/affinity.c
)
target_include_directories(cryptosteer_host_tests PRIVATE ../include)
target_compile_options(cryptosteer_host_tests PRIVATE -Wall -Wextra -Wpedantic -Wconversion -Wsign-conversion -Wshadow -Werror=return-type)
