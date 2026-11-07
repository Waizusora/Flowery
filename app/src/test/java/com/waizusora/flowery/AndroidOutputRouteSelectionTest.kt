package com.waizusora.flowery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidOutputRouteSelectionTest {
    @Test
    fun prefersUsbOutputWithAdvertisedSampleRates() {
        val candidates = listOf(
            OutputDeviceCandidate(4, 11, "USB DAC without rates", null, intArrayOf()),
            OutputDeviceCandidate(7, 11, "USB DAC with rates", "usb:7", intArrayOf(44_100, 96_000)),
        )

        val selected = AndroidOutputRouteSelection.selectUsbOutput(candidates, 34)

        assertEquals(7, selected?.id)
        assertEquals("USB DAC with rates", selected?.productName)
        assertEquals(listOf(44_100, 96_000), selected?.sampleRates?.toList())
    }

    @Test
    fun fallsBackToFirstUsbOutputWhenNoRatesAreAdvertised() {
        val candidates = listOf(
            OutputDeviceCandidate(4, 11, "First DAC", null, intArrayOf()),
            OutputDeviceCandidate(7, 11, "Second DAC", null, intArrayOf()),
        )

        val selected = AndroidOutputRouteSelection.selectUsbOutput(candidates, 34)

        assertEquals(4, selected?.id)
    }

    @Test
    fun doesNotSelectUsbOutputOnUnsupportedPlatformApi() {
        val candidates = listOf(
            OutputDeviceCandidate(4, 11, "USB DAC", null, intArrayOf(44_100)),
        )

        assertNull(AndroidOutputRouteSelection.selectUsbOutput(candidates, 27))
    }

    @Test
    fun returnsNullWhenNoUsbOutputsAreAvailable() {
        assertNull(AndroidOutputRouteSelection.selectUsbOutput(emptyList(), 34))
    }
}
