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
#include <stdint.h>
#include <string.h>

#include "nstackx.h"
#include "nstackx_struct.h"

#define TAG "MalekPlayerNSTACKX"
#define MAX_LOCAL_TEXT 96

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static JavaVM *g_vm = NULL;
static jclass g_bridge_class = NULL;
static jmethodID g_on_device_found = NULL;
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
    int attached = 0;
    uint32_t i;
    if (devices == NULL || count == 0 || g_vm == NULL || g_bridge_class == NULL ||
        g_on_device_found == NULL) {
        return;
    }

    jint status = (*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) return;
        attached = 1;
    } else if (status != JNI_OK) {
        return;
    }

    for (i = 0; i < count; ++i) {
        jstring id = (*env)->NewStringUTF(env, devices[i].deviceId);
        jstring name = (*env)->NewStringUTF(env, devices[i].deviceName);
        jstring iface = (*env)->NewStringUTF(env, devices[i].networkName);
        if (id != NULL && name != NULL && iface != NULL) {
            (*env)->CallStaticVoidMethod(env, g_bridge_class, g_on_device_found, id, name, iface);
        }
        if ((*env)->ExceptionCheck(env)) {
            /* A UI listener must not poison NSTACKX's native worker thread. */
            (*env)->ExceptionClear(env);
        }
        if (id != NULL) (*env)->DeleteLocalRef(env, id);
        if (name != NULL) (*env)->DeleteLocalRef(env, name);
        if (iface != NULL) (*env)->DeleteLocalRef(env, iface);
    }
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
    JNIEnv *env, jclass clazz, jstring deviceName, jstring deviceId,
    jstring interfaceName, jstring ipv4Address)
{
    const char *name = NULL, *id = NULL, *iface = NULL, *ip = NULL;
    NSTACKX_Parameter parameter;
    NSTACKX_LocalDeviceInfo local;
    int32_t result = -1;
    jclass globalClass = NULL;

    if (deviceName == NULL || deviceId == NULL || interfaceName == NULL || ipv4Address == NULL)
        return -1001;

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

    globalClass = (*env)->NewGlobalRef(env, clazz);
    if (globalClass == NULL) {
        pthread_mutex_unlock(&g_lock);
        result = -1003;
        goto CLEANUP;
    }
    g_on_device_found = (*env)->GetStaticMethodID(
        env, clazz, "onNativeDeviceFound",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    if (g_on_device_found == NULL) {
        (*env)->DeleteGlobalRef(env, globalClass);
        globalClass = NULL;
        pthread_mutex_unlock(&g_lock);
        result = -1004;
        goto CLEANUP;
    }
    g_bridge_class = globalClass;
    globalClass = NULL;

    result = NSTACKX_Init(&parameter);
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
    }
    pthread_mutex_unlock(&g_lock);

CLEANUP:
    if (globalClass != NULL) (*env)->DeleteGlobalRef(env, globalClass);
    ReleaseUtf(env, deviceName, name);
    ReleaseUtf(env, deviceId, id);
    ReleaseUtf(env, interfaceName, iface);
    ReleaseUtf(env, ipv4Address, ip);
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
    (void)clazz;
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
    pthread_mutex_unlock(&g_lock);
}
