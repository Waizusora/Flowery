package com.waizusora.flowery

/**
 * Read-only diagnostic sweep of UAC2 AudioControl class requests.
 *
 * Purpose: determine why Feature Unit requests fail on a DAC whose Clock Source
 * requests succeed. The sweep issues only GET_CUR requests; it never writes.
 *
 * - Entity sweep: GET_CUR of volume (channel 1) and of the Clock Source sampling
 *   frequency for entity IDs 1..[MAX_ENTITY_ID]. Entities of the wrong type are
 *   expected to STALL; the pattern of successes shows which entity IDs answer.
 * - Interface sweep: GET_CUR of volume (channel 1) on [unitId] with wIndex
 *   interface bytes 0..[MAX_INTERFACE].
 */
internal object UsbFeatureUnitProbe {
    private const val MAX_ENTITY_ID = 10
    private const val MAX_INTERFACE = 3
    /** UAC2 CUR request (Audio 2.0). UAC1 GET_CUR is 0x81 and must not be used here. */
    private const val UAC2_CUR = 0x01
    private const val CLASS_INTERFACE_IN = 0xA1
    private const val TIMEOUT_MS = 500

    fun run(
        phase: String,
        controlInterface: Int,
        unitId: Int,
        transfer: UsbControlTransfer,
        log: (String) -> Unit
    ) {
        for (entity in 1..MAX_ENTITY_ID) {
            request(phase, "vol_ch1", entity, controlInterface, 0x0201, 2, transfer, log)
            request(phase, "clock_freq", entity, controlInterface, 0x0100, 4, transfer, log)
        }
        for (interfaceNumber in 0..MAX_INTERFACE) {
            if (interfaceNumber == controlInterface) continue
            request(phase, "vol_ch1_iface", unitId, interfaceNumber, 0x0201, 2, transfer, log)
        }
    }

    private fun request(
        phase: String,
        label: String,
        entity: Int,
        interfaceNumber: Int,
        value: Int,
        length: Int,
        transfer: UsbControlTransfer,
        log: (String) -> Unit
    ) {
        val buffer = ByteArray(length)
        val index = (entity shl 8) or interfaceNumber
        val result = try {
            transfer.transfer(CLASS_INTERFACE_IN, UAC2_CUR, value, index, buffer, length, TIMEOUT_MS)
        } catch (error: Exception) {
            Int.MIN_VALUE
        }
        val data = if (result > 0) {
            buffer.copyOfRange(0, minOf(result, length)).joinToString("") { "%02X".format(it.toInt() and 0xff) }
        } else {
            ""
        }
        log(
            "UAC2_SWEEP phase=$phase op=$label entity=$entity interface=$interfaceNumber " +
                "wValue=0x${value.toString(16)} transferred=$result data=$data"
        )
    }
}
