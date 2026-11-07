cmake_minimum_required(VERSION 3.22)
project(tilecast_rtmp_client LANGUAGES C)

add_library(rtmp-jni SHARED
    src/main/cpp/rtmp-log-policy.c
    src/main/cpp/rtmpmuxer.c
    src/main/cpp/librtmp-jni.c
    src/main/cpp/librtmp/amf.c
    src/main/cpp/librtmp/hashswf.c
    src/main/cpp/librtmp/log.c
    src/main/cpp/librtmp/parseurl.c
    src/main/cpp/librtmp/rtmp.c
    src/main/cpp/flvmuxer/xiecc_rtmp.c
)
target_include_directories(rtmp-jni PRIVATE src/main/cpp/librtmp)
# Match the upstream client: this adapter handles rtmp, not rtmps.
target_compile_definitions(rtmp-jni PRIVATE NO_CRYPTO)
# Rebuild all ABIs; do not ship the Maven 3.2.0 binary's 4 KB LOAD segments.
target_link_options(rtmp-jni PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
find_library(android_log log REQUIRED)
target_link_libraries(rtmp-jni PRIVATE ${android_log})
