/*
 * Copyright (c) 2021-2023 Huawei Device Co., Ltd.
 * Licensed under the Apache License, Version 2.0.
 *
 * MalekPlayer JNI lifecycle bridge for the pinned NSTACKX DFinder API.
 * Discovery callbacks expose peer identity only. They are NOT authentication
 * and must never authorize file transfer; the existing QR/PIN handshake does.
 */
#include <jni.h>
#include <pthread.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <stdlib.h>
#include <stdio.h>
#include <stdint.h>
#include <string.h>

#include "nstackx.h"
#include "nstackx_struct.h"
#include "nstackx_dfile.h"
#include "sys_network.h"

#define TAG "MalekPlayerNSTACKX"
#define MAX_LOCAL_TEXT 96

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static JavaVM *g_vm = NULL;
static jclass g_bridge_class = NULL;
static jmethodID g_on_device_found = NULL;
static jmethodID g_on_dfile_event = NULL;
#define DFILE_SESSION_CAPACITY 32
static int32_t g_dfile_sessions[DFILE_SESSION_CAPACITY] = {0};
static int g_initialized = 0;
static int g_discovery_started = 0;

static void ReleaseUtf(JNIEnv *env, jstring value, const char *chars)
{
    if (value != NULL && chars != NULL) {
        (*env)->ReleaseStringUTFChars(env, value, chars);
    }
}

static void OnDeviceFound(const NSTACKX_DeviceInfo *devices, uint32_t count)
{
    JNIEnv *env = NULL;
    jclass callbackClass = NULL;
    jmethodID callbackMethod = NULL;
    int attached = 0;
    uint32_t i;
    if (devices == NULL || count == 0 || g_vm == NULL) return;

    jint status = (*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) return;
        attached = 1;
    } else if (status != JNI_OK) {
        return;
    }

    pthread_mutex_lock(&g_lock);
    if (g_bridge_class != NULL && g_on_device_found != NULL) {
        callbackClass = (jclass)(*env)->NewLocalRef(env, g_bridge_class);
        callbackMethod = g_on_device_found;
    }
    pthread_mutex_unlock(&g_lock);
    if (callbackClass == NULL || callbackMethod == NULL) {
        if (attached) (*g_vm)->DetachCurrentThread(g_vm);
        return;
    }

    for (i = 0; i < count; ++i) {
        jstring id = (*env)->NewStringUTF(env, devices[i].deviceId);
        jstring name = (*env)->NewStringUTF(env, devices[i].deviceName);
        jstring iface = (*env)->NewStringUTF(env, devices[i].networkName);
        if (id != NULL && name != NULL && iface != NULL) {
            (*env)->CallStaticVoidMethod(env, callbackClass, callbackMethod, id, name, iface);
        }
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        if (id != NULL) (*env)->DeleteLocalRef(env, id);
        if (name != NULL) (*env)->DeleteLocalRef(env, name);
        if (iface != NULL) (*env)->DeleteLocalRef(env, iface);
    }
    (*env)->DeleteLocalRef(env, callbackClass);
    if (attached) (*g_vm)->DetachCurrentThread(g_vm);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void)reserved;
    g_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jint JNICALL
