cmake_minimum_required(VERSION 3.22)
project(zpaq715_fixed)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(zpaq715_fixed SHARED
    zpaq_jni.cpp
    zpaq_official_jni.cpp
    zpaq/libzpaq.cpp)

target_compile_definitions(zpaq715_fixed PRIVATE unix)

target_include_directories(zpaq715_fixed PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/zpaq
    ${CMAKE_CURRENT_SOURCE_DIR}/zpaqcli)

find_library(log-lib log)

target_link_libraries(zpaq715_fixed ${log-lib})
