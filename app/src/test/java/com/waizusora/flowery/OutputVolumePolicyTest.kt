package com.waizusora.flowery

/**
 * Direct USB/libusb output bypasses Android's mixer. Keep system volume and
 * native software gain/ducking out of that route to preserve source PCM bytes.
 */
internal object OutputVolumePolicy {
    fun allowsSystemVolumeControl(usbRouteActive: Boolean): Boolean = !usbRouteActive

    fun nativeGain(usbRouteActive: Boolean, ducked: Boolean, duckGain: Float): Float =
        if (usbRouteActive) 1f else if (ducked) duckGain else 1f

    fun reportedVolume(usbRouteActive: Boolean, systemVolume: Float): Float =
        if (usbRouteActive) 1f else systemVolume
}
