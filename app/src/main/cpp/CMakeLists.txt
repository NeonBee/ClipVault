cmake_minimum_required(VERSION 3.22.1)

project(clipvault_native LANGUAGES CXX)

add_library(clipvault SHARED native_classifier.cpp)

target_compile_features(clipvault PRIVATE cxx_std_20)
target_compile_options(clipvault PRIVATE -fvisibility=hidden -fstack-protector-strong)
target_link_options(clipvault PRIVATE
        -Wl,-z,relro,-z,now
        -Wl,-z,max-page-size=16384
        -Wl,-z,common-page-size=16384)

find_library(log-lib log)
target_link_libraries(clipvault PRIVATE ${log-lib})
