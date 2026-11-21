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

    private fun outputCandidates(): List<OutputDeviceCandidate> {
        val candidates = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { device ->
            OutputDeviceCandidate(
                id = device.id,
                type = device.type,
                productName = device.productName?.toString(),
            )
        }
        AndroidOutputDeviceOrder.seed(candidates)
        return AndroidOutputDeviceOrder.newestFirst(candidates)
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

internal object AndroidOutputDeviceOrder {
    private val arrivalOrder = mutableListOf<Int>()

    @Synchronized
    fun seed(candidates: List<OutputDeviceCandidate>) {
        candidates.forEach { candidate ->
            if (candidate.id !in arrivalOrder) arrivalOrder += candidate.id
        }
    }

    @Synchronized
    fun onAdded(candidates: List<OutputDeviceCandidate>) {
        candidates.forEach { candidate ->
            arrivalOrder.remove(candidate.id)
            arrivalOrder += candidate.id
        }
    }

    @Synchronized
    fun onRemoved(deviceIds: Collection<Int>) {
        arrivalOrder.removeAll(deviceIds.toSet())
    }

    @Synchronized
    fun newestFirst(candidates: List<OutputDeviceCandidate>): List<OutputDeviceCandidate> =
        candidates.sortedByDescending { candidate ->
            arrivalOrder.indexOf(candidate.id).takeIf { it >= 0 } ?: -1
        }
}

internal object AndroidOutputRouteSelection {
    private fun isExternalOutput(candidate: OutputDeviceCandidate): Boolean =
        candidate.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER

    fun detectCurrent(candidates: List<OutputDeviceCandidate>): AndroidOutputTarget {
        val newest = candidates.firstOrNull(::isExternalOutput)
        return when {
            newest == null -> AndroidOutputTarget.SPEAKER
            isUsb(newest) -> AndroidOutputTarget.USB
            isAux(newest) -> AndroidOutputTarget.AUX
            isBluetooth(newest) -> AndroidOutputTarget.BLUETOOTH
            else -> AndroidOutputTarget.SPEAKER
        }
    }

    private fun isUsb(device: OutputDeviceCandidate): Boolean =
        device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY

    private fun isAux(device: OutputDeviceCandidate): Boolean =
        device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            device.type == AudioDeviceInfo.TYPE_LINE_ANALOG

    private fun isBluetooth(device: OutputDeviceCandidate): Boolean =
        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= 31 &&
                (device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))

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
