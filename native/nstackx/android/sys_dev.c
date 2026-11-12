/*
 * Copyright (c) 2021 Huawei Device Co., Ltd.
 * Licensed under the Apache License, Version 2.0.
 * Android/Bionic adaptation for MalekPlayer.
 */
#include <ifaddrs.h>
#include <net/if.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <errno.h>
#include <string.h>
#include "nstackx_dev.h"
#include "nstackx_error.h"
#include "nstackx_log.h"
#include "securec.h"

#define TAG "NSTACKXAndroidDev"

static int IsUpIpv4(const struct ifaddrs *it)
{
    return it != NULL && it->ifa_addr != NULL && it->ifa_addr->sa_family == AF_INET &&
        (it->ifa_flags & IFF_UP) != 0;
}

static int IsSupportedName(const char *name)
{
    return name != NULL && (strncmp(name, "p2p", 3) == 0 ||
        strncmp(name, "wlan", 4) == 0 || strncmp(name, "eth", 3) == 0 ||
        strncmp(name, "rndis", 5) == 0);
}

static int FindIpv4Interface(uint32_t address, const char *requestedName,
    char *outName, size_t outNameLen, struct in_addr *netmask, struct in_addr *broadcast)
{
    struct ifaddrs *all = NULL;
    struct ifaddrs *it;
    int found = 0;
    if (getifaddrs(&all) != 0) {
        LOGE(TAG, "getifaddrs failed: %d", errno);
        return NSTACKX_EFAILED;
    }
    for (it = all; it != NULL; it = it->ifa_next) {
        struct sockaddr_in *local;
        if (!IsUpIpv4(it) || !IsSupportedName(it->ifa_name)) continue;
        local = (struct sockaddr_in *)it->ifa_addr;
        if (address != 0 && local->sin_addr.s_addr != address) continue;
        if (requestedName != NULL && strncmp(it->ifa_name, requestedName, IFNAMSIZ) != 0) continue;
        if (outName != NULL && strcpy_s(outName, outNameLen, it->ifa_name) != EOK) break;
        if (netmask != NULL && it->ifa_netmask != NULL)
            *netmask = ((struct sockaddr_in *)it->ifa_netmask)->sin_addr;
        if (broadcast != NULL && it->ifa_broadaddr != NULL)
            *broadcast = ((struct sockaddr_in *)it->ifa_broadaddr)->sin_addr;
        found = 1;
        break;
    }
    freeifaddrs(all);
    return found ? NSTACKX_EOK : NSTACKX_EFAILED;
}

int32_t GetInterfaceNameByIP(uint32_t sourceIp, char *interfaceName, size_t nameLen)
{
    if (interfaceName == NULL || nameLen == 0) return NSTACKX_EINVAL;
    return FindIpv4Interface(sourceIp, NULL, interfaceName, nameLen, NULL, NULL);
}

int32_t GetIfBroadcastIp(const char *ifName, char *ipString, size_t ipStringLen)
{
    struct in_addr broadcast = {0};
    if (ifName == NULL || ipString == NULL || ipStringLen == 0) return NSTACKX_EINVAL;
    if (FindIpv4Interface(0, ifName, NULL, 0, NULL, &broadcast) != NSTACKX_EOK)
        return NSTACKX_EFAILED;
    return inet_ntop(AF_INET, &broadcast, ipString, (socklen_t)ipStringLen) != NULL ?
        NSTACKX_EOK : NSTACKX_EFAILED;
}

/*
 * Android apps must not assume SO_BINDTODEVICE is permitted on every release/OEM.
 * Attempt it only for a verified interface; callers retain their existing route
 * selection/fallback when the kernel rejects the option.
 */
static int BindVerifiedInterface(SocketDesc fd, const char *name)
{
    if (fd < 0 || name == NULL || if_nametoindex(name) == 0) return NSTACKX_EFAILED;
#ifdef SO_BINDTODEVICE
    if (setsockopt(fd, SOL_SOCKET, SO_BINDTODEVICE, name, (socklen_t)(strlen(name) + 1)) == 0)
        return NSTACKX_EOK;
#endif
    LOGW(TAG, "SO_BINDTODEVICE unavailable for %s: %d; keep OS-selected route", name, errno);
    return NSTACKX_EFAILED;
}

int32_t BindToDevice(SocketDesc sockfd, const struct sockaddr_in *localAddr)
{
    char name[IFNAMSIZ] = {0};
    if (localAddr != NULL &&
        GetInterfaceNameByIP(localAddr->sin_addr.s_addr, name, sizeof(name)) != NSTACKX_EOK)
        return NSTACKX_EFAILED;
    if (localAddr == NULL) {
        /* No implicit interface preference: Android ConnectivityManager owns routing. */
        return NSTACKX_EOK;
    }
    return BindVerifiedInterface(sockfd, name);
}

int32_t BindToTargetDev(SocketDesc sockfd, const char *targetInterfaceName)
{
    if (targetInterfaceName == NULL ||
        FindIpv4Interface(0, targetInterfaceName, NULL, 0, NULL, NULL) != NSTACKX_EOK)
        return NSTACKX_EFAILED;
    return BindVerifiedInterface(sockfd, targetInterfaceName);
}

void BindToDevInTheSameLan(SocketDesc sockfd, const struct sockaddr_in *sockAddr)
{
    struct ifaddrs *all = NULL;
    struct ifaddrs *it;
    if (sockfd < 0 || sockAddr == NULL || getifaddrs(&all) != 0) return;
    for (it = all; it != NULL; it = it->ifa_next) {
        struct sockaddr_in *local;
        struct sockaddr_in *mask;
        if (!IsUpIpv4(it) || !IsSupportedName(it->ifa_name) || it->ifa_netmask == NULL) continue;
        local = (struct sockaddr_in *)it->ifa_addr;
        mask = (struct sockaddr_in *)it->ifa_netmask;
        if ((sockAddr->sin_addr.s_addr & mask->sin_addr.s_addr) ==
            (local->sin_addr.s_addr & mask->sin_addr.s_addr)) {
            (void)BindVerifiedInterface(sockfd, it->ifa_name);
            break;
        }
    }
    freeifaddrs(all);
}
