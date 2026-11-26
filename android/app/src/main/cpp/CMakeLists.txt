cmake_minimum_required(VERSION 3.22)
project(mlt_whisper C CXX)

# gizlilik: derleyen bilgisayarın klasör yolları (kullanıcı adı) kitaplığa gömülmesin
foreach(_ev IN ITEMS "$ENV{USERPROFILE}" "$ENV{HOME}")
    if(_ev)
        file(TO_CMAKE_PATH "${_ev}" _ev_yol)
        add_compile_options("-ffile-prefix-map=${_ev_yol}=~")
        string(REPLACE "/" "\\\\" _ev_ters "${_ev_yol}")
        add_compile_options("-ffile-prefix-map=${_ev_ters}=~")
    endif()
endforeach()

# whisper.cpp (masaüstünde de kullanılan v1.9.5) derleme sırasında indirilir
include(FetchContent)
# Hız: işlemcinin her nesli için ayrı bir sürüm derlenir (dotprod, fp16, i8mm, SVE…);
# uygulama açılışta telefonun desteklediği en hızlısını yükler (ggml_backend_load_all_from_path).
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)

FetchContent_Declare(whisper
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.5.tar.gz)
FetchContent_MakeAvailable(whisper)

add_library(mlt_whisper SHARED whisper_jni.cpp)
target_compile_options(mlt_whisper PRIVATE -O3)
target_link_libraries(mlt_whisper PRIVATE whisper ggml android log)

# ---------------------------------------------------------------- çevrimdışı çeviri
# NLLB-200 600M (masaüstündekiyle aynı CTranslate2 int8 modeli) + SentencePiece belirteçleyici.
# CTranslate2'nin alt modülleri (ruy, cpuinfo, spdlog, cpu_features) GitHub arşivinde olmadığından
# ayrı ayrı, commit'e sabitlenmiş olarak indirilip yerlerine konur. Hepsi statik, libmlt_ceviri.so'ya gömülür.
set(CMAKE_POLICY_DEFAULT_CMP0077 NEW)
set(_paylasimli ${BUILD_SHARED_LIBS})
set(BUILD_SHARED_LIBS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON CACHE BOOL "" FORCE)  # ct2 ruy'dan sonra normal değişkeni siler

FetchContent_Declare(ct2
    URL https://codeload.github.com/OpenNMT/CTranslate2/tar.gz/refs/tags/v4.6.0
    URL_HASH SHA256=9421d846668573466ceb713b27f71fb16fed8486ed7bdb654583c48c3c20621b
    SOURCE_SUBDIR yok)
FetchContent_MakeAvailable(ct2)
set(_tp ${ct2_SOURCE_DIR}/third_party)
FetchContent_Declare(ct2_ruy
    URL https://codeload.github.com/google/ruy/tar.gz/363f252289fb7a1fba1703d99196524698cb884d
    URL_HASH SHA256=3c2181c9134896784d5d0494e2638c43127de4da2ddf81b3a64c06311fb17432
    SOURCE_DIR ${_tp}/ruy SOURCE_SUBDIR yok)
FetchContent_Declare(ct2_spdlog
    URL https://codeload.github.com/gabime/spdlog/tar.gz/76fb40d95455f249bd70824ecfcae7a8f0930fa3
    URL_HASH SHA256=4530d53b86d45228a5b8982a8f4fe7d95d3ba8e1944858d3f663732c7e949cb7
    SOURCE_DIR ${_tp}/spdlog SOURCE_SUBDIR yok)
FetchContent_Declare(ct2_cpu_features
    URL https://codeload.github.com/google/cpu_features/tar.gz/8a494eb1e158ec2050e5f699a504fbc9b896a43b
    URL_HASH SHA256=9b2019dae7608630ab985129a5c3263fc788e4f82e26965a8d8c33b8c035802e
    SOURCE_DIR ${_tp}/cpu_features SOURCE_SUBDIR yok)
FetchContent_MakeAvailable(ct2_ruy ct2_spdlog ct2_cpu_features)
FetchContent_Declare(ct2_cpuinfo
    URL https://codeload.github.com/pytorch/cpuinfo/tar.gz/082deffc80ce517f81dc2f3aebe6ba671fcd09c9
    URL_HASH SHA256=4379348ec3127b37e854a0a66f85ea1d3c606e5f3a6dce235dc9c69ce663c026
    SOURCE_DIR ${_tp}/ruy/third_party/cpuinfo SOURCE_SUBDIR yok)
FetchContent_MakeAvailable(ct2_cpuinfo)

# Android'de pthread_setaffinity_np yok: iş parçacığı sabitleme (kullanılmıyor) Linux dışı gibi derlenir
file(READ ${ct2_SOURCE_DIR}/src/thread_pool.cc _tp_kaynak)
string(REPLACE "#if !defined(__linux__) || defined(_OPENMP)\n" "#if !defined(__linux__) || defined(_OPENMP) || defined(__ANDROID__)\n" _tp_yeni "${_tp_kaynak}")
if(NOT _tp_yeni STREQUAL _tp_kaynak)
    file(WRITE ${ct2_SOURCE_DIR}/src/thread_pool.cc "${_tp_yeni}")
endif()

set(WITH_MKL OFF)
set(WITH_RUY ON)
set(OPENMP_RUNTIME NONE CACHE STRING "" FORCE)
set(BUILD_CLI OFF)
add_subdirectory(${ct2_SOURCE_DIR} ${CMAKE_BINARY_DIR}/ct2 EXCLUDE_FROM_ALL)

set(SPM_ENABLE_SHARED OFF)
set(SPM_ENABLE_TCMALLOC OFF)
FetchContent_Declare(sentencepiece
    URL https://codeload.github.com/google/sentencepiece/tar.gz/refs/tags/v0.2.0
    URL_HASH SHA256=9970f0a0afee1648890293321665e5b2efa04eaec9f1671fcf8048f456f5bb86
    SOURCE_SUBDIR yok)
FetchContent_MakeAvailable(sentencepiece)
add_subdirectory(${sentencepiece_SOURCE_DIR} ${CMAKE_BINARY_DIR}/sentencepiece EXCLUDE_FROM_ALL)
# Android Gradle her hedefi derler: sentencepiece'in komut satırı araçları da Android günlüğüne bağlanmalı
foreach(_arac spm_encode spm_decode spm_normalize spm_train spm_export_vocab)
    if(TARGET ${_arac})
        target_link_libraries(${_arac} log)
    endif()
endforeach()
set(BUILD_SHARED_LIBS ${_paylasimli})

add_library(mlt_ceviri SHARED ceviri_jni.cpp)
target_compile_options(mlt_ceviri PRIVATE -O3)
target_include_directories(mlt_ceviri PRIVATE ${sentencepiece_SOURCE_DIR}/src)
target_link_libraries(mlt_ceviri PRIVATE ctranslate2 sentencepiece-static android log)
