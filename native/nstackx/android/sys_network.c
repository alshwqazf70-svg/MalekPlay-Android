/*
 * Android/Bionic socket-to-Network binding for NSTACKX.
 * Android API 23+: android_setsocknetwork() applies routing to this socket only.
 */
#include <errno.h>
#include <stdint.h>
#include <android/multinetwork.h>
#include <sys/socket.h>
#include <stdatomic.h>

#include "sys_network.h"

static atomic_uint_fast64_t g_networkHandle = ATOMIC_VAR_INIT(0);

void NstackxSetNetworkHandle(uint64_t networkHandle)
{
    atomic_store_explicit(&g_networkHandle, networkHandle, memory_order_release);
}

int NstackxBindSocketToCurrentNetwork(int socketFd)
{
    uint64_t handle = atomic_load_explicit(&g_networkHandle, memory_order_acquire);
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
