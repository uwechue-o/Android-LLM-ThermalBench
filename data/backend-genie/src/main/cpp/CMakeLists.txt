# Native half of the Qualcomm Genie adapter.
#
# Built only when the project is configured with -Pthermalbench.qairtDir=<an
# unpacked QAIRT SDK>. Only the SDK's Genie headers are used here: libGenie.so
# is opened at run time, so this library loads on any device and reports
# Genie's absence instead of failing to load.
cmake_minimum_required(VERSION 3.22.1)
project(thermalbench_genie CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_FLAGS_RELEASE "-O2 -DNDEBUG")

if(NOT DEFINED QAIRT_DIR OR NOT EXISTS "${QAIRT_DIR}/include/Genie/GenieDialog.h")
    message(FATAL_ERROR "QAIRT_DIR (${QAIRT_DIR}) is not a QAIRT SDK: include/Genie/GenieDialog.h is missing.")
endif()

add_library(thermalbench_genie SHARED genie_jni.cpp)
target_include_directories(thermalbench_genie PRIVATE "${QAIRT_DIR}/include/Genie")

find_library(log-lib log)
target_link_libraries(thermalbench_genie ${log-lib} dl)
