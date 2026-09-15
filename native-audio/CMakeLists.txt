cmake_minimum_required(VERSION 3.22.1)
project(roxstar_audio)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)
FetchContent_Declare(
        oboe
        GIT_REPOSITORY https://github.com/google/oboe.git
        GIT_TAG 1.9.3
)
FetchContent_MakeAvailable(oboe)

add_library(roxstar-audio SHARED
        audio_engine.cpp
        jni_bridge.cpp)

find_library(log-lib log)

target_include_directories(roxstar-audio PRIVATE include)

target_link_libraries(roxstar-audio
        oboe
        ${log-lib})
