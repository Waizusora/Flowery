package com.waizusora.flowery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbLibusbSessionTest {
    private class FakeUsbControl : UsbControlTransfer {
        var selectorPin = 1
        var secondSelectorPin = 1
        var failSelectorRestore = false
        var failSecondSelectorSet = false
        var failSecondSelectorGet = false
        var failSourceVerification = false
        var sourceWriteCompleted = false
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
                if (entityId == 5 || entityId == 6) {
                    if (entityId == 6 && failSecondSelectorGet) return -1
                    buffer[0] = (if (entityId == 5) selectorPin else secondSelectorPin).toByte()
                    return 1
                }
                if (entityId == 9) {
                    putU32(buffer, 0, if (failSourceVerification && sourceWriteCompleted) 47_999 else sourceRate)
                    if (sourceWriteCompleted) failSourceVerification = false
                    return 4
                }
            }
            if (requestType == 0x21 && request == 0x01 && value == 0x0100) {
                if (entityId == 5 || entityId == 6) {
                    val pin = buffer[0].toInt() and 0xFF
                    if (entityId == 6 && failSecondSelectorSet) { failSecondSelectorSet = false; return -1 }
                    if (entityId == 5 && pin == 1 && failSelectorRestore) return -1
                    if (entityId == 5) selectorPin = pin else secondSelectorPin = pin
                    writes += "selector$entityId:$pin"
                    return 1
                }
                if (entityId == 9) {
                    if (failNextSourceWrite) {
                        failNextSourceWrite = false
                        return -1
                    }
                    sourceRate = readU32(buffer)
                    sourceWriteCompleted = true
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
        assertEquals(listOf("selector5:2", "source:48000", "source:44100", "selector5:1"), fake.writes)
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
        assertEquals(listOf("selector5:2", "source:44100", "selector5:1"), fake.writes)
    }

    @Test
    fun rollsBackMultipleSelectorsInReverseOrder() {
        val fake = FakeUsbControl()
        val session = UsbLibusbSession(null, 1L, fake)
        val path = Uac2ClockPath(
            sourceEntityId = 9,
            sourceControls = 2,
            selectors = listOf(
                Uac2ClockSelectorStep(5, 2, 2),
                Uac2ClockSelectorStep(6, 2, 3)
            ),
            multipliers = emptyList()
        )

        assertTrue(session.negotiateUac2Clock(0, 9, 2, 48_000, clockPaths = listOf(path)).isSuccess)
        assertEquals(2, fake.selectorPin)
        assertEquals(3, fake.secondSelectorPin)
        assertTrue(session.rollbackClockNegotiation().isSuccess)
        assertEquals(1, fake.selectorPin)
        assertEquals(1, fake.secondSelectorPin)
        assertEquals(
            listOf("selector5:2", "selector6:3", "source:48000", "source:44100", "selector6:1", "selector5:1"),
            fake.writes
        )
    }

    @Test
    fun reportsFatalWhenClockRollbackCannotRestoreDeviceState() {
        val fake = FakeUsbControl().apply { failSelectorRestore = true }
        val session = UsbLibusbSession(null, 1L, fake)
        val path = Uac2ClockPath(
            sourceEntityId = 9,
            sourceControls = 2,
            selectors = listOf(Uac2ClockSelectorStep(5, 2, 2)),
            multipliers = emptyList()
        )

        assertTrue(session.negotiateUac2Clock(0, 9, 2, 48_000, clockPaths = listOf(path)).isSuccess)
        val rollback = session.rollbackClockNegotiation()
        assertTrue(rollback.isFailure)
        assertTrue(rollback.exceptionOrNull()?.message?.contains("FATAL") == true)
        assertEquals(2, fake.selectorPin)
        assertEquals(44_100, fake.sourceRate)
    }


    @Test
    fun restoresEarlierSelectorWhenSecondSelectorSetFails() {
        val fake = FakeUsbControl().apply { failSecondSelectorSet = true }
        val session = UsbLibusbSession(null, 1L, fake)
        val path = Uac2ClockPath(
            sourceEntityId = 9,
            sourceControls = 2,
            selectors = listOf(
                Uac2ClockSelectorStep(5, 2, 2),
                Uac2ClockSelectorStep(6, 2, 3)
            ),
            multipliers = emptyList()
        )

        val result = session.negotiateUac2Clock(0, 9, 2, 48_000, clockPaths = listOf(path))
        assertTrue(result.isFailure)
        assertEquals(1, fake.selectorPin)
        assertEquals(1, fake.secondSelectorPin)
        assertEquals(44_100, fake.sourceRate)
        assertEquals(listOf("selector5:2", "selector5:1"), fake.writes)
    }

    @Test
    fun doesNotMutateClockWhenLaterSelectorGetCurFails() {
        val fake = FakeUsbControl().apply { failSecondSelectorGet = true }
        val session = UsbLibusbSession(null, 1L, fake)
        val path = Uac2ClockPath(
            sourceEntityId = 9,
            sourceControls = 2,
            selectors = listOf(
                Uac2ClockSelectorStep(5, 2, 2),
                Uac2ClockSelectorStep(6, 2, 3)
            ),
            multipliers = emptyList()
        )

        val result = session.negotiateUac2Clock(0, 9, 2, 48_000, clockPaths = listOf(path))
        assertTrue(result.isFailure)
        assertEquals(1, fake.selectorPin)
        assertEquals(1, fake.secondSelectorPin)
        assertEquals(44_100, fake.sourceRate)
        assertTrue(fake.writes.isEmpty())
    }

    @Test
    fun restoresClockSourceAndSelectorWhenSourceVerificationFails() {
        val fake = FakeUsbControl().apply { failSourceVerification = true }
        val session = UsbLibusbSession(null, 1L, fake)
        val path = Uac2ClockPath(
            sourceEntityId = 9,
            sourceControls = 2,
            selectors = listOf(Uac2ClockSelectorStep(5, 2, 2)),
            multipliers = emptyList()
        )

        val result = session.negotiateUac2Clock(0, 9, 2, 48_000, clockPaths = listOf(path))
        assertTrue(result.isFailure)
        assertEquals(1, fake.selectorPin)
        assertEquals(44_100, fake.sourceRate)
        assertEquals(listOf("selector5:2", "source:48000", "source:44100", "selector5:1"), fake.writes)
    }

}
