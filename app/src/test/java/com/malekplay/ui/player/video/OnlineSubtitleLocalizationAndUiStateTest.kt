package com.malekplay.ui.player.video

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.malekplay.R
import com.malekplay.network.OnlineSubtitle
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class OnlineSubtitleLocalizationAndUiStateTest {

    private lateinit var appContext: Context

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun englishAndArabicResourcesHaveFullParityNoDuplicatesAndMatchingPlaceholders() {
        val enFile = resolveRepoFile("app/src/main/res/values/strings.xml", "src/main/res/values/strings.xml")
        val arFile = resolveRepoFile("app/src/main/res/values-ar/strings.xml", "src/main/res/values-ar/strings.xml")

        val enParsed = parseResources(enFile)
        val arParsed = parseResources(arFile)

        assertEquals("Duplicate <string> keys in values/strings.xml", emptyList<String>(), enParsed.duplicateStrings)
        assertEquals("Duplicate <string> keys in values-ar/strings.xml", emptyList<String>(), arParsed.duplicateStrings)
        assertEquals("Duplicate <plurals> keys in values/strings.xml", emptyList<String>(), enParsed.duplicatePlurals)
        assertEquals("Duplicate <plurals> keys in values-ar/strings.xml", emptyList<String>(), arParsed.duplicatePlurals)

        assertEquals(
            "Keys present in English but missing in Arabic",
            emptySet<String>(),
            enParsed.strings.keys - arParsed.strings.keys
        )
        assertEquals(
            "Keys present in Arabic but missing in English",
            emptySet<String>(),
            arParsed.strings.keys - enParsed.strings.keys
        )
        assertEquals(
            "Plurals present in English but missing in Arabic",
            emptySet<String>(),
            enParsed.plurals.keys - arParsed.plurals.keys
        )
        assertEquals(
            "Plurals present in Arabic but missing in English",
            emptySet<String>(),
            arParsed.plurals.keys - enParsed.plurals.keys
        )

        val placeholderRegex = Regex("""%\d+\$[sdfox]|\{\d+\}""")
        val mismatchedPlaceholders = enParsed.strings.keys.sorted().filter { key ->
            val enPlaceholders = placeholderRegex.findAll(enParsed.strings.getValue(key)).map { it.value }.sorted().toList()
            val arPlaceholders = placeholderRegex.findAll(arParsed.strings.getValue(key)).map { it.value }.sorted().toList()
            enPlaceholders != arPlaceholders
        }
        assertEquals("Placeholder mismatch between English and Arabic", emptyList<String>(), mismatchedPlaceholders)

        val emptyArabic = arParsed.strings.filterValues { it.isBlank() }.keys
        val emptyEnglish = enParsed.strings.filterValues { it.isBlank() }.keys
        assertEquals("Blank English strings", emptySet<String>(), emptyEnglish)
        assertEquals("Blank Arabic strings", emptySet<String>(), emptyArabic)
    }

    @Test
    fun subtitleSheetSourceHasNoHardcodedArabicOrForcedRtlDirection() {
        val sheetFile = resolveRepoFile(
            "app/src/main/java/com/malekplay/ui/player/video/OnlineSubtitleSearchSheet.kt",
            "src/main/java/com/malekplay/ui/player/video/OnlineSubtitleSearchSheet.kt"
        )
        val source = sheetFile.readText()
        val hasArabicCharacters = Regex("""[\u0600-\u06FF]""").containsMatchIn(source)
        assertFalse("OnlineSubtitleSearchSheet.kt must not contain hardcoded Arabic UI text", hasArabicCharacters)
        assertFalse("OnlineSubtitleSearchSheet.kt must not force LayoutDirection.Rtl", source.contains("LayoutDirection.Rtl"))
    }

    @Test
    fun subtitleButtonAndStatusStringsAreAccurateInArabicAndEnglish() {
        val english = parseResources(
            resolveRepoFile("app/src/main/res/values/strings.xml", "src/main/res/values/strings.xml")
        )
        val arabic = parseResources(
            resolveRepoFile("app/src/main/res/values-ar/strings.xml", "src/main/res/values-ar/strings.xml")
        )
        val en = english.strings
        val ar = arabic.strings

        assertEquals("Download & apply", en.getValue("subtitle_download_and_apply"))
        assertEquals("تنزيل وتطبيق", ar.getValue("subtitle_download_and_apply"))
        assertNotEquals("تطبيق", ar.getValue("subtitle_download_and_apply"))
        assertEquals("Downloading & applying…", en.getValue("subtitle_downloading_and_applying"))
        assertEquals("جارٍ التنزيل والتطبيق…", ar.getValue("subtitle_downloading_and_applying"))
        assertNotEquals("جارٍ التطبيق…", ar.getValue("subtitle_downloading_and_applying"))
        assertEquals("Downloading subtitle file and applying it to the video…", en.getValue("subtitle_downloading_and_applying_status"))
        assertEquals("جارٍ تنزيل ملف الترجمة وتطبيقه على الفيديو…", ar.getValue("subtitle_downloading_and_applying_status"))
        assertEquals("Preview", en.getValue("subtitle_preview_button"))
        assertEquals("Loading preview…", en.getValue("subtitle_preview_loading_button"))
        assertEquals("Hide preview", en.getValue("subtitle_hide_preview_button"))
        assertEquals("معاينة", ar.getValue("subtitle_preview_button"))
        assertEquals("جارٍ تحميل المعاينة…", ar.getValue("subtitle_preview_loading_button"))
        assertEquals("إخفاء المعاينة", ar.getValue("subtitle_hide_preview_button"))
        assertEquals("Download & translate to Arabic (AI)", en.getValue("subtitle_download_and_translate_ai"))
        assertEquals("Downloading & translating…", en.getValue("subtitle_downloading_and_translating"))
        assertEquals("تنزيل وترجمة للعربية (AI)", ar.getValue("subtitle_download_and_translate_ai"))
        assertEquals("جارٍ التنزيل والترجمة…", ar.getValue("subtitle_downloading_and_translating"))

        assertTrue(english.plurals.getValue("subtitle_downloads_count").any { it.contains("download") })
        assertEquals(
            listOf("%1\$d تنزيل", "تنزيل واحد", "تنزيلان", "%1\$d تنزيلات", "%1\$d تنزيلاً", "%1\$d تنزيل"),
            arabic.plurals.getValue("subtitle_downloads_count")
        )
    }

    @Test
    fun subtitleCardActionAndFooterStatesReflectDownloadPreviewAndTranslationProgress() {
        val arabicSub = OnlineSubtitle(
            fileId = 1L,
            language = "ar",
            release = "Movie.2025.Arabic",
            downloads = 120,
            format = "srt",
            sourceId = "ar-1",
            provider = "subdl"
        )
        val englishSub = OnlineSubtitle(
            fileId = 2L,
            language = "en",
            release = "Movie.2025.English",
            downloads = 500,
            format = "ass",
            sourceId = "en-2",
            provider = "subdl"
        )

        val idleEnglish = resolveSubtitleCardActionState(
            subtitle = englishSub,
            loading = false,
            downloadingKey = null,
            previewKey = null,
            previewLoading = false,
            previewLines = emptyList()
        )
        assertEquals(R.string.subtitle_download_and_apply, idleEnglish.primaryLabelRes)
        assertEquals(R.string.subtitle_preview_button, idleEnglish.previewLabelRes)
        assertEquals(R.string.subtitle_download_and_translate_ai as Int?, idleEnglish.aiLabelRes)
        assertTrue(idleEnglish.buttonsEnabled)
        assertTrue(idleEnglish.previewEnabled)

        val idleArabic = resolveSubtitleCardActionState(
            subtitle = arabicSub,
            loading = false,
            downloadingKey = null,
            previewKey = "subdl:ar-1",
            previewLoading = false,
            previewLines = listOf("[00:00:01,000] مرحباً")
        )
        assertEquals(R.string.subtitle_hide_preview_button, idleArabic.previewLabelRes)
        assertNull(idleArabic.aiLabelRes)

        val downloadingEnglish = resolveSubtitleCardActionState(
            subtitle = englishSub,
            loading = true,
            downloadingKey = "subdl:en-2",
            previewKey = null,
            previewLoading = false,
            previewLines = emptyList()
        )
        assertEquals(R.string.subtitle_downloading_and_applying, downloadingEnglish.primaryLabelRes)
        assertFalse(downloadingEnglish.buttonsEnabled)
        assertEquals(
            R.string.subtitle_downloading_and_applying_status as Int?,
            resolveSubtitleFooterStatusRes(loading = true, downloadingKey = "subdl:en-2", translationLoading = false)
        )

        val translatingEnglish = resolveSubtitleCardActionState(
            subtitle = englishSub,
            loading = true,
            downloadingKey = "subdl:en-2:ai",
            previewKey = null,
            previewLoading = false,
            previewLines = emptyList()
        )
        assertEquals(R.string.subtitle_download_and_apply, translatingEnglish.primaryLabelRes)
        assertEquals(R.string.subtitle_downloading_and_translating as Int?, translatingEnglish.aiLabelRes)
        assertEquals(
            R.string.subtitle_downloading_and_translating as Int?,
            resolveSubtitleFooterStatusRes(loading = true, downloadingKey = "subdl:en-2:ai", translationLoading = true)
        )

        val previewLoadingState = resolveSubtitleCardActionState(
            subtitle = englishSub,
            loading = false,
            downloadingKey = null,
            previewKey = "subdl:en-2",
            previewLoading = true,
            previewLines = emptyList()
        )
        assertEquals(R.string.subtitle_preview_loading_button, previewLoadingState.previewLabelRes)
        assertFalse(previewLoadingState.previewEnabled)

        val previewErrorOrEmptyState = resolveSubtitleCardActionState(
            subtitle = englishSub,
            loading = false,
            downloadingKey = null,
            previewKey = "subdl:en-2",
            previewLoading = false,
            previewLines = emptyList()
        )
        assertEquals(R.string.subtitle_preview_button, previewErrorOrEmptyState.previewLabelRes)
        assertTrue(previewErrorOrEmptyState.previewEnabled)

        // Filter & sort verification.
        val list = listOf(arabicSub, englishSub)
        assertEquals(listOf(arabicSub), filterAndSortOnlineSubtitles(list, SubtitleResultFilter.AR, SubtitleResultSort.BEST_MATCH))
        assertEquals(listOf(englishSub), filterAndSortOnlineSubtitles(list, SubtitleResultFilter.EN, SubtitleResultSort.BEST_MATCH))
        assertEquals(listOf(arabicSub), filterAndSortOnlineSubtitles(list, SubtitleResultFilter.SRT, SubtitleResultSort.BEST_MATCH))
        assertEquals(listOf(englishSub, arabicSub), filterAndSortOnlineSubtitles(list, SubtitleResultFilter.ALL, SubtitleResultSort.MOST_DOWNLOADED))
    }

    private fun resourcesForLocale(locale: Locale): Resources {
        val config = Configuration(appContext.resources.configuration)
        config.setLocale(locale)
        return appContext.createConfigurationContext(config).resources
    }

    private fun resolveRepoFile(vararg candidates: String): File =
        candidates.map(::File).firstOrNull(File::exists)
            ?: error("Could not locate any of: ${candidates.joinToString()}")

    private data class ParsedResources(
        val strings: Map<String, String>,
        val plurals: Map<String, List<String>>,
        val duplicateStrings: List<String>,
        val duplicatePlurals: List<String>
    )

    private fun parseResources(file: File): ParsedResources {
        val factory = DocumentBuilderFactory.newInstance()
        val doc = factory.newDocumentBuilder().parse(file)
        val root = doc.documentElement
        val strings = linkedMapOf<String, String>()
        val plurals = linkedMapOf<String, List<String>>()
        val dupStrings = mutableListOf<String>()
        val dupPlurals = mutableListOf<String>()

        val children = root.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i) as? Element ?: continue
            val name = node.getAttribute("name")
            when (node.tagName) {
                "string" -> {
                    if (strings.containsKey(name)) dupStrings += name
                    strings[name] = node.textContent
                }
                "plurals" -> {
                    if (plurals.containsKey(name)) dupPlurals += name
                    val items = mutableListOf<String>()
                    val itemNodes = node.getElementsByTagName("item")
                    for (j in 0 until itemNodes.length) {
                        items += (itemNodes.item(j) as Element).textContent
                    }
                    plurals[name] = items
                }
            }
        }
        return ParsedResources(strings, plurals, dupStrings, dupPlurals)
    }
}
