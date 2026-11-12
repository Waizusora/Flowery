package com.waizusora.flowery

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

internal enum class AndroidOutputTarget(val preferenceValue: String, val displayName: String) {
    USB("usb", "USB DAC"), AUX("aux", "AUX"), SPEAKER("speaker", "Speaker"), BLUETOOTH("bluetooth", "Bluetooth");

    companion object {
        fun fromPreference(value: String?): AndroidOutputTarget =
            entries.firstOrNull { it.preferenceValue == value } ?: SPEAKER
    }
}

internal data class AndroidOutputRoute(
    val target: AndroidOutputTarget,
    val deviceId: Int,
    val deviceName: String?,
    val deviceType: Int?,
)

internal data class OutputDeviceCandidate(
    val id: Int,
    val type: Int,
    val productName: String?,
)

internal class AndroidOutputRouteResolver(context: Context) {
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun outputCandidates() = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { device ->
        OutputDeviceCandidate(
            id = device.id,
            type = device.type,
            productName = device.productName?.toString(),
        )
    }

    fun detectCurrent(): AndroidOutputTarget =
        AndroidOutputRouteSelection.detectCurrent(outputCandidates())

    fun resolve(target: AndroidOutputTarget): AndroidOutputRoute {
        val candidates = outputCandidates()
        val selected = when (target) {
            AndroidOutputTarget.USB -> AndroidOutputRouteSelection.selectUsb(candidates)
            AndroidOutputTarget.AUX -> resolveAux(candidates)
            AndroidOutputTarget.SPEAKER -> resolveSpeaker(candidates)
            AndroidOutputTarget.BLUETOOTH -> resolveBluetooth(candidates)
        }
        return AndroidOutputRoute(target, selected?.id ?: 0, selected?.productName, selected?.type)
    }

    private fun resolveAux(candidates: List<OutputDeviceCandidate>) =
        AndroidOutputRouteSelection.selectAux(candidates)

    private fun resolveSpeaker(candidates: List<OutputDeviceCandidate>) =
        AndroidOutputRouteSelection.selectSpeaker(candidates)

    private fun resolveBluetooth(candidates: List<OutputDeviceCandidate>) =
        AndroidOutputRouteSelection.selectBluetooth(candidates)
}

internal object AndroidOutputRouteSelection {
    fun detectCurrent(candidates: List<OutputDeviceCandidate>): AndroidOutputTarget =
        when {
            selectUsb(candidates) != null -> AndroidOutputTarget.USB
            selectAux(candidates) != null -> AndroidOutputTarget.AUX
            selectBluetooth(candidates) != null -> AndroidOutputTarget.BLUETOOTH
            else -> AndroidOutputTarget.SPEAKER
        }

    fun selectUsb(candidates: List<OutputDeviceCandidate>): OutputDeviceCandidate? =
        candidates.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        }

    fun selectAux(candidates: List<OutputDeviceCandidate>): OutputDeviceCandidate? =
        candidates.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_LINE_ANALOG
        }

    fun selectSpeaker(candidates: List<OutputDeviceCandidate>): OutputDeviceCandidate? =
        candidates.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

    fun selectBluetooth(candidates: List<OutputDeviceCandidate>): OutputDeviceCandidate? =
        candidates.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= 31 &&
                    (it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
        }
}
