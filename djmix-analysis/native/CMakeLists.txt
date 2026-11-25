cmake_minimum_required(VERSION 3.22)
project(ae3_host_analysis LANGUAGES CXX)
find_package(JNI REQUIRED)
get_filename_component(REPO "${CMAKE_CURRENT_SOURCE_DIR}/../.." ABSOLUTE)
# Read-only reuse of exactly the phone DSP and JNI entrypoints. No host DSP fork.
add_library(bitchord_analysis SHARED
    ${REPO}/native/analyzer/audio_analysis.cpp
    ${REPO}/native/analyzer/tempo_analysis.cpp
    ${REPO}/native/analyzer/resampler.cpp
    ${REPO}/native/analyzer/mel_spectrogram.cpp
    ${REPO}/native/analyzer/stem_dsp.cpp
    ${REPO}/app/src/main/cpp/jni/analysis_jni.cpp
    ${REPO}/app/src/main/cpp/jni/mel_jni.cpp
    ${REPO}/app/src/main/cpp/jni/stem_jni.cpp)
target_include_directories(bitchord_analysis PRIVATE ${JNI_INCLUDE_DIRS} ${REPO}/native)
target_compile_features(bitchord_analysis PRIVATE cxx_std_17)
target_compile_options(bitchord_analysis PRIVATE -O3 -fno-fast-math -ffp-contract=off -Wall -Wextra -fvisibility=hidden)
