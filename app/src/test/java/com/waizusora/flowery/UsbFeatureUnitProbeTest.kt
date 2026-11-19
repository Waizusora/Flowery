package com.waizusora.flowery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbFeatureUnitProbeTest {
    @Test
    fun issuesOnlyReadOnlyGetCurRequests() {
        val requests = mutableListOf<Pair<Int, Int>>()
        val logs = mutableListOf<String>()

        UsbFeatureUnitProbe.run(
            phase = "TEST",
            controlInterface = 0,
            unitId = 7,
            transfer = UsbControlTransfer { requestType, request, _, _, _, _, _ ->
                requests += requestType to request
                -1
            }
        ) { logs += it }

        assertTrue(requests.isNotEmpty())
        // UAC2 CUR is 0x01 (not UAC1 GET_CUR 0x81).
        assertTrue(requests.all { it == (0xA1 to 0x01) })
        assertEquals(requests.size, logs.size)
        assertTrue(logs.all { it.startsWith("UAC2_SWEEP phase=TEST ") })
    }

    @Test
    fun recordsReturnedBytesAndSurvivesTransportExceptions() {
        val logs = mutableListOf<String>()
        var call = 0

        UsbFeatureUnitProbe.run(
            phase = "TEST",
            controlInterface = 0,
            unitId = 7,
            transfer = UsbControlTransfer { _, _, _, _, buffer, length, _ ->
                call++
                if (call == 1) {
                    buffer[0] = 0x34
                    buffer[1] = 0x12
                    length
                } else {
                    throw IllegalStateException("transport failure")
                }
            }
        ) { logs += it }

        assertTrue(logs.first().contains("transferred=2 data=3412"))
        assertTrue(logs.drop(1).all { it.contains("transferred=${Int.MIN_VALUE}") })
    }
}
