cmake_minimum_required(VERSION 3.22.1)
project(malekplayer_nstackx LANGUAGES C CXX)

if(NOT ANDROID)
    message(FATAL_ERROR "NSTACKX Android integration must be configured with the Android NDK toolchain")
endif()

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT CMAKE_ANDROID_ARCH_ABI MATCHES "^(arm64-v8a|armeabi-v7a|x86|x86_64)$")
    message(FATAL_ERROR "Unsupported NSTACKX ABI: ${CMAKE_ANDROID_ARCH_ABI}")
endif()

set(NSTACKX_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../third_party/nstackx/nstackx")
set(NSTACKX_UTIL "${NSTACKX_ROOT}/nstackx_util")
set(NSTACKX_CTRL "${NSTACKX_ROOT}/nstackx_ctrl")
set(NSTACKX_DFILE "${NSTACKX_ROOT}/nstackx_core/dfile")
set(NSTACKX_CONGESTION "${NSTACKX_ROOT}/nstackx_congestion")
set(NSTACKX_DEPS "${CMAKE_CURRENT_LIST_DIR}/../../third_party/nstackx")

foreach(required_path
    "${NSTACKX_ROOT}/t2stack.gni"
    "${NSTACKX_CTRL}/BUILD.gn"
    "${NSTACKX_DFILE}/BUILD.gn"
    "${NSTACKX_DEPS}/libcoap/CMakeLists.txt"
    "${NSTACKX_DEPS}/cjson/cJSON.c"
    "${NSTACKX_DEPS}/securec/src/memcpy_s.c"
    "${NSTACKX_DEPS}/mbedtls/CMakeLists.txt")
    if(NOT EXISTS "${required_path}")
        message(FATAL_ERROR "Pinned NSTACKX source is missing: ${required_path}. Run scripts/prepare_nstackx_sources.sh first.")
    endif()
endforeach()

# Build only the required dependency libraries. Upstream examples/tests/docs are disabled.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(ENABLE_DTLS OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTS OFF CACHE BOOL "" FORCE)
set(ENABLE_EXAMPLES OFF CACHE BOOL "" FORCE)
set(ENABLE_DOCS OFF CACHE BOOL "" FORCE)
set(ENABLE_OSCORE OFF CACHE BOOL "" FORCE)
set(ENABLE_PROXY_CODE OFF CACHE BOOL "" FORCE)
set(ENABLE_WS OFF CACHE BOOL "" FORCE)
set(ENABLE_TCP OFF CACHE BOOL "" FORCE)
set(ENABLE_AF_UNIX OFF CACHE BOOL "" FORCE)
set(ENABLE_CJSON_TEST OFF CACHE BOOL "" FORCE)
set(ENABLE_CJSON_UNINSTALL OFF CACHE BOOL "" FORCE)
set(ENABLE_TARGET_EXPORT OFF CACHE BOOL "" FORCE)
set(ENABLE_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
set(DISABLE_PACKAGE_CONFIG_AND_INSTALL ON CACHE BOOL "" FORCE)
set(MBEDTLS_FATAL_WARNINGS OFF CACHE BOOL "" FORCE)

add_subdirectory("${NSTACKX_DEPS}/libcoap" "${CMAKE_CURRENT_BINARY_DIR}/libcoap" EXCLUDE_FROM_ALL)
add_subdirectory("${NSTACKX_DEPS}/mbedtls" "${CMAKE_CURRENT_BINARY_DIR}/mbedtls" EXCLUDE_FROM_ALL)

add_library(nstackx_cjson STATIC "${NSTACKX_DEPS}/cjson/cJSON.c")
target_include_directories(nstackx_cjson PUBLIC "${NSTACKX_DEPS}/cjson")
set_target_properties(nstackx_cjson PROPERTIES OUTPUT_NAME nstackx_cjson)

# securec source list mirrors OpenHarmony's pinned libsec_src.gni.
set(SECUREC_SOURCES
    fscanf_s.c fwscanf_s.c gets_s.c memcpy_s.c memmove_s.c memset_s.c
    scanf_s.c securecutil.c secureinput_a.c secureinput_w.c
    secureprintoutput_a.c secureprintoutput_w.c snprintf_s.c sprintf_s.c
    sscanf_s.c strcat_s.c strcpy_s.c strncat_s.c strncpy_s.c strtok_s.c
    swprintf_s.c swscanf_s.c vfscanf_s.c vfwscanf_s.c vscanf_s.c
    vsnprintf_s.c vsprintf_s.c vsscanf_s.c vswprintf_s.c vswscanf_s.c
    vwscanf_s.c wcscat_s.c wcscpy_s.c wcsncat_s.c wcsncpy_s.c wcstok_s.c
    wmemcpy_s.c wmemmove_s.c wscanf_s.c)
list(TRANSFORM SECUREC_SOURCES PREPEND "${NSTACKX_DEPS}/securec/src/")
add_library(nstackx_securec STATIC ${SECUREC_SOURCES})
target_include_directories(nstackx_securec PUBLIC "${NSTACKX_DEPS}/securec/include")

set(NSTACKX_UTIL_SOURCES
    "${NSTACKX_UTIL}/core/nstackx_dev.c"
    "${NSTACKX_UTIL}/core/nstackx_event.c"
    "${NSTACKX_UTIL}/core/nstackx_getopt.c"
    "${NSTACKX_UTIL}/core/nstackx_log.c"
    "${NSTACKX_UTIL}/core/nstackx_mbedtls.c"
    "${NSTACKX_UTIL}/core/nstackx_socket.c"
    "${NSTACKX_UTIL}/core/nstackx_timer.c"
    "${NSTACKX_UTIL}/core/nstackx_util.c"
    "${CMAKE_CURRENT_LIST_DIR}/android/sys_dev.c"
    "${CMAKE_CURRENT_LIST_DIR}/android/sys_network.c"
    "${NSTACKX_UTIL}/platform/unix/sys_epoll.c"
    "${NSTACKX_UTIL}/platform/unix/sys_event.c"
    "${CMAKE_CURRENT_LIST_DIR}/android/sys_log.c"
    "${CMAKE_CURRENT_LIST_DIR}/android/sys_socket.c"
    "${NSTACKX_UTIL}/platform/unix/sys_timer.c"
    "${CMAKE_CURRENT_LIST_DIR}/android/sys_util.c")

add_library(nstackx_util STATIC ${NSTACKX_UTIL_SOURCES})
target_include_directories(nstackx_util PUBLIC
    "${NSTACKX_UTIL}/include"
    "${NSTACKX_UTIL}/interface"
    "${NSTACKX_UTIL}/platform/unix"
    "${NSTACKX_ROOT}/nstackx_core")
target_compile_definitions(nstackx_util PUBLIC
    NSTACKX_WITH_LINUX MBEDTLS_INCLUDED ENABLE_USER_LOG)
target_link_libraries(nstackx_util PUBLIC nstackx_securec mbedtls mbedx509 mbedcrypto dl)

set(NSTACKX_DFINDER_SOURCES
    "${NSTACKX_CTRL}/core/json_payload.c"
    "${NSTACKX_CTRL}/core/nstackx_common.c"
    "${NSTACKX_CTRL}/core/nstackx_device.c"
    "${NSTACKX_CTRL}/core/nstackx_device_local.c"
    "${NSTACKX_CTRL}/core/nstackx_device_remote.c"
    "${NSTACKX_CTRL}/core/nstackx_dfinder_log.c"
    "${NSTACKX_CTRL}/core/nstackx_inet.c"
    "${NSTACKX_CTRL}/core/coap_discover/coap_app.c"
    "${NSTACKX_CTRL}/core/coap_discover/coap_client.c"
    "${NSTACKX_CTRL}/core/coap_discover/coap_discover.c"
    "${NSTACKX_CTRL}/core/nstackx_dfinder_hidump.c"
    "${NSTACKX_CTRL}/core/nstackx_dfinder_mgt_msg_log.c"
    "${NSTACKX_CTRL}/core/nstackx_dfinder_hievent.c"
    "${NSTACKX_CTRL}/core/nstackx_statistics.c"
    "${NSTACKX_CTRL}/core/nstackx_smartgenius.c")

add_library(nstackx_dfinder STATIC ${NSTACKX_DFINDER_SOURCES})
target_include_directories(nstackx_dfinder PUBLIC
    "${NSTACKX_DEPS}/securec/include"
    "${NSTACKX_CTRL}/interface"
    "${NSTACKX_CTRL}/include"
    "${NSTACKX_CTRL}/include/coap_discover"
    "${NSTACKX_UTIL}/interface"
    "${NSTACKX_UTIL}/platform/unix")
target_compile_definitions(nstackx_dfinder PUBLIC
    NSTACKX_EXTEND_BUSINESSDATA DFINDER_SAVE_DEVICE_LIST
    DFINDER_SUPPORT_SET_SCREEN_STATUS
    DFINDER_DISTINGUISH_ACTIVE_PASSIVE_DISCOVERY
    DFINDER_MGT_MSG_LOG NSTACKX_DFINDER_HIDUMP)
target_link_libraries(nstackx_dfinder PUBLIC
    nstackx_util nstackx_cjson nstackx_securec coap-3)

set(NSTACKX_CONGESTION_SOURCES
    "${NSTACKX_CONGESTION}/core/nstackx_congestion.c"
    "${NSTACKX_CONGESTION}/platform/unix/sys_congestion.c")
add_library(nstackx_congestion STATIC ${NSTACKX_CONGESTION_SOURCES})
target_include_directories(nstackx_congestion PUBLIC
    "${NSTACKX_CONGESTION}/interface"
    "${NSTACKX_CONGESTION}/platform/unix"
    "${NSTACKX_UTIL}/interface"
    "${NSTACKX_UTIL}/platform/unix")
target_compile_definitions(nstackx_congestion PUBLIC NSTACKX_WITH_LINUX MBEDTLS_INCLUDED ENABLE_USER_LOG)
target_link_libraries(nstackx_congestion PUBLIC nstackx_util nstackx_securec)

set(NSTACKX_DFILE_SOURCES
    "${NSTACKX_DFILE}/core/nstackx_dfile.c"
    "${NSTACKX_DFILE}/core/nstackx_dfile_config.c"
    "${NSTACKX_DFILE}/core/nstackx_dfile_control.c"
    "${NSTACKX_DFILE}/core/nstackx_dfile_dfx.c"
    "${NSTACKX_DFILE}/core/nstackx_dfile_frame.c"
    "${NSTACKX_DFILE}/core/nstackx_dfile_log.c"
    "${NSTACKX_DFILE}/core/nstackx_dfile_mp.c"
    "${NSTACKX_DFILE}/core/nstackx_dfile_retransmission.c"
    "${NSTACKX_DFILE}/core/nstackx_dfile_send.c"
    "${NSTACKX_DFILE}/core/nstackx_dfile_session.c"
    "${NSTACKX_DFILE}/core/nstackx_dfile_transfer.c"
    "${NSTACKX_DFILE}/core/nstackx_file_list.c"
    "${NSTACKX_DFILE}/core/nstackx_file_manager.c"
    "${NSTACKX_DFILE}/core/nstackx_file_manager_client.c"
    "${NSTACKX_ROOT}/nstackx_core/platform/unix/dfile/sys_dfile.c"
    "${NSTACKX_ROOT}/nstackx_core/platform/unix/dfile/sys_dfile_session.c"
    "${NSTACKX_ROOT}/nstackx_core/platform/unix/dfile/sys_file_manager.c")

add_library(nstackx_dfile STATIC ${NSTACKX_DFILE_SOURCES})
target_include_directories(nstackx_dfile PUBLIC
    "${NSTACKX_DFILE}/include"
    "${NSTACKX_DFILE}/interface"
    "${NSTACKX_ROOT}/nstackx_core"
    "${NSTACKX_CONGESTION}/interface"
    "${NSTACKX_UTIL}/interface"
    "${NSTACKX_UTIL}/platform/unix")
target_compile_definitions(nstackx_dfile PUBLIC
    NSTACKX_WITH_LINUX MBEDTLS_INCLUDED ENABLE_USER_LOG DFILE_ENABLE_HIDUMP)
target_link_libraries(nstackx_dfile PUBLIC
    nstackx_congestion nstackx_util nstackx_securec mbedtls mbedx509 mbedcrypto)

# Package the real DFinder + DFile static modules behind the JNI lifecycle/transfer bridge.

add_library(nstackx_engine SHARED
    "${CMAKE_CURRENT_LIST_DIR}/nstackx_engine_entry.c"
    "${CMAKE_CURRENT_LIST_DIR}/android/nstackx_jni.c")
target_include_directories(nstackx_engine PRIVATE
    "${NSTACKX_CTRL}/interface"
    "${NSTACKX_CTRL}/include"
    "${NSTACKX_UTIL}/interface"
    "${NSTACKX_UTIL}/include")
target_compile_definitions(nstackx_engine PRIVATE
    NSTACKX_ANDROID_NSTACKX_REVISION="fbf280db18092eb00bb065e59ce6676c7b85b8c9")
target_link_libraries(nstackx_engine PRIVATE
    "-Wl,--whole-archive"
    nstackx_dfinder nstackx_dfile nstackx_congestion nstackx_util nstackx_securec
    "-Wl,--no-whole-archive"
    coap-3 mbedtls mbedx509 mbedcrypto log android)
