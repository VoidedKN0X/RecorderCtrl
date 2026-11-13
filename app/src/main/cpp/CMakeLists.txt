# On-phone speech-to-text for RecorderCtrl: whisper.cpp built from source as one JNI library, arm64-v8a only.
#
# whisper.cpp (MIT, https://github.com/ggml-org/whisper.cpp) is vendored in ./whisper.cpp:
#   release tag v1.9.5, commit d1be6fde11ac6e0407606b4e42fe72d34add8037
# Only what the CPU build needs is kept: include/whisper.h, src/whisper.cpp (+ whisper-arch.h and the aneforge
# stub it always includes, which compiles to nothing off Apple), ggml's CMake, headers, core sources and the CPU
# backend with the ARM kernels. GPU backends, other CPU architectures, KleidiAI and SpacemiT were left out.
# Nothing is downloaded at build time.
cmake_minimum_required(VERSION 3.22.1)
project(recorderctrl_whisper C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

# ggml: static, CPU only, no OpenMP (its own thread pool), no ccache, nothing fetched.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU ON CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE ON CACHE BOOL "" FORCE)
set(GGML_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
# ARMv8.2-A with half-precision maths and dot product: every phone core from the Cortex-A55/A75 on
# (2017+). RecorderCtrl checks the processor before loading the library and explains when it can't run.
set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)
# ggml stamps the git commit of the surrounding checkout into the library; keep builds identical instead.
set(GIT_EXE "${CMAKE_CURRENT_SOURCE_DIR}/no-git" CACHE FILEPATH "" FORCE)

# Same output whatever folder the repo is checked out in.
add_compile_options(
    "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=."
    -ffunction-sections -fdata-sections
)

add_subdirectory(${WHISPER_DIR}/ggml ggml)

add_library(whisper STATIC
    ${WHISPER_DIR}/src/whisper.cpp
    ${WHISPER_DIR}/src/aneforge/whisper-aneforge.cpp
)
target_include_directories(whisper PUBLIC ${WHISPER_DIR}/include ${WHISPER_DIR}/src)
target_compile_definitions(whisper PRIVATE WHISPER_VERSION="1.9.5")
target_link_libraries(whisper PUBLIC ggml)

add_library(recorderctrl_whisper SHARED whisper_jni.cpp)
target_compile_options(recorderctrl_whisper PRIVATE -fvisibility=hidden)
target_link_libraries(recorderctrl_whisper PRIVATE whisper)
target_link_options(recorderctrl_whisper PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    # 16 KB memory pages (Android 15+ devices that use them).
    -Wl,-z,max-page-size=16384
    -Wl,--build-id=none
)
