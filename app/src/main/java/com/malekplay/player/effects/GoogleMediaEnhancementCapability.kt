package com.malekplay.player.effects

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.google.android.gms.media.effect.enhancement.Enhancement

/**
 * Performs a non-invasive capability probe for Google's on-device video enhancement service.
 *
 * This deliberately does not create a session or change ExoPlayer's output surface.
 * Surface routing is a separate integration step that must preserve subtitles, lifecycle,
 * seek behavior and the existing Media3 effect chain.
 */
object GoogleMediaEnhancementCapability {

    enum class Status {
        ANDROID_VERSION_UNSUPPORTED,
        DEVICE_UNSUPPORTED,
        MODULE_INSTALL_REQUIRED,
        READY,
        CHECK_FAILED
    }

    fun inspect(context: Context, callback: (Status) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback(Status.ANDROID_VERSION_UNSUPPORTED)
            return
        }

        inspectApi30Plus(context.applicationContext, callback)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun inspectApi30Plus(context: Context, callback: (Status) -> Unit) {
        val client = try {
            Enhancement.getClient(context)
        } catch (_: Throwable) {
            callback(Status.CHECK_FAILED)
            return
        }

        try {
            client.isDeviceSupported().addOnCompleteListener { supportTask ->
                if (!supportTask.isSuccessful) {
                    callback(Status.CHECK_FAILED)
                } else if (supportTask.result != true) {
                    callback(Status.DEVICE_UNSUPPORTED)
                } else {
                    client.isModuleInstalled().addOnCompleteListener { moduleTask ->
                        callback(
                            when {
                                !moduleTask.isSuccessful -> Status.CHECK_FAILED
                                moduleTask.result == true -> Status.READY
                                else -> Status.MODULE_INSTALL_REQUIRED
                            }
                        )
                    }
                }
            }
        } catch (_: Throwable) {
            callback(Status.CHECK_FAILED)
        }
    }
}
