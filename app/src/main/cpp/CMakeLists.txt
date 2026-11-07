cmake_minimum_required(VERSION 3.22.1)
project(flowery_native LANGUAGES C CXX)

include(FetchContent)

set(LIBUSB_BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(LIBUSB_INSTALL_TARGETS OFF CACHE BOOL "" FORCE)
set(LIBUSB_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LIBUSB_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(LIBUSB_ENABLE_LOGGING ON CACHE BOOL "" FORCE)

FetchContent_Declare(
    libusb_cmake
    GIT_REPOSITORY https://github.com/libusb/libusb-cmake.git
    GIT_TAG v1.0.30-0
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(libusb_cmake)


add_library(flowery_usb SHARED usb_audio_jni.cpp)
target_compile_features(flowery_usb PRIVATE cxx_std_17)
target_link_libraries(flowery_usb PRIVATE usb-1.0 log)
