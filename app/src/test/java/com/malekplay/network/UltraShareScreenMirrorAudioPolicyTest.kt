package com.malekplay.network

import android.Manifest
import android.app.Application
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class UltraShareScreenMirrorAudioPolicyTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun systemAudioCaptureIsSupportedOnlyOnAndroid10AndAbove() {
        for (sdk in Build.VERSION_CODES.M..Build.VERSION_CODES.P) {
            assertFalse(
                "SDK $sdk must never advertise internal AudioPlaybackCapture",
                UltraShareScreenMirrorPolicy.supportsSystemAudioCapture(sdk)
            )
            assertEquals(
                "SDK $sdk must expose ONLY video-only and microphone modes",
                listOf(
                    UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE,
                    UltraShareScreenMirrorPolicy.MirrorAudioMode.MICROPHONE
                ),
                UltraShareScreenMirrorPolicy.availableAudioModes(sdk)
            )
        }
        for (sdk in Build.VERSION_CODES.Q..36) {
            assertTrue(
                "SDK $sdk must support optional AudioPlaybackCapture",
                UltraShareScreenMirrorPolicy.supportsSystemAudioCapture(sdk)
            )
            assertEquals(
                listOf(
                    UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE,
                    UltraShareScreenMirrorPolicy.MirrorAudioMode.SYSTEM_AUDIO,
                    UltraShareScreenMirrorPolicy.MirrorAudioMode.MICROPHONE
                ),
                UltraShareScreenMirrorPolicy.availableAudioModes(sdk)
            )
        }
    }

    @Test
    fun microphoneCaptureIsSupportedAcrossAndroid6Through16() {
        for (sdk in Build.VERSION_CODES.M..36) {
            assertTrue(UltraShareScreenMirrorPolicy.supportsMicrophoneAudioCapture(sdk))
        }
    }

    @Test
    fun effectiveAudioModeFallsBackToVideoOnlyWithoutBlockingVideoWhenUnsupportedOrDenied() {
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)

        // Android 6–9 requesting SYSTEM_AUDIO safely degrades to NONE (video-only):
        assertEquals(
            UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE,
            UltraShareScreenMirrorPolicy.resolveEffectiveAudioMode(
                context = context,
                requestedMode = UltraShareScreenMirrorPolicy.MirrorAudioMode.SYSTEM_AUDIO,
                sdkInt = Build.VERSION_CODES.M
            )
        )
        // Android 6–9 requesting MICROPHONE with RECORD_AUDIO granted -> MICROPHONE:
        assertEquals(
            UltraShareScreenMirrorPolicy.MirrorAudioMode.MICROPHONE,
            UltraShareScreenMirrorPolicy.resolveEffectiveAudioMode(
                context = context,
                requestedMode = UltraShareScreenMirrorPolicy.MirrorAudioMode.MICROPHONE,
                sdkInt = Build.VERSION_CODES.M
            )
        )
        // Android 10+ requesting SYSTEM_AUDIO with RECORD_AUDIO granted -> SYSTEM_AUDIO:
        assertEquals(
            UltraShareScreenMirrorPolicy.MirrorAudioMode.SYSTEM_AUDIO,
            UltraShareScreenMirrorPolicy.resolveEffectiveAudioMode(
                context = context,
                requestedMode = UltraShareScreenMirrorPolicy.MirrorAudioMode.SYSTEM_AUDIO,
                sdkInt = Build.VERSION_CODES.Q
            )
        )

        // Denying RECORD_AUDIO degrades both SYSTEM_AUDIO and MICROPHONE to NONE (video continues):
        shadowOf(context).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertEquals(
            UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE,
            UltraShareScreenMirrorPolicy.resolveEffectiveAudioMode(
                context = context,
                requestedMode = UltraShareScreenMirrorPolicy.MirrorAudioMode.MICROPHONE,
                sdkInt = Build.VERSION_CODES.M
            )
        )
        assertEquals(
            UltraShareScreenMirrorPolicy.MirrorAudioMode.NONE,
            UltraShareScreenMirrorPolicy.resolveEffectiveAudioMode(
                context = context,
                requestedMode = UltraShareScreenMirrorPolicy.MirrorAudioMode.SYSTEM_AUDIO,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            )
        )
    }

    @Test
    fun mediaProjectionForegroundServiceTypeRespectsSdkBoundaries() {
        assertEquals(
            0,
            UltraShareScreenMirrorPolicy.mediaProjectionForegroundServiceType(
                sdkInt = Build.VERSION_CODES.P,
                includeMicrophone = true
            )
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            UltraShareScreenMirrorPolicy.mediaProjectionForegroundServiceType(
                sdkInt = Build.VERSION_CODES.Q,
                includeMicrophone = true
            )
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            UltraShareScreenMirrorPolicy.mediaProjectionForegroundServiceType(
                sdkInt = Build.VERSION_CODES.R,
                includeMicrophone = true
            )
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            UltraShareScreenMirrorPolicy.mediaProjectionForegroundServiceType(
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                includeMicrophone = false
            )
        )
    }

    @Test
    fun upmixMonoPcmToStereoDuplicatesSamplesCleanlyForLegacyEncoders() {
        // Two 16-bit LE samples: 0x1234 and 0xABCD
        val mono = byteArrayOf(0x34, 0x12, 0xCD.toByte(), 0xAB.toByte())
        val stereo = upmixMono16BitToStereo(mono, mono.size)
        assertArrayEquals(
            byteArrayOf(
                0x34, 0x12, 0x34, 0x12,
                0xCD.toByte(), 0xAB.toByte(), 0xCD.toByte(), 0xAB.toByte()
            ),
            stereo
        )
    }

    @Test
    fun parsesAacAudioSpecificConfigAndSplitsAnnexBSpsPpsCleanly() {
        // AAC-LC (2), 48kHz index (3), 2 channels (2): 00010_0011_0010_000 = 0x11 0x90
        val asc = parseAacAudioSpecificConfig(byteArrayOf(0x11, 0x90.toByte()))
        assertNotNull(asc)
        assertEquals(48_000, asc!!.first)
        assertEquals(2, asc.second)

        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1F)
        val pps = byteArrayOf(0, 0, 0, 1, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        val combined = sps + pps
        val split = splitAvcCodecConfig(combined)
        assertNotNull(split)
        assertArrayEquals(sps, split!!.first)
        assertArrayEquals(pps, split.second)
    }
}
