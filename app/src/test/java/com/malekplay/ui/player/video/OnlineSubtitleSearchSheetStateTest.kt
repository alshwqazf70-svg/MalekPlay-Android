package com.malekplay.ui.player.video

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.malekplay.R
import com.malekplay.network.OnlineSubtitle
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class OnlineSubtitleSearchSheetStateTest {

    private val subtitle = OnlineSubtitle(
        fileId = 7L,
        language = "en",
        release = "Movie.2025",
        downloads = 100,
        format = "srt",
        sourceId = "subtitle-7",
        provider = "subdl"
    )

    @Test
    fun primaryActionUsesDownloadAndApplyResourcesAndAccurateLoadingState() {
        val idle = resolveSubtitleCardActionState(
            subtitle = subtitle,
            loading = false,
            downloadingKey = null,
            previewKey = null,
            previewLoading = false,
            previewLines = emptyList()
        )
        assertEquals(R.string.subtitle_download_and_apply, idle.primaryLabelRes)
        assertTrue(idle.buttonsEnabled)

        val loading = resolveSubtitleCardActionState(
            subtitle = subtitle,
            loading = true,
            downloadingKey = "subdl:subtitle-7",
            previewKey = null,
            previewLoading = false,
            previewLines = emptyList()
        )
        assertEquals(R.string.subtitle_downloading_and_applying, loading.primaryLabelRes)
        assertFalse(loading.buttonsEnabled)
        assertEquals(
            R.string.subtitle_downloading_and_applying_status,
            resolveSubtitleFooterStatusRes(
                loading = true,
                downloadingKey = "subdl:subtitle-7",
                translationLoading = false
            )
        )
    }

    @Test
    fun previewAndAiActionsResolveDistinctLocalizedResources() {
        val preview = resolveSubtitleCardActionState(
            subtitle = subtitle,
            loading = false,
            downloadingKey = null,
            previewKey = "subdl:subtitle-7",
            previewLoading = true,
            previewLines = emptyList()
        )
        assertEquals(R.string.subtitle_preview_loading_button, preview.previewLabelRes)
        assertFalse(preview.previewEnabled)

        val previewReady = resolveSubtitleCardActionState(
            subtitle = subtitle,
            loading = false,
            downloadingKey = null,
            previewKey = "subdl:subtitle-7",
            previewLoading = false,
            previewLines = listOf("A preview line")
        )
        assertEquals(R.string.subtitle_hide_preview_button, previewReady.previewLabelRes)

        val aiLoading = resolveSubtitleCardActionState(
            subtitle = subtitle,
            loading = true,
            downloadingKey = "subdl:subtitle-7:ai",
            previewKey = null,
            previewLoading = false,
            previewLines = emptyList()
        )
        assertEquals(R.string.subtitle_downloading_and_translating, aiLoading.aiLabelRes)
        assertEquals(
            R.string.subtitle_downloading_and_translating,
            resolveSubtitleFooterStatusRes(
                loading = true,
                downloadingKey = "subdl:subtitle-7:ai",
                translationLoading = false
            )
        )
        assertNull(
            resolveSubtitleCardActionState(
                subtitle = subtitle.copy(language = "ar"),
                loading = false,
                downloadingKey = null,
                previewKey = null,
                previewLoading = false,
                previewLines = emptyList()
            ).aiLabelRes
        )
    }

    @Test
    fun actionLabelsExistInEnglishAndArabicResources() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val english = base.createConfigurationContext(
            Configuration(base.resources.configuration).apply {
                setLocale(Locale.ENGLISH)
            }
        )
        val arabic = base.createConfigurationContext(
            Configuration(base.resources.configuration).apply {
                setLocale(Locale("ar"))
            }
        )

        assertEquals("Download & apply", english.getString(R.string.subtitle_download_and_apply))
        assertEquals("Downloading & applying…", english.getString(R.string.subtitle_downloading_and_applying))
        assertEquals("Preview", english.getString(R.string.subtitle_preview_button))
        assertEquals("Hide preview", english.getString(R.string.subtitle_hide_preview_button))
        assertEquals("Download & translate to Arabic (AI)", english.getString(R.string.subtitle_download_and_translate_ai))

        assertEquals("تنزيل وتطبيق", arabic.getString(R.string.subtitle_download_and_apply))
        assertEquals("جارٍ التنزيل والتطبيق…", arabic.getString(R.string.subtitle_downloading_and_applying))
        assertEquals("معاينة", arabic.getString(R.string.subtitle_preview_button))
        assertEquals("إخفاء المعاينة", arabic.getString(R.string.subtitle_hide_preview_button))
        assertEquals("تنزيل وترجمة للعربية (AI)", arabic.getString(R.string.subtitle_download_and_translate_ai))
    }
}
