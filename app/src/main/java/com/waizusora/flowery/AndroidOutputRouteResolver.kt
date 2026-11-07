package com.waizusora.flowery

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

internal data class AndroidOutputRoute(
    val deviceId: Int,
    val deviceName: String?,
    val deviceType: Int?,
    val deviceAddress: String?,
    val supportedSampleRates: IntArray,
) {
    val isUsbOutput: Boolean get() = deviceId != 0
}

/**
 * Resolves the Android-managed output route used by the shared AAudio path.
 *
 * Direct libusb playback is selected by the Rust playback session before this
 * resolver is consulted. This class does not claim USB interfaces or promise
 * exclusive access to Android-managed USB audio devices.
 */
internal class AndroidOutputRouteResolver(context: Context) {
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun resolve(): AndroidOutputRoute {
        val candidates = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            .map {
                OutputDeviceCandidate(
                    id = it.id,
                    type = it.type,
                    productName = it.productName?.toString(),
                    address = runCatching { it.address }.getOrNull(),
                    sampleRates = it.sampleRates?.filter { rate -> rate > 0 }?.toIntArray() ?: intArrayOf(),
                )
            }
        val selected = AndroidOutputRouteSelection.selectUsbOutput(candidates, Build.VERSION.SDK_INT)
        return AndroidOutputRoute(
            deviceId = selected?.id ?: 0,
            deviceName = selected?.productName,
            deviceType = selected?.type,
            deviceAddress = selected?.address,
            supportedSampleRates = selected?.sampleRates ?: intArrayOf(),
        )
    }
}

internal data class OutputDeviceCandidate(
    val id: Int,
    val type: Int,
    val productName: String?,
    val address: String?,
    val sampleRates: IntArray,
)

internal object AndroidOutputRouteSelection {
    fun selectUsbOutput(
        candidates: List<OutputDeviceCandidate>,
        apiLevel: Int,
    ): OutputDeviceCandidate? {
        if (apiLevel < 28) return null
        return candidates.firstOrNull { it.sampleRates.isNotEmpty() } ?: candidates.firstOrNull()
    }
}
