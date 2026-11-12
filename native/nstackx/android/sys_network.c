/*
 * Android/Bionic socket-to-Network binding for NSTACKX.
 * Android API 23+: android_setsocknetwork() applies routing to this socket only.
 */
#include <errno.h>
#include <stdint.h>
#include <android/multinetwork.h>
#include <sys/socket.h>

#include "sys_network.h"

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