Java_com_malekplay_network_NstackxNativeBridge_nativeInitialize(
    JNIEnv *env, jobject thiz, jstring deviceName, jstring deviceId,
    jstring interfaceName, jstring ipv4Address, jlong networkHandle)
{
    const char *name = NULL, *id = NULL, *iface = NULL, *ip = NULL;
    NSTACKX_Parameter parameter;
    NSTACKX_LocalDeviceInfo local;
    int32_t result = -1;
    jclass globalClass = NULL;
    jclass bridgeClass = NULL;

    if (deviceName == NULL || deviceId == NULL || interfaceName == NULL || ipv4Address == NULL || networkHandle <= 0)
        return -1001;

    bridgeClass = (*env)->GetObjectClass(env, thiz);
    if (bridgeClass == NULL) return -1003;
    name = (*env)->GetStringUTFChars(env, deviceName, NULL);
    id = (*env)->GetStringUTFChars(env, deviceId, NULL);
    iface = (*env)->GetStringUTFChars(env, interfaceName, NULL);
    ip = (*env)->GetStringUTFChars(env, ipv4Address, NULL);
    if (name == NULL || id == NULL || iface == NULL || ip == NULL) goto CLEANUP;
    if (strlen(name) == 0 || strlen(id) == 0 || strlen(iface) == 0 || strlen(ip) == 0 ||
        strlen(name) >= NSTACKX_MAX_DEVICE_NAME_LEN || strlen(id) >= NSTACKX_MAX_DEVICE_ID_LEN ||
        strlen(iface) >= NSTACKX_MAX_INTERFACE_NAME_LEN || strlen(ip) >= NSTACKX_MAX_IP_STRING_LEN) {
        result = -1002;
        goto CLEANUP;
    }

    pthread_mutex_lock(&g_lock);
    if (g_initialized) {
        result = 0;
        pthread_mutex_unlock(&g_lock);
        goto CLEANUP;
    }

    memset(&parameter, 0, sizeof(parameter));
    parameter.onDeviceFound = OnDeviceFound;
    parameter.onDeviceListChanged = OnDeviceFound;
    parameter.maxDeviceNum = 64;

    globalClass = (*env)->NewGlobalRef(env, bridgeClass);
    if (globalClass == NULL) {
        pthread_mutex_unlock(&g_lock);
        result = -1003;
        goto CLEANUP;
    }
    g_on_device_found = (*env)->GetStaticMethodID(
        env, bridgeClass, "onNativeDeviceFound",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    g_on_dfile_event = (*env)->GetStaticMethodID(
        env, bridgeClass, "onNativeDFileEvent", "(IIJJLjava/lang/String;)V");
    if (g_on_device_found == NULL || g_on_dfile_event == NULL) {
        (*env)->DeleteGlobalRef(env, globalClass);
        globalClass = NULL;
        pthread_mutex_unlock(&g_lock);
        result = -1004;
        goto CLEANUP;
    }
    g_bridge_class = globalClass;
    globalClass = NULL;

    NstackxSetNetworkHandle((uint64_t)networkHandle);
    result = NSTACKX_Init(&parameter);
    if (result == 0) result = NSTACKX_ThreadInit();
    if (result == 0) {
        memset(&local, 0, sizeof(local));
        (void)strncpy(local.name, name, sizeof(local.name) - 1);
        (void)strncpy(local.deviceId, id, sizeof(local.deviceId) - 1);
        local.ifNums = 1;
        (void)strncpy(local.localIfInfo[0].networkName, iface,
            sizeof(local.localIfInfo[0].networkName) - 1);
        (void)strncpy(local.localIfInfo[0].networkIpAddr, ip,
            sizeof(local.localIfInfo[0].networkIpAddr) - 1);
        (void)strncpy(local.networkName, iface, sizeof(local.networkName) - 1);
        (void)strncpy(local.networkIpAddr, ip, sizeof(local.networkIpAddr) - 1);
        result = NSTACKX_RegisterDevice(&local);
    }
    if (result == 0) {
        g_initialized = 1;
    } else {
        NSTACKX_Deinit();
        if (g_bridge_class != NULL) (*env)->DeleteGlobalRef(env, g_bridge_class);
        g_bridge_class = NULL;
        g_on_device_found = NULL;
        g_on_dfile_event = NULL;
        NstackxSetNetworkHandle(0);
    }
    pthread_mutex_unlock(&g_lock);

CLEANUP:
    if (globalClass != NULL) (*env)->DeleteGlobalRef(env, globalClass);
    ReleaseUtf(env, deviceName, name);
    ReleaseUtf(env, deviceId, id);
    ReleaseUtf(env, interfaceName, iface);
    ReleaseUtf(env, ipv4Address, ip);
    if (bridgeClass != NULL) (*env)->DeleteLocalRef(env, bridgeClass);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_malekplay_network_NstackxNativeBridge_nativeStartDiscovery(JNIEnv *env, jclass clazz)
{
    NSTACKX_DiscoverySettings settings;
    int32_t result;
    (void)env;
    (void)clazz;
    pthread_mutex_lock(&g_lock);
    if (!g_initialized) {
        pthread_mutex_unlock(&g_lock);
        return -1005;
    }
    memset(&settings, 0, sizeof(settings));
    settings.businessType = NSTACKX_BUSINESS_TYPE_NULL;
    settings.discoveryMode = DISCOVER_MODE;
    settings.advertiseCount = 12;
    settings.advertiseDuration = 5000;
    result = NSTACKX_StartDeviceDiscovery(&settings);
    if (result == 0) g_discovery_started = 1;
    pthread_mutex_unlock(&g_lock);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_malekplay_network_NstackxNativeBridge_nativeStopDiscovery(JNIEnv *env, jclass clazz)
{
    int32_t result = 0;
    (void)env;
    (void)clazz;
    pthread_mutex_lock(&g_lock);
    if (g_initialized && g_discovery_started) {
        result = NSTACKX_StopDeviceFind();
        if (result == 0) g_discovery_started = 0;
    }
    pthread_mutex_unlock(&g_lock);
    return result;
}

JNIEXPORT void JNICALL
Java_com_malekplay_network_NstackxNativeBridge_nativeRelease(JNIEnv *env, jclass clazz)
{
    int32_t sessions[DFILE_SESSION_CAPACITY];
    uint32_t i;
    (void)clazz;
    memset(sessions, 0, sizeof(sessions));

    /* Close DFile sessions without holding g_lock: their worker callbacks may
     * re-enter JNI and must be allowed to acquire the callback reference. */
    pthread_mutex_lock(&g_lock);
    for (i = 0; i < DFILE_SESSION_CAPACITY; ++i) {
        sessions[i] = g_dfile_sessions[i];
        g_dfile_sessions[i] = 0;
    }
    pthread_mutex_unlock(&g_lock);
    for (i = 0; i < DFILE_SESSION_CAPACITY; ++i) {
        if (sessions[i] > 0) NSTACKX_DFileClose(sessions[i]);
    }

    pthread_mutex_lock(&g_lock);
    if (g_initialized) {
        if (g_discovery_started) (void)NSTACKX_StopDeviceFind();
        NSTACKX_Deinit();
        g_initialized = 0;
        g_discovery_started = 0;
    }
    if (g_bridge_class != NULL) (*env)->DeleteGlobalRef(env, g_bridge_class);
    g_bridge_class = NULL;
    g_on_device_found = NULL;
    g_on_dfile_event = NULL;
    NstackxSetNetworkHandle(0);
    pthread_mutex_unlock(&g_lock);
}

/* DFile uses a 128-bit session key derived from the already-authenticated
 * Ultra Share pairing context. Never pass the QR/PIN itself to these methods. */
/*
 * Keep the native session registry authoritative. DFile session identifiers
 * are process-local handles; never forward an untracked/stale identifier back
 * into the upstream library, and do not consume two slots for one handle.
 */
static int RegisterDFileSession(int32_t sessionId)
{
    uint32_t i;
    int freeSlot = -1;
    int registered = 0;
    if (sessionId <= 0) return 0;

    pthread_mutex_lock(&g_lock);
    for (i = 0; i < DFILE_SESSION_CAPACITY; ++i) {
        if (g_dfile_sessions[i] == sessionId) {
            registered = 1;
            break;
        }
        if (g_dfile_sessions[i] == 0 && freeSlot < 0) {
            freeSlot = (int)i;
        }
    }
    if (!registered && freeSlot >= 0) {
        g_dfile_sessions[freeSlot] = sessionId;
        registered = 1;
    }
    pthread_mutex_unlock(&g_lock);
    return registered;
}

static int HasDFileSession(int32_t sessionId)
{
    uint32_t i;
    int found = 0;
    if (sessionId <= 0) return 0;
    pthread_mutex_lock(&g_lock);
    for (i = 0; i < DFILE_SESSION_CAPACITY; ++i) {
        if (g_dfile_sessions[i] == sessionId) {
            found = 1;
            break;
        }
    }
    pthread_mutex_unlock(&g_lock);
    return found;
}

static int ForgetDFileSession(int32_t sessionId)
{
    uint32_t i;
    int found = 0;
    pthread_mutex_lock(&g_lock);
    for (i = 0; i < DFILE_SESSION_CAPACITY; ++i) {
        if (g_dfile_sessions[i] == sessionId) {
            g_dfile_sessions[i] = 0;
            found = 1;
            break;
        }
    }
    pthread_mutex_unlock(&g_lock);
    return found;
}

static void OnDFileRename(DFileRenamePara *renamePara)
{
    const char *name;
    const char *dot;
    size_t prefixLength;
    const char *extension;
    unsigned int suffix;
    if (renamePara == NULL || renamePara->initFileName == NULL) return;
    name = strrchr(renamePara->initFileName, '/');
    name = (name == NULL) ? renamePara->initFileName : name + 1;
    dot = strrchr(name, '.');
    if (dot == NULL || dot == name) dot = name + strlen(name);
    prefixLength = (size_t)(dot - renamePara->initFileName);
    extension = dot;
    for (suffix = 1; suffix < 10000; ++suffix) {
        int written = snprintf(renamePara->newFileName, sizeof(renamePara->newFileName),
            "%.*s (%u)%s", (int)prefixLength, renamePara->initFileName, suffix, extension);
        if (written > 0 && (size_t)written < sizeof(renamePara->newFileName)) return;
    }
    renamePara->newFileName[0] = '\\0';
}

static void OnDFileMessage(int32_t sessionId, DFileMsgType msgType, const DFileMsg *msg)
{
    JNIEnv *env = NULL;
    jclass callbackClass = NULL;
    jmethodID callbackMethod = NULL;
    jstring message = NULL;
    int attached = 0;
    jlong transferred = 0;
    jlong total = 0;
    int errorCode = 0;
    char text[96];

    if (g_vm == NULL) return;
    jint status = (*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) return;
        attached = 1;
    } else if (status != JNI_OK) {
        return;
    }

    pthread_mutex_lock(&g_lock);
    if (g_bridge_class != NULL && g_on_dfile_event != NULL) {
        callbackClass = (jclass)(*env)->NewLocalRef(env, g_bridge_class);
        callbackMethod = g_on_dfile_event;
    }
    pthread_mutex_unlock(&g_lock);
    if (callbackClass == NULL || callbackMethod == NULL) {
        if (attached) (*g_vm)->DetachCurrentThread(g_vm);
        return;
    }

    if (msg != NULL) {
        errorCode = msg->errorCode;
        if (msgType == DFILE_ON_TRANS_IN_PROGRESS ||
            msgType == DFILE_ON_SESSION_IN_PROGRESS) {
            transferred = (jlong)msg->transferUpdate.bytesTransferred;
            total = (jlong)msg->transferUpdate.totalBytes;
        }
    }
    (void)snprintf(text, sizeof(text), "%s%d",
        errorCode == 0 ? "" : "error:", errorCode);
    message = (*env)->NewStringUTF(env, text);
    if (message != NULL) {
        (*env)->CallStaticVoidMethod(env, callbackClass, callbackMethod,
            (jint)sessionId, (jint)msgType, transferred, total, message);
        (*env)->DeleteLocalRef(env, message);
    }
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, callbackClass);
    if (attached) (*g_vm)->DetachCurrentThread(g_vm);
}

JNIEXPORT jint JNICALL
Java_com_malekplay_network_NstackxNativeBridge_nativeDFileStartServer(
    JNIEnv *env, jclass clazz, jstring localIpv4, jint port, jbyteArray key, jstring storagePath)
{
    const char *ip = NULL, *path = NULL;
    jbyte *keyBytes = NULL;
    jsize keyLength;
    struct sockaddr_in address;
    struct in_addr parsed;
    int32_t sessionId;
    int32_t result;
    (void)clazz;
    if (localIpv4 == NULL || storagePath == NULL || key == NULL ||
        port < 1 || port > 65535) return -1201;
    keyLength = (*env)->GetArrayLength(env, key);
    if (keyLength != 16) return -1202;
    ip = (*env)->GetStringUTFChars(env, localIpv4, NULL);
    path = (*env)->GetStringUTFChars(env, storagePath, NULL);
    keyBytes = (*env)->GetByteArrayElements(env, key, NULL);
    if (ip == NULL || path == NULL || keyBytes == NULL) {
        result = -1203;
        goto SERVER_CLEANUP;
    }
    if (inet_pton(AF_INET, ip, &parsed) != 1 || strlen(path) == 0 ||
        strlen(path) > NSTACKX_MAX_PATH_LEN) {
        result = -1204;
        goto SERVER_CLEANUP;
    }
    memset(&address, 0, sizeof(address));
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = ntohl(parsed.s_addr); /* DFile API expects host order. */
    address.sin_port = (uint16_t)port;

    sessionId = NSTACKX_DFileServer(&address, sizeof(address),
        (const uint8_t *)keyBytes, (uint32_t)keyLength, OnDFileMessage);
    if (sessionId <= 0) {
        result = sessionId;
        goto SERVER_CLEANUP;
    }
    if (!RegisterDFileSession(sessionId)) {
        NSTACKX_DFileClose(sessionId);
        result = -1205;
        goto SERVER_CLEANUP;
    }
    result = NSTACKX_DFileSetStoragePath(sessionId, path);
    if (result == 0) result = NSTACKX_DFileSetRenameHook(sessionId, OnDFileRename);
    if (result != 0) {
        ForgetDFileSession(sessionId);
        NSTACKX_DFileClose(sessionId);
    } else {
        result = sessionId;
    }

SERVER_CLEANUP:
    if (keyBytes != NULL) { memset(keyBytes, 0, (size_t)keyLength); (*env)->ReleaseByteArrayElements(env, key, keyBytes, JNI_ABORT); }
    ReleaseUtf(env, localIpv4, ip);
    ReleaseUtf(env, storagePath, path);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_malekplay_network_NstackxNativeBridge_nativeDFileStartClient(
    JNIEnv *env, jclass clazz, jstring remoteIpv4, jint port, jbyteArray key)
{
    const char *ip = NULL;
    jbyte *keyBytes = NULL;
    jsize keyLength;
    struct sockaddr_in address;
    struct in_addr parsed;
    int32_t sessionId;
    int32_t result;
    (void)clazz;
    if (remoteIpv4 == NULL || key == NULL || port < 1 || port > 65535) return -1211;
    keyLength = (*env)->GetArrayLength(env, key);
    if (keyLength != 16) return -1212;
    ip = (*env)->GetStringUTFChars(env, remoteIpv4, NULL);
    keyBytes = (*env)->GetByteArrayElements(env, key, NULL);
    if (ip == NULL || keyBytes == NULL) {
        result = -1213;
        goto CLIENT_CLEANUP;
    }
    if (inet_pton(AF_INET, ip, &parsed) != 1) {
        result = -1214;
        goto CLIENT_CLEANUP;
    }
    memset(&address, 0, sizeof(address));
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = ntohl(parsed.s_addr); /* DFile API expects host order. */
    address.sin_port = (uint16_t)port;
    sessionId = NSTACKX_DFileClient(&address, sizeof(address),
        (const uint8_t *)keyBytes, (uint32_t)keyLength, OnDFileMessage);
    if (sessionId <= 0) {
        result = sessionId;
    } else if (!RegisterDFileSession(sessionId)) {
        NSTACKX_DFileClose(sessionId);
        result = -1215;
    } else {
        result = sessionId;
    }

CLIENT_CLEANUP:
    if (keyBytes != NULL) { memset(keyBytes, 0, (size_t)keyLength); (*env)->ReleaseByteArrayElements(env, key, keyBytes, JNI_ABORT); }
    ReleaseUtf(env, remoteIpv4, ip);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_malekplay_network_NstackxNativeBridge_nativeDFileSendFiles(
    JNIEnv *env, jclass clazz, jint sessionId, jobjectArray filePaths)
{
    jsize count;
    char **paths;
    jsize i;
    int32_t result;
    (void)clazz;
    if (sessionId <= 0 || filePaths == NULL) return -1221;
    if (!HasDFileSession(sessionId)) return -1227;
    count = (*env)->GetArrayLength(env, filePaths);
    if (count <= 0 || count > NSTACKX_MAX_FILE_LIST_NUM) return -1222;
    paths = (char **)calloc((size_t)count, sizeof(char *));
    if (paths == NULL) return -1223;

    for (i = 0; i < count; ++i) {
        jstring path = (jstring)(*env)->GetObjectArrayElement(env, filePaths, i);
        const char *utfPath;
        size_t length;
        if (path == NULL) {
            result = -1224;
            goto SEND_CLEANUP;
        }
        utfPath = (*env)->GetStringUTFChars(env, path, NULL);
        if (utfPath == NULL) {
            (*env)->DeleteLocalRef(env, path);
            result = -1225;
            goto SEND_CLEANUP;
        }
        length = strlen(utfPath);
        paths[i] = (char *)malloc(length + 1);
        if (paths[i] != NULL) memcpy(paths[i], utfPath, length + 1);
        (*env)->ReleaseStringUTFChars(env, path, utfPath);
        (*env)->DeleteLocalRef(env, path);
        if (paths[i] == NULL) {
            result = -1226;
            goto SEND_CLEANUP;
        }
    }

    result = NSTACKX_DFileSendFiles(sessionId, (const char **)paths, (uint32_t)count, "MalekPlayer");

SEND_CLEANUP:
    for (i = 0; i < count; ++i) free(paths[i]);
    free(paths);
    return result;
}

JNIEXPORT void JNICALL
Java_com_malekplay_network_NstackxNativeBridge_nativeDFileCloseSession(
    JNIEnv *env, jclass clazz, jint sessionId)
{
    (void)env;
    (void)clazz;
    if (sessionId <= 0) return;
    /* Ignore duplicate/stale close requests after release or a prior callback. */
    if (!ForgetDFileSession(sessionId)) return;
    NSTACKX_DFileClose(sessionId);
}
