/*
 * Android Network binding adapter for NSTACKX.
 *
 * The caller supplies Network.getNetworkHandle() for the Android Network
 * selected by ConnectivityManager (API 23+). No interface-name guessing or
 * process-wide network changes are performed here.
 */
#ifndef MALEKPLAYER_NSTACKX_SYS_NETWORK_H
#define MALEKPLAYER_NSTACKX_SYS_NETWORK_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Bind one already-created socket to the selected Android Network.
 * Returns 0 on success, -1 on failure (errno is preserved from Bionic).
 * The caller must treat failure as fatal for that network-specific socket and
 * must not silently continue on a different interface.
 */
int NstackxBindSocketToNetwork(int socketFd, uint64_t networkHandle);

#ifdef __cplusplus
}
#endif

#endif
