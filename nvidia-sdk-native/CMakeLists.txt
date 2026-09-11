cmake_minimum_required(VERSION 3.24)
project(mc_reflex_tools_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT WIN32)
    message(FATAL_ERROR "The NVIDIA SDK bridge currently targets Windows only.")
endif()

if(NOT DEFINED ENV{JAVA_HOME})
    message(FATAL_ERROR "Set JAVA_HOME to a JDK that provides jni.h.")
endif()

find_package(JNI REQUIRED)

add_library(mc_reflex_tools_native SHARED src/jni_exports.cpp)
target_include_directories(mc_reflex_tools_native PRIVATE ${JNI_INCLUDE_DIRS})
target_compile_definitions(mc_reflex_tools_native PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX)

# Streamline is intentionally not vendored. Set NVIDIA_STREAMLINE_ROOT to an SDK
# distribution accepted under its license before enabling the native implementation.
if(DEFINED ENV{NVIDIA_STREAMLINE_ROOT})
    set(STREAMLINE_ROOT "$ENV{NVIDIA_STREAMLINE_ROOT}")
    set(STREAMLINE_INCLUDE_DIR "${STREAMLINE_ROOT}/include")

    if(EXISTS "${STREAMLINE_INCLUDE_DIR}/sl.h" AND EXISTS "${STREAMLINE_INCLUDE_DIR}/sl_helpers_vk.h")
        target_include_directories(mc_reflex_tools_native PRIVATE "${STREAMLINE_INCLUDE_DIR}")
        target_link_libraries(mc_reflex_tools_native PRIVATE "${STREAMLINE_ROOT}/lib/x64/sl.interposer.lib")
        target_compile_definitions(mc_reflex_tools_native PRIVATE MC_REFLEX_TOOLS_HAS_STREAMLINE=1)
        set(STREAMLINE_ENABLED TRUE)
        message(STATUS "NVIDIA Streamline headers detected at: ${STREAMLINE_INCLUDE_DIR}")
    else()
        message(WARNING "NVIDIA_STREAMLINE_ROOT is set, but Streamline headers were not found under ${STREAMLINE_INCLUDE_DIR}")
    endif()
else()
    message(STATUS "NVIDIA_STREAMLINE_ROOT is unset; building the M2 native readiness probe only.")
endif()

# A compile database for clangd. The Visual Studio generator does not implement
# CMAKE_EXPORT_COMPILE_COMMANDS, so write the single entry by hand from the same
# variables the real target uses — that is what lets clangd resolve jni.h and the
# Streamline headers instead of falling back to a flagless command. clangd finds
# the MSVC standard library on its own; only these extra paths are missing.
# .clangd in the repository root points clangd at this file, which lands in the
# gitignored build directory.
set(_clangd_defs "-DWIN32_LEAN_AND_MEAN" "-DNOMINMAX")
set(_clangd_includes ${JNI_INCLUDE_DIRS})
if(STREAMLINE_ENABLED)
    list(APPEND _clangd_defs "-DMC_REFLEX_TOOLS_HAS_STREAMLINE=1")
    list(APPEND _clangd_includes "${STREAMLINE_INCLUDE_DIR}")
endif()
list(REMOVE_DUPLICATES _clangd_includes)

set(_clangd_args "\"-std=c++20\"")
foreach(_flag IN LISTS _clangd_defs _clangd_includes)
    if(_flag MATCHES "^-D")
        string(APPEND _clangd_args ",\n    \"${_flag}\"")
    else()
        file(TO_CMAKE_PATH "${_flag}" _flag_posix)
        string(APPEND _clangd_args ",\n    \"-I${_flag_posix}\"")
    endif()
endforeach()

set(CLANGD_ARGUMENTS "${_clangd_args}")
set(CLANGD_DIRECTORY "${CMAKE_BINARY_DIR}")
set(CLANGD_SOURCE "${CMAKE_CURRENT_SOURCE_DIR}/src/jni_exports.cpp")
configure_file(cmake/compile_commands.json.in compile_commands.json @ONLY)
