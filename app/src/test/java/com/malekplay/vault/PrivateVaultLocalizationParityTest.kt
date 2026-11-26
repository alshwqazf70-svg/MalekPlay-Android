package com.malekplay.vault

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.malekplay.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
class PrivateVaultLocalizationParityTest {

    private val placeholderRegex = Regex("""%\d+\$[sdfox]""")

    @Test
    fun allVaultStringsAreLocalizedInEnglishAndArabicWithNoDuplicatesAndMatchingPlaceholders() {
        val enFile = resolveRepoFile("app/src/main/res/values/strings.xml", "src/main/res/values/strings.xml")
        val arFile = resolveRepoFile("app/src/main/res/values-ar/strings.xml", "src/main/res/values-ar/strings.xml")

        val enParsed = parseVaultStrings(enFile)
        val arParsed = parseVaultStrings(arFile)

        assertEquals("Duplicate vault_* keys in English", emptyList<String>(), enParsed.duplicates)
        assertEquals("Duplicate vault_* keys in Arabic", emptyList<String>(), arParsed.duplicates)
        assertTrue("Expected at least 100 vault_* strings", enParsed.values.size >= 100)

        assertEquals(
            "Keys present in English but missing in Arabic",
            emptySet<String>(),
            enParsed.values.keys - arParsed.values.keys
        )
        assertEquals(
            "Keys present in Arabic but missing in English",
            emptySet<String>(),
            arParsed.values.keys - enParsed.values.keys
        )

        for ((key, enValue) in enParsed.values) {
            val arValue = arParsed.values.getValue(key)
            assertFalse("Blank English value for $key", enValue.isBlank())
            assertFalse("Blank Arabic value for $key", arValue.isBlank())

            val enPlaceholders = placeholderRegex.findAll(enValue).map { it.value }.sorted().toList()
            val arPlaceholders = placeholderRegex.findAll(arValue).map { it.value }.sorted().toList()
            assertEquals("Placeholder mismatch for $key", enPlaceholders, arPlaceholders)
        }
    }

    @Test
    fun vaultUiFilesHaveNoHardcodedArabicText() {
        val uiFiles = listOf(
            "ui/vault/PrivateVaultScreen.kt",
            "ui/vault/VaultInternalBrowserModal.kt",
            "ui/vault/VaultMediaViewerDialog.kt"
        )
        val arabicCharRegex = Regex("""[\u0600-\u06FF]""")
        for (rel in uiFiles) {
            val file = resolveRepoFile("app/src/main/java/com/malekplay/$rel", "src/main/java/com/malekplay/$rel")
            val content = file.readText()
            assertFalse("Hardcoded Arabic found in $rel", arabicCharRegex.containsMatchIn(content))
        }
    }

    @Test
    fun arabicConfigurationUsesRtlDirectionAndResolvesVaultStrings() {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val arConfig = Configuration(baseContext.resources.configuration).apply {
            setLocale(Locale("ar"))
            setLayoutDirection(Locale("ar"))
        }
        val arContext = baseContext.createConfigurationContext(arConfig)

        assertEquals(
            android.view.View.LAYOUT_DIRECTION_RTL,
            arContext.resources.configuration.layoutDirection
        )
        val takeOut = arContext.getString(R.string.vault_take_out)
        assertTrue(takeOut.contains("إخراج"))
        val formattedSummary = arContext.getString(R.string.vault_browser_selected_summary, 4, "12.5 MB")
        assertTrue(formattedSummary.contains("4") && formattedSummary.contains("12.5 MB"))
    }

    private data class ParsedVaultStrings(
        val values: Map<String, String>,
        val duplicates: List<String>
    )

    private fun parseVaultStrings(file: File): ParsedVaultStrings {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        val values = linkedMapOf<String, String>()
        val duplicates = mutableListOf<String>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val name = el.getAttribute("name")
            if (!name.startsWith("vault_")) continue
            if (values.containsKey(name)) duplicates += name
            values[name] = el.textContent.orEmpty()
        }
        return ParsedVaultStrings(values, duplicates)
    }

    private fun resolveRepoFile(vararg candidates: String): File =
        candidates.map(::File).firstOrNull(File::isFile)
            ?: error("Could not locate any of: ${candidates.joinToString()}")
}
