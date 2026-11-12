/*
 * Android/Bionic socket-to-Network binding for NSTACKX.
 * Android API 23+: android_setsocknetwork() applies routing to this socket only.
 */
#include <errno.h>
#include <stdint.h>
#include <android/multinetwork.h>
#include <sys/socket.h>
#include <pthread.h>

#include "sys_network.h"

static pthread_mutex_t g_networkLock = PTHREAD_MUTEX_INITIALIZER;
static uint64_t g_networkHandle = 0;

void NstackxSetNetworkHandle(uint64_t networkHandle)
{
    pthread_mutex_lock(&g_networkLock);
    g_networkHandle = networkHandle;
    pthread_mutex_unlock(&g_networkLock);
}

int NstackxBindSocketToCurrentNetwork(int socketFd)
{
    uint64_t handle;
    pthread_mutex_lock(&g_networkLock);
    handle = g_networkHandle;
    pthread_mutex_unlock(&g_networkLock);
    if (handle == 0) {
        errno = ENONET;
        return -1;
    }
    return NstackxBindSocketToNetwork(socketFd, handle);
}

int NstackxBindSocketToNetwork(int socketFd, uint64_t networkHandle)
{
    if (socketFd < 0 || networkHandle == 0) {
        errno = EINVAL;
        return -1;
    }

    /*
     * Network.getNetworkHandle() returns the handle accepted by the NDK
     * android_setsocknetwork API. Binding is per-socket, so WLAN and Wi-Fi
     * Direct sockets can coexist without changing the process default route.
     */
    return android_setsocknetwork((net_handle_t)networkHandle, socketFd);
}
