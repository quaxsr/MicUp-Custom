# MicUp-Custom native 音频核心（第2阶段：Oboe + JNI）
# 产物：libmicup_audio.so，与 Kotlin NativeAudioEngine.companion 的 System.loadLibrary("micup_audio") 一致
cmake_minimum_required(VERSION 3.22.1)
project(micup_audio LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Oboe 由 Gradle prefab 提供（app/build.gradle.kts: implementation("com.google.oboe:oboe:1.9.0")）
find_package(oboe REQUIRED CONFIG)

add_library(micup_audio SHARED
        AudioEngine.cpp)

find_library(log-lib log)

target_link_libraries(micup_audio
        PRIVATE
        oboe::oboe
        ${log-lib}
        android)
