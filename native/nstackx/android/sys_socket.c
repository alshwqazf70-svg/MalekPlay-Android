/*
 * Copyright (c) 2021 Huawei Device Co., Ltd.
 * Licensed under the Apache License, Version 2.0.
 * Android/Bionic UDP adapter: portable datagram fallback (no UDP_SEGMENT dependency).
 */
#include <fcntl.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include "nstackx_socket.h"
#include "nstackx_log.h"
#include "nstackx_error.h"

#define TAG "NSTACKXAndroidSocket"

static int32_t g_gsoSupport = 0;

int32_t SupportGSO(void) { return g_gsoSupport; }
void SocketModuleClean(void) { g_gsoSupport = 0; }
int32_t SocketModuleInit(void) { g_gsoSupport = 0; return NSTACKX_EOK; }

int32_t SetSocketNonBlock(SocketDesc fd)
{
    int flags;
    if (fd < 0) return NSTACKX_EINVAL;
    flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) < 0) {
        LOGE(TAG, "failed to set nonblocking mode: %d", errno);
        return NSTACKX_EFAILED;
    }
    return NSTACKX_EOK;
}

/*
 * Preserve the upstream GSO contract while avoiding Linux UDP_SEGMENT cmsgs:
 * split the original iovec byte stream into ordinary UDP datagrams of <= MSS.
 * This is slower than GSO but works on Android kernels without UDP GSO support.
 */
int32_t SocketSendEx(const Socket *socket, uint16_t mss, const struct iovec *iov, uint32_t count)
{
    struct iovec *parts;
    size_t total = 0;
    size_t offset = 0;
    uint32_t i;
    int32_t result = NSTACKX_EFAILED;
    if (socket == NULL || socket->sockfd < 0 || socket->protocol != NSTACKX_PROTOCOL_UDP ||
        iov == NULL || count == 0 || mss == 0) return NSTACKX_EINVAL;

    for (i = 0; i < count; ++i) {
        if (iov[i].iov_len != 0 && iov[i].iov_base == NULL) return NSTACKX_EINVAL;
        if (iov[i].iov_len > SIZE_MAX - total) return NSTACKX_EFAILED;
        total += iov[i].iov_len;
    }
    if (total == 0 || total > INT32_MAX) return NSTACKX_EFAILED;
    parts = (struct iovec *)calloc(count, sizeof(struct iovec));
    if (parts == NULL) return NSTACKX_ENOMEM;

    while (offset < total) {
        size_t wanted = total - offset;
        size_t skipped = 0;
        size_t copied = 0;
        size_t packetBytes;
        struct msghdr message;
        ssize_t sent;
        if (wanted > mss) wanted = mss;
        /* Build the exact iovec slice for this UDP datagram. */
        {
            size_t partCount = 0;
            size_t baseOffset = offset;
            skipped = 0;
            copied = 0;
            for (i = 0; i < count && copied < wanted; ++i) {
                size_t begin, available, take;
                if (baseOffset >= skipped + iov[i].iov_len) {
                    skipped += iov[i].iov_len;
                    continue;
                }
                begin = baseOffset > skipped ? baseOffset - skipped : 0;
                available = iov[i].iov_len - begin;
                take = wanted - copied;
                if (take > available) take = available;
                parts[partCount].iov_base = (char *)iov[i].iov_base + begin;
                parts[partCount].iov_len = take;
                ++partCount;
                copied += take;
                skipped += iov[i].iov_len;
            }
            memset(&message, 0, sizeof(message));
            message.msg_name = (void *)&socket->dstAddr;
            message.msg_namelen = sizeof(socket->dstAddr);
            message.msg_iov = parts;
            message.msg_iovlen = partCount;
            sent = sendmsg(socket->sockfd, &message, 0);
            packetBytes = copied;
            if (sent < 0 || (size_t)sent != packetBytes) {
                LOGE(TAG, "UDP fallback send failed: sent=%ld expected=%zu errno=%d",
                    (long)sent, packetBytes, errno);
                result = (sent < 0) ? CheckSocketError() : NSTACKX_EFAILED;
                goto OUT;
            }
        }
        offset += wanted;
    }
    result = (int32_t)total;
OUT:
    free(parts);
    return result;
}

void CheckGSOSupport(void) { g_gsoSupport = 0; }
int32_t SocketOpInProgress(void) { return errno == EINPROGRESS; }
int32_t SocketOpWouldBlock(void) { return errno == EAGAIN || errno == EWOULDBLOCK; }
