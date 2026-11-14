package com.malekplay.network

/** Rejects late native callbacks after a DFile session has been replaced or closed. */
internal object UltraShareDFileSessionGuard {
    fun isCurrentSession(currentSessionId: Int?, callbackSessionId: Int): Boolean =
        currentSessionId != null && currentSessionId > 0 && callbackSessionId > 0 &&
            currentSessionId == callbackSessionId
}
