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
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class PrivateVaultLocalizationParityTest {

    private val placeholderRegex = Regex("%\\d+\\$[sd]")

    @Test
    fun allVaultStringsAreLocalizedInEnglishAndArabicWithMatchingPlaceholders() {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val enContext = baseContext.createConfigurationContext(
            Configuration(baseContext.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        )
        val arContext = baseContext.createConfigurationContext(
            Configuration(baseContext.resources.configuration).apply { setLocale(Locale("ar")) }
        )

        val vaultStringFields = R.string::class.java.fields
            .filter { it.name.startsWith("vault_") }
            .sortedBy { it.name }

        assertTrue("Expected vault_* string resources to be present", vaultStringFields.size >= 80)

        for (field in vaultStringFields) {
            val resId = field.getInt(null)
            val enValue = enContext.getString(resId)
            val arValue = arContext.getString(resId)

            assertFalse("Missing English value for ${field.name}", enValue.isBlank())
            assertFalse("Missing Arabic value for ${field.name}", arValue.isBlank())

            val enPlaceholders = placeholderRegex.findAll(enValue).map { it.value }.sorted().toList()
            val arPlaceholders = placeholderRegex.findAll(arValue).map { it.value }.sorted().toList()
            assertEquals(
                "Format placeholder mismatch for ${field.name}: EN=$enValue vs AR=$arValue",
                enPlaceholders,
                arPlaceholders
            )
        }
    }

    @Test
    fun arabicConfigurationUsesRtlDirectionAndArabicStrings() {
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
}
