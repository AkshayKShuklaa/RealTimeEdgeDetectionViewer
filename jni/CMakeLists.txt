cmake_minimum_required(VERSION 3.18.1)

project(edge_processor LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(edge_processor SHARED
    src/edge_processor.cpp
)

find_library(log-lib log)

set(OpenCV_DIR "${OPENCV_ANDROID_SDK}/native/jni")
find_package(OpenCV REQUIRED core imgproc)

target_include_directories(edge_processor
    PRIVATE
        ${OpenCV_INCLUDE_DIRS}
)

target_link_libraries(edge_processor
    PRIVATE
        ${OpenCV_LIBS}
        ${log-lib}
)

