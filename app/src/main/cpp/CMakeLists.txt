cmake_minimum_required(VERSION 3.18)

# Debug APK ichida ham dvigatel to'liq optimallashtirilgan bo'lishi shart,
# aks holda rasm yaratish o'nlab marta sekinlashadi.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

project(sdjni C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(SD_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(SD_WEBP OFF CACHE BOOL "" FORCE)
set(SD_WEBM OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

if(ANDROID)
    # ARMv8.2: yarim aniqlik (fp16) va dotprod buyruqlari — 2018-yildan keyingi telefonlar.
    add_compile_options(-O3 -march=armv8.2-a+fp16+dotprod)
endif()

# stable-diffusion.cpp manbasi GitHub Actions ichida shu papkaga yuklanadi
# (.github/workflows/build.yml ga qarang).
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/sdcpp sdcpp_build EXCLUDE_FROM_ALL)

add_library(sdjni SHARED sd_jni.cpp)
target_include_directories(sdjni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/sdcpp/include)
target_link_libraries(sdjni PRIVATE stable-diffusion)

if(ANDROID)
    target_link_libraries(sdjni PRIVATE log)
    # 16 KB xotira sahifali qurilmalar bilan mos bo'lishi uchun.
    target_link_options(sdjni PRIVATE "-Wl,-z,max-page-size=16384")
endif()
