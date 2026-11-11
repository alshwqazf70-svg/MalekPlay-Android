package com.malekplay

import android.Manifest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsSelected
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainNavigationSmokeTest {
    @get:Rule(order = 0)
    val notificationPermission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun primaryTabsRemainReachableAcrossRepeatedSwitches() {
        val home = composeRule.onNodeWithTag("bottom_nav_home")
        val movies = composeRule.onNodeWithTag("bottom_nav_movies")
        val series = composeRule.onNodeWithTag("bottom_nav_series")
        val music = composeRule.onNodeWithTag("bottom_nav_music")
        val local = composeRule.onNodeWithTag("bottom_nav_local")
        val ultraShare = composeRule.onNodeWithTag("bottom_nav_ultra_share")

        home.assertIsSelected()
        movies.performClick()
        movies.assertIsSelected()
        series.performClick()
        series.assertIsSelected()
        music.performClick()
        music.assertIsSelected()
        local.performClick()
        local.assertIsSelected()
        ultraShare.performClick()
        ultraShare.assertIsSelected()
        home.performClick()
        home.assertIsSelected()

        // Repeat fast tab changes to catch stale or duplicate back-stack entries.
        ultraShare.performClick()
        music.performClick()
        home.performClick()
        movies.performClick()
        local.performClick()
        home.performClick()
        home.assertIsSelected()
    }
}
