package com.waizusora.flowery

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

internal enum class AndroidOutputTarget(val preferenceValue: String) {
    AUX("aux"), SPEAKER("speaker"), BLUETOOTH("bluetooth");

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

    fun resolve(target: AndroidOutputTarget): AndroidOutputRoute {
        val candidates = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { device ->
            OutputDeviceCandidate(
                id = device.id,
                type = device.type,
                productName = device.productName?.toString(),
            )
        }
        val selected = when (target) {
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
