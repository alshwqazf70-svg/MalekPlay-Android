package com.malekplay.player.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class EqualizerBandMapperTest {

    @Test
    fun expandsFiveBandPresetSmoothlyToNineBands() {
        assertEquals(
            listOf(0, 50, 100, 150, 200, 250, 300, 350, 400),
            EqualizerBandMapper.map(intArrayOf(0, 100, 200, 300, 400), 9)
        )
    }

    @Test
    fun reducesPresetWhilePreservingBothEndpoints() {
        assertEquals(
            listOf(-600, 0, 600),
            EqualizerBandMapper.map(intArrayOf(-600, -300, 0, 300, 600), 3)
        )
    }

    @Test
    fun handlesSingleBandAndEmptyPreset() {
        assertEquals(listOf(200), EqualizerBandMapper.map(intArrayOf(0, 100, 200, 300, 400), 1))
        assertEquals(listOf(0, 0, 0), EqualizerBandMapper.map(intArrayOf(), 3))
        assertEquals(emptyList<Int>(), EqualizerBandMapper.map(intArrayOf(1, 2), 0))
    }

    @Test
    fun capsDeviceBandCountToSupportedMaximum() {
        assertEquals(
            AudioEffectsController.MAX_BANDS,
            EqualizerBandMapper.map(intArrayOf(0, 100), 100).size
        )
    }

    @Test
    fun interpolatesNegativeGainsWithCorrectRounding() {
        assertEquals(
            listOf(-500, -250, 0),
            EqualizerBandMapper.map(intArrayOf(-500, 0), 3)
        )
    }
}
