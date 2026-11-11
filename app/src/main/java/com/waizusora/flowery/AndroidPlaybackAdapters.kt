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

/** Bounded asynchronous USB diagnostics recorder and Android USB-host inventory adapter. */
internal class UsbPlaybackDiagnostics(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(
        "flowery_preferences",
        Context.MODE_PRIVATE
    )
    private val recordingEnabled = java.util.concurrent.atomic.AtomicBoolean(
        preferences.getBoolean("usb_diagnostic_recording", false)
    )
    private val pendingEvents = java.util.ArrayDeque<String>()
    private val pendingLock = Any()
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "FloweryUsbDiagnostics").apply { isDaemon = true }
    }
    private var drainScheduled = false

    private val preferenceListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "usb_diagnostic_recording") {
                recordingEnabled.set(preferences.getBoolean(key, false))
            }
        }

    init {
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    /**
     * Enqueues an event without performing preference I/O on the playback worker.
     * Persistence is batched on a dedicated daemon thread and the pending queue is bounded.
     */
    fun record(event: String) {
        if (!recordingEnabled.get()) return
        val timestamp = java.text.SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            Locale.US
        ).format(java.util.Date())
        val line = "$timestamp $event".replace('\n', ' ').replace('\r', ' ')
        synchronized(pendingLock) {
            while (pendingEvents.size >= MAX_PENDING_EVENTS) pendingEvents.removeFirst()
            pendingEvents.addLast(line)
            if (!drainScheduled) {
                drainScheduled = true
                writer.execute(::drainPendingEvents)
            }
        }
    }

    private fun drainPendingEvents() {
        while (true) {
            val batch = synchronized(pendingLock) {
                if (pendingEvents.isEmpty()) {
                    drainScheduled = false
                    return
                }
                buildList {
                    repeat(minOf(MAX_BATCH_SIZE, pendingEvents.size)) {
                        add(pendingEvents.removeFirst())
                    }
                }
            }
            val existing = preferences.getString("usb_direct_diagnostics", "").orEmpty()
            val retained = existing.lineSequence().filter { it.isNotBlank() }.toMutableList()
            retained.addAll(batch)
            preferences.edit()
                .putString("usb_direct_diagnostics", retained.takeLast(MAX_LINES).joinToString("\n"))
                .apply()
            batch.forEach { android.util.Log.i(TAG, it) }
        }
    }

    fun hostDeviceSummary(): String {
        val manager = appContext.getSystemService(Context.USB_SERVICE) as? UsbManager
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
        const val MAX_PENDING_EVENTS = 2_048
        const val MAX_BATCH_SIZE = 64
    }
}
