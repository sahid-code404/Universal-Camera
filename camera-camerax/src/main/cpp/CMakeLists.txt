cmake_minimum_required(VERSION 3.22.1)
project(omnicam_raw LANGUAGES CXX)

add_library(omnicam_raw SHARED native_raw_processor.cpp)

target_compile_features(omnicam_raw PRIVATE cxx_std_20)
target_compile_options(
    omnicam_raw
    PRIVATE
    -O3
    -ffast-math
    -fomit-frame-pointer
    -fstrict-aliasing
    -DNDEBUG
)

find_library(log_lib log)
find_library(jnigraphics_lib jnigraphics)

target_link_libraries(omnicam_raw PRIVATE ${log_lib} ${jnigraphics_lib})
