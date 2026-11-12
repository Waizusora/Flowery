package com.waizusora.flowery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbLibusbSessionTest {
    private class FakeUsbControl : UsbControlTransfer {
        var selectorPin = 1
        var sourceRate = 44_100
        val writes = mutableListOf<String>()
        var failNextSourceWrite = false

        override fun transfer(
            requestType: Int,
            request: Int,
            value: Int,
            index: Int,
            buffer: ByteArray,
            length: Int,
            timeoutMs: Int
        ): Int {
            val entityId = index ushr 8
            if (requestType == 0xA1 && request == 0x02 && entityId == 9) {
                // One discrete range: 48 kHz only.
                buffer[0] = 1
                buffer[1] = 0
                putU32(buffer, 2, 48_000)
                putU32(buffer, 6, 48_000)
                putU32(buffer, 10, 0)
                return 14
            }
            if (requestType == 0xA1 && request == 0x01 && value == 0x0100) {
                if (entityId == 5) {
                    buffer[0] = selectorPin.toByte()
                    return 1
                }
                if (entityId == 9) {
                    putU32(buffer, 0, sourceRate)
                    return 4
                }
            }
            if (requestType == 0x21 && request == 0x01 && value == 0x0100) {
                if (entityId == 5) {
                    selectorPin = buffer[0].toInt() and 0xFF
                    writes += "selector:$selectorPin"
                    return 1
                }
                if (entityId == 9) {
                    if (failNextSourceWrite) {
                        failNextSourceWrite = false
                        return -1
                    }
                    sourceRate = readU32(buffer)
                    writes += "source:$sourceRate"
                    return 4
                }
            }
            return -1
        }

        private fun putU32(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = value.toByte()
            bytes[offset + 1] = (value ushr 8).toByte()
            bytes[offset + 2] = (value ushr 16).toByte()
            bytes[offset + 3] = (value ushr 24).toByte()
        }

        private fun readU32(bytes: ByteArray): Int =
            (bytes[0].toInt() and 0xFF) or
                ((bytes[1].toInt() and 0xFF) shl 8) or
                ((bytes[2].toInt() and 0xFF) shl 16) or
                ((bytes[3].toInt() and 0xFF) shl 24)
    }

    @Test
    fun restoresClockSourceAndSelectorAfterStartupRollback() {
        val fake = FakeUsbControl()
        val session = UsbLibusbSession(null, 1L, fake)
        val path = Uac2ClockPath(
            sourceEntityId = 9,
            sourceControls = 2,
            selectors = listOf(Uac2ClockSelectorStep(5, 2, 2)),
            multipliers = emptyList()
        )

        val negotiated = session.negotiateUac2Clock(
            controlInterfaceNumber = 0,
            clockSourceEntityId = 9,
            clockControls = 2,
            sampleRate = 48_000,
            clockPaths = listOf(path)
        )
        assertTrue(negotiated.isSuccess)
        assertEquals(2, fake.selectorPin)
        assertEquals(48_000, fake.sourceRate)

        val rollback = session.rollbackClockNegotiation()
        assertTrue(rollback.isSuccess)
        assertEquals(1, fake.selectorPin)
        assertEquals(44_100, fake.sourceRate)
        assertEquals(listOf("selector:2", "source:48000", "source:44100", "selector:1"), fake.writes)
    }

    @Test
    fun restoresSelectorWhenClockSourceSetCurFails() {
        val fake = FakeUsbControl().apply { failNextSourceWrite = true }
        val session = UsbLibusbSession(null, 1L, fake)
        val path = Uac2ClockPath(
            sourceEntityId = 9,
            sourceControls = 2,
            selectors = listOf(Uac2ClockSelectorStep(5, 2, 2)),
            multipliers = emptyList()
        )

        val negotiated = session.negotiateUac2Clock(
            controlInterfaceNumber = 0,
            clockSourceEntityId = 9,
            clockControls = 2,
            sampleRate = 48_000,
            clockPaths = listOf(path)
        )

        assertTrue(negotiated.isFailure)
        assertEquals(1, fake.selectorPin)
        assertEquals(44_100, fake.sourceRate)
        assertEquals(listOf("selector:2", "source:44100", "selector:1"), fake.writes)
    }
}
