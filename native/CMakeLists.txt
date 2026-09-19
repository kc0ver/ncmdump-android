cmake_minimum_required(VERSION 3.22)

project(ncmdump_android LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 上游 ncmdump 源码位置（git submodule）
set(NCMDUMP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../third_party/ncmdump" CACHE PATH "ncmdump 上游源码目录")

if(NOT EXISTS "${NCMDUMP_DIR}/src/main.cpp")
  message(FATAL_ERROR "找不到 ncmdump 源码：${NCMDUMP_DIR}\n请先执行 git submodule update --init --recursive")
endif()

file(GLOB NCMDUMP_COMMON_SOURCES
  "${NCMDUMP_DIR}/src/ncmcrypt.cpp"
  "${NCMDUMP_DIR}/src/utils/*.cpp")

# 上游 main.cpp 是 CLI 入口，直接沿用，不改一行代码
add_executable(ncmdump_exec
  ${NCMDUMP_COMMON_SOURCES}
  "${NCMDUMP_DIR}/src/main.cpp")

target_include_directories(ncmdump_exec PRIVATE "${NCMDUMP_DIR}/src/include")

# TagLib 的 imported target 会引用 ZLIB::ZLIB，必须先找 zlib
find_package(ZLIB REQUIRED)
find_package(TagLib REQUIRED)
if(TARGET TagLib::tag)
  set(NCMDUMP_TAGLIB_TARGET TagLib::tag)
elseif(TARGET TagLib::taglib)
  set(NCMDUMP_TAGLIB_TARGET TagLib::taglib)
else()
  set(NCMDUMP_TAGLIB_TARGET ${TagLib_LIBRARIES})
endif()
target_link_libraries(ncmdump_exec PRIVATE ${NCMDUMP_TAGLIB_TARGET})
target_link_libraries(ncmdump_exec PRIVATE z)

# 输出名必须是 lib*.so，这样 Android 才会把它当成 native library 解压到
# applicationInfo.nativeLibraryDir —— 那是少数几个在 Android 10+ 上仍然允许 exec() 的目录。
set_target_properties(ncmdump_exec PROPERTIES
  OUTPUT_NAME "ncmdump"
  PREFIX "lib"
  SUFFIX ".so")

if(ANDROID)
  # Android 15+ 的 16 KB page size 要求
  target_link_options(ncmdump_exec PRIVATE "-Wl,-z,max-page-size=16384")
endif()
