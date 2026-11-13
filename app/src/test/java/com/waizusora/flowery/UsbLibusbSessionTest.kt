package com.waizusora.flowery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        var failSourceRestore = false

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
                    if (failSourceRestore && readU32(buffer) == 44_100) return -1
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
            selectors = listOf(Uac2ClockSelectorStep(5, 2, 2, listOf(7, 9))),
            multipliers = emptyList(),
            clockEntities = listOf(5, 9)
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
            selectors = listOf(Uac2ClockSelectorStep(5, 2, 2, listOf(7, 9))),
            multipliers = emptyList(),
            clockEntities = listOf(5, 9)
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
                Uac2ClockSelectorStep(5, 2, 2, listOf(7, 6)),
                Uac2ClockSelectorStep(6, 2, 3, listOf(7, 8, 9))
            ),
            multipliers = emptyList(),
            clockEntities = listOf(5, 6, 9)
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
            selectors = listOf(Uac2ClockSelectorStep(5, 2, 2, listOf(7, 9))),
            multipliers = emptyList(),
            clockEntities = listOf(5, 9)
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
                Uac2ClockSelectorStep(5, 2, 2, listOf(7, 6)),
                Uac2ClockSelectorStep(6, 2, 3, listOf(7, 8, 9))
            ),
            multipliers = emptyList(),
            clockEntities = listOf(5, 6, 9)
        )

        val result = session.negotiateUac2Clock(0, 9, 2, 48_000, clockPaths = listOf(path))
        assertTrue(result.isFailure)
        assertEquals(1, fake.selectorPin)
        assertEquals(1, fake.secondSelectorPin)
        assertEquals(44_100, fake.sourceRate)
        assertEquals(listOf("selector5:2", "selector6:1", "selector5:1"), fake.writes)
    }

    @Test
    fun doesNotMutateClockWhenLaterSelectorGetCurFails() {
        val fake = FakeUsbControl().apply { failSecondSelectorGet = true }
        val session = UsbLibusbSession(null, 1L, fake)
        val path = Uac2ClockPath(
            sourceEntityId = 9,
            sourceControls = 2,
            selectors = listOf(
                Uac2ClockSelectorStep(5, 2, 2, listOf(7, 6)),
                Uac2ClockSelectorStep(6, 2, 3, listOf(7, 8, 9))
            ),
            multipliers = emptyList(),
            clockEntities = listOf(5, 6, 9)
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
            selectors = listOf(Uac2ClockSelectorStep(5, 2, 2, listOf(7, 9))),
            multipliers = emptyList(),
            clockEntities = listOf(5, 9)
        )

        val result = session.negotiateUac2Clock(0, 9, 2, 48_000, clockPaths = listOf(path))
        assertTrue(result.isFailure)
        assertEquals(1, fake.selectorPin)
        assertEquals(44_100, fake.sourceRate)
        assertEquals(listOf("selector5:2", "source:48000", "source:44100", "selector5:1"), fake.writes)
    }


    @Test
    fun rejectsClockPathWhenSelectorInputDoesNotMatchNextEntity() {
        val path = Uac2ClockPath(
            sourceEntityId = 9,
            sourceControls = 2,
            selectors = listOf(Uac2ClockSelectorStep(5, 2, 1, listOf(8, 9))),
            multipliers = emptyList(),
            clockEntities = listOf(5, 9)
        )

        assertFalse(path.hasConsistentTopology())
    }

    @Test
    fun rejectsClockPathWhenMultiplierLinkDoesNotMatchNextEntity() {
        val path = Uac2ClockPath(
            sourceEntityId = 9,
            sourceControls = 2,
            selectors = emptyList(),
            multipliers = listOf(Uac2ClockMultiplierStep(5, 0x05, 8)),
            clockEntities = listOf(5, 9)
        )

        assertFalse(path.hasConsistentTopology())
    }

    @Test
    fun acceptsInterleavedSelectorAndMultiplierChain() {
        val path = Uac2ClockPath(
            sourceEntityId = 9,
            sourceControls = 2,
            selectors = listOf(Uac2ClockSelectorStep(5, 2, 1, listOf(6, 7))),
            multipliers = listOf(Uac2ClockMultiplierStep(6, 0x05, 9)),
            clockEntities = listOf(5, 6, 9)
        )

        assertTrue(path.hasConsistentTopology())
    }

    @Test
    fun rejectsActiveSelectorPinOutsideDescriptorInputCountBeforeMutation() {
        val fake = FakeUsbControl().apply { selectorPin = 3 }
        val session = UsbLibusbSession(null, 1L, fake)
        val path = Uac2ClockPath(
            sourceEntityId = 9,
            sourceControls = 2,
            selectors = listOf(Uac2ClockSelectorStep(5, 2, 2, listOf(7, 8))),
            multipliers = emptyList(),
            clockEntities = listOf(5, 9)
        )

        val result = session.negotiateUac2Clock(0, 9, 2, 48_000, clockPaths = listOf(path))

        assertTrue(result.isFailure)
        assertEquals(3, fake.selectorPin)
        assertEquals(44_100, fake.sourceRate)
        assertTrue(fake.writes.isEmpty())
    }

    @Test
    fun rejectsRequestedSelectorPinOutsideDescriptorInputCountBeforeMutation() {
        val fake = FakeUsbControl()
        val session = UsbLibusbSession(null, 1L, fake)
        val path = Uac2ClockPath(
            sourceEntityId = 9,
            sourceControls = 2,
            selectors = listOf(Uac2ClockSelectorStep(5, 2, 3, listOf(7, 8))),
            multipliers = emptyList(),
            clockEntities = listOf(5, 9)
        )

        val result = session.negotiateUac2Clock(0, 9, 2, 48_000, clockPaths = listOf(path))

        assertTrue(result.isFailure)
        assertEquals(1, fake.selectorPin)
        assertEquals(44_100, fake.sourceRate)
        assertTrue(fake.writes.isEmpty())
    }

    @Test
    fun reportsFatalWhenClockSourceRestoreFails() {
        val fake = FakeUsbControl().apply { failSourceRestore = true }
        val session = UsbLibusbSession(null, 1L, fake)
        val path = Uac2ClockPath(
            sourceEntityId = 9,
            sourceControls = 2,
            selectors = listOf(Uac2ClockSelectorStep(5, 2, 2, listOf(7, 9))),
            multipliers = emptyList(),
            clockEntities = listOf(5, 9)
        )

        assertTrue(session.negotiateUac2Clock(0, 9, 2, 48_000, clockPaths = listOf(path)).isSuccess)
        val rollback = session.rollbackClockNegotiation()
        assertTrue(rollback.isFailure)
        assertTrue(rollback.exceptionOrNull()?.message?.contains("FATAL") == true)
        assertEquals(1, fake.selectorPin)
        assertEquals(48_000, fake.sourceRate)
    }


    private class ClockTopologyFake(
        private val supportedRates: Map<Int, Set<Int>>,
        private val ratios: Map<Int, Pair<Int, Int>>,
        initialSourceRates: Map<Int, Int>,
        initialSelectorPins: Map<Int, Int> = emptyMap()
    ) : UsbControlTransfer {
        val sourceRates = initialSourceRates.toMutableMap()
        val selectorPins = initialSelectorPins.toMutableMap()
        val writes = mutableListOf<String>()

        override fun transfer(
            requestType: Int,
            request: Int,
            value: Int,
            index: Int,
            buffer: ByteArray,
            length: Int,
            timeoutMs: Int
        ): Int {
            val entity = index ushr 8
            val selector = value ushr 8
            if (requestType == 0xA1 && request == 0x02) {
                val rates = supportedRates[entity] ?: return -1
                if (rates.isEmpty()) return -1
                buffer[0] = rates.size.toByte()
                buffer[1] = 0
                var offset = 2
                rates.sorted().forEach { rate ->
                    putU32(buffer, offset, rate)
                    putU32(buffer, offset + 4, rate)
                    putU32(buffer, offset + 8, 0)
                    offset += 12
                }
                return offset
            }
            if (requestType == 0xA1 && request == 0x01) {
                if (value == 0x0100 && selectorPins.containsKey(entity)) {
                    buffer[0] = selectorPins.getValue(entity).toByte()
                    return 1
                }
                if (value == 0x0100 && sourceRates.containsKey(entity)) {
                    putU32(buffer, 0, sourceRates.getValue(entity))
                    return 4
                }
                if (selector == 1 || selector == 2) {
                    val ratio = ratios[entity] ?: return -1
                    putU32(buffer, 0, if (selector == 1) ratio.first else ratio.second)
                    return 4
                }
            }
            if (requestType == 0x21 && request == 0x01 && value == 0x0100) {
                if (selectorPins.containsKey(entity)) {
                    val pin = buffer[0].toInt() and 0xFF
                    selectorPins[entity] = pin
                    writes += "selector$entity:$pin"
                    return 1
                }
                if (sourceRates.containsKey(entity)) {
                    val rate = readU32(buffer)
                    sourceRates[entity] = rate
                    writes += "source$entity:$rate"
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
    fun selectsLaterClockPathWhenFirstSourceCannotProduceRequestedRate() {
        val fake = ClockTopologyFake(
            supportedRates = mapOf(7 to setOf(44_100), 8 to setOf(48_000)),
            ratios = emptyMap(),
            initialSourceRates = mapOf(7 to 44_100, 8 to 44_100),
            initialSelectorPins = mapOf(5 to 1)
        )
        val session = UsbLibusbSession(null, 1L, fake)
        val paths = listOf(
            Uac2ClockPath(7, 2, listOf(Uac2ClockSelectorStep(5, 2, 1, listOf(7, 8))), emptyList(), listOf(5, 7)),
            Uac2ClockPath(8, 2, listOf(Uac2ClockSelectorStep(5, 2, 2, listOf(7, 8))), emptyList(), listOf(5, 8))
        )

        val result = session.negotiateUac2Clock(0, 7, 2, 48_000, clockPaths = paths)

        assertTrue(result.isSuccess)
        assertEquals(2, fake.selectorPins[5])
        assertEquals(48_000, fake.sourceRates[8])
        assertEquals(listOf("selector5:2", "source8:48000"), fake.writes)
        assertTrue(session.rollbackClockNegotiation().isSuccess)
        assertEquals(1, fake.selectorPins[5])
        assertEquals(44_100, fake.sourceRates[8])
    }

    @Test
    fun calculatesSourceRateAcrossNestedClockMultipliers() {
        val fake = ClockTopologyFake(
            supportedRates = mapOf(9 to setOf(16_000)),
            ratios = mapOf(6 to (2 to 1), 7 to (3 to 2)),
            initialSourceRates = mapOf(9 to 44_100)
        )
        val session = UsbLibusbSession(null, 1L, fake)
        val path = Uac2ClockPath(
            sourceEntityId = 9,
            sourceControls = 2,
            selectors = emptyList(),
            multipliers = listOf(
                Uac2ClockMultiplierStep(6, 0x05, 7),
                Uac2ClockMultiplierStep(7, 0x05, 9)
            ),
            clockEntities = listOf(6, 7, 9)
        )

        val result = session.negotiateUac2Clock(0, 9, 2, 48_000, clockPaths = listOf(path))

        assertTrue(result.isSuccess)
        assertEquals(16_000, fake.sourceRates[9])
        assertEquals(listOf("source9:16000"), fake.writes)
        assertTrue(session.rollbackClockNegotiation().isSuccess)
        assertEquals(44_100, fake.sourceRates[9])
    }

    @Test
    fun skipsClockPathWhenMultiplierRequiresFractionalSourceRate() {
        val fake = ClockTopologyFake(
            supportedRates = mapOf(9 to setOf(22_050)),
            ratios = mapOf(6 to (2 to 1)),
            initialSourceRates = mapOf(9 to 44_100)
        )
        val session = UsbLibusbSession(null, 1L, fake)
        val path = Uac2ClockPath(
            sourceEntityId = 9,
            sourceControls = 2,
            selectors = emptyList(),
            multipliers = listOf(Uac2ClockMultiplierStep(6, 0x05, 9)),
            clockEntities = listOf(6, 9)
        )

        val result = session.negotiateUac2Clock(0, 9, 2, 44_101, clockPaths = listOf(path))

        assertTrue(result.isFailure)
        assertTrue(fake.writes.isEmpty())
        assertEquals(44_100, fake.sourceRates[9])
    }

}
