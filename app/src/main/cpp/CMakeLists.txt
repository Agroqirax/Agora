cmake_minimum_required(VERSION 3.22)
project("agora_llama")

set(LLAMA_CPP_DIR "F:/workspace/llama.cpp" CACHE PATH "llama.cpp source directory")

# Build llama.cpp as a static library
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "")
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "")
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "")
set(BUILD_SHARED_LIBS OFF CACHE BOOL "")
add_subdirectory(${LLAMA_CPP_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama)

# Build our JNI wrapper as a shared library
add_library(agora_llama SHARED llama_jni.cpp)
target_link_libraries(agora_llama llama)

target_include_directories(agora_llama PRIVATE ${LLAMA_CPP_DIR}/include)
