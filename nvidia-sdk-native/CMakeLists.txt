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
    message(STATUS "NVIDIA Streamline SDK root: $ENV{NVIDIA_STREAMLINE_ROOT}")
else()
    message(STATUS "NVIDIA_STREAMLINE_ROOT is unset; building the M0 probe-only bridge.")
endif()
