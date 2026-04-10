cmake_minimum_required(VERSION 3.14)
project(KhromiumCore)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

include(FetchContent)

# 1. Fetch TLSF
FetchContent_Declare(
    tlsf
    GIT_REPOSITORY https://github.com/mattconte/tlsf.git
    GIT_TAG        master
)
FetchContent_MakeAvailable(tlsf)

# 2. Fetch QuickJS-NG (Maintained QuickJS fork with native CMake support)
FetchContent_Declare(
    quickjs
    GIT_REPOSITORY https://github.com/quickjs-ng/quickjs.git
    GIT_TAG        master
)
# QuickJS-NG produces a static library target 'qjs'
FetchContent_MakeAvailable(quickjs)

# Find JNI
find_package(JNI REQUIRED)
include_directories(${JNI_INCLUDE_DIRS})

# Our wrapper library
add_library(KhromiumCore SHARED KhromiumMem.cpp QuickJSBridge.cpp)
target_include_directories(KhromiumCore PRIVATE ${tlsf_SOURCE_DIR})
target_link_libraries(KhromiumCore PRIVATE qjs tlsf)
