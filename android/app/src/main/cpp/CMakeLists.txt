cmake_minimum_required(VERSION 3.22.1)
project(questtimevr)

find_package(OpenXR REQUIRED CONFIG)

add_library(questtimevr SHARED vr_renderer.cpp)

target_compile_options(questtimevr PRIVATE -Wall -Wno-unused-parameter)

target_link_libraries(questtimevr
    OpenXR::openxr_loader
    android
    log
    EGL
    GLESv3)
