package com.waizusora.flowery

import android.content.Context
import android.hardware.usb.UsbManager
import android.net.Uri
import android.provider.OpenableColumns
import java.util.Locale

/** Android SAF adapter for deriving a decoder hint from a selected document URI. */
internal class AudioSourceResolver(private val context: Context) {
    fun resolveExtension(uri: Uri): String {
        val displayName = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull().orEmpty()
        val nameExtension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (nameExtension.isNotBlank()) return nameExtension

        val uriExtension = Uri.decode(uri.lastPathSegment.orEmpty())
            .substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (uriExtension.isNotBlank()) return uriExtension

        return when (context.contentResolver.getType(uri)?.lowercase(Locale.ROOT)) {
            "audio/mp4", "video/mp4", "audio/x-m4a" -> "m4a"
            "audio/aac" -> "aac"
            "audio/flac" -> "flac"
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/ogg", "application/ogg" -> "ogg"
            "audio/mpeg" -> "mp3"
            else -> ""
        }
    }
}

/** Bounded local USB diagnostics recorder and Android USB-host inventory adapter. */
internal class UsbPlaybackDiagnostics(private val context: Context) {
    fun record(event: String) {
        val preferences = context.getSharedPreferences("flowery_preferences", Context.MODE_PRIVATE)
        if (!preferences.getBoolean("usb_diagnostic_recording", false)) return
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
            .format(java.util.Date())
        val line = "\$timestamp $event".replace('\n', ' ').replace('\r', ' ')
        synchronized(LOCK) {
            val lines = preferences.getString("usb_direct_diagnostics", "").orEmpty()
                .lineSequence().filter { it.isNotBlank() }.toMutableList()
            lines.add(line)
            preferences.edit().putString("usb_direct_diagnostics", lines.takeLast(MAX_LINES).joinToString("\n")).apply()
        }
        android.util.Log.i(TAG, line)
    }

    fun hostDeviceSummary(): String {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return "usbHostDevices=unavailable"
        val devices = runCatching { manager.deviceList.values.toList() }.getOrDefault(emptyList())
        if (devices.isEmpty()) return "usbHostDevices=none"
        return devices.joinToString(prefix = "usbHostDevices=[", postfix = "]") { device ->
            val vendor = "%04X".format(Locale.ROOT, device.vendorId)
            val product = "%04X".format(Locale.ROOT, device.productId)
            "vid=$vendor,pid=$product,name=${device.productName ?: "unknown"}," +
                "manufacturer=${device.manufacturerName ?: "unknown"}," +
                "interfaces=${device.interfaceCount},path=${device.deviceName}"
        }
    }

    private companion object {
        const val TAG = "FloweryUsbDiag"
        const val MAX_LINES = 1_200
        val LOCK = Any()
    }
}
