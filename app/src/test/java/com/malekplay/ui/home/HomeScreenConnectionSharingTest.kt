package com.malekplay.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeScreenConnectionSharingTest {

    @Test fun groupScreenMirrorCardAppearsNextToCastToTvWithoutDisplacingExistingCards() {
        var ultraShareClicks = 0
        var networkClicks = 0
        var groupMirrorClicks = 0

        val actions = buildHomeConnectionSharingActions(
            ultraShareLabel = "إرسال واستلام",
            computerLabel = "الاتصال بالكمبيوتر",
            castLabel = "البث إلى التلفزيون",
            onOpenUltraShare = { ultraShareClicks++ },
            onOpenNetwork = { networkClicks++ },
            onOpenGroupScreenMirror = { groupMirrorClicks++ }
        )

        assertEquals(
            listOf(
                "إرسال واستلام",
                "الاتصال بالكمبيوتر",
                "البث إلى التلفزيون",
                "الشاشة الجماعية"
            ),
            actions.map { it.title }
        )

        // Clicking "الشاشة الجماعية" must open the screen-mirroring flow, never Cast/TV or file transfer.
        actions.last().onClick()
        assertEquals(1, groupMirrorClicks)
        assertEquals(0, networkClicks)
        assertEquals(0, ultraShareClicks)

        // Existing cards keep their exact destinations.
        actions[0].onClick() // إرسال واستلام
        actions[1].onClick() // الاتصال بالكمبيوتر
        actions[2].onClick() // البث إلى التلفزيون
        assertEquals(1, ultraShareClicks)
        assertEquals(2, networkClicks)
        assertEquals(1, groupMirrorClicks)
    }

    @Test fun connectionSharingSectionOmitsActionsWhenNavigationCallbacksUnavailable() {
        val actions = buildHomeConnectionSharingActions(
            ultraShareLabel = "إرسال واستلام",
            computerLabel = "الاتصال بالكمبيوتر",
            castLabel = "البث إلى التلفزيون",
            onOpenUltraShare = null,
            onOpenNetwork = null,
            onOpenGroupScreenMirror = null
        )
        assertTrue(actions.isEmpty())
    }
}
