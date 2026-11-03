package com.malekplay

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainNavigationSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeAndPrimaryNavigationAreVisibleAndReachable() {
        composeRule.onNodeWithText("MalekPlay").assertExists()
        composeRule.onNodeWithText("Movies").performClick()
        composeRule.onNodeWithText("Series").performClick()
        composeRule.onNodeWithText("Home").performClick()
        composeRule.onNodeWithText("MalekPlay").assertExists()
    }
}
