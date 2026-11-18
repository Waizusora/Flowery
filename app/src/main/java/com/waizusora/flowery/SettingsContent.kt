package com.waizusora.flowery

import android.media.AudioDeviceCallback
import android.media.AudioManager
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsContent(page: SettingsPage, color: Color, muted: Color, theme: String,
    onBack: () -> Unit, navigate: (SettingsPage) -> Unit, changeTheme: (String) -> Unit,
    libraryEncoding: String, availableEncodings: List<String>, changeEncoding: (String) -> Unit,
    battery: () -> Unit, update: () -> Unit, openFolders: () -> Unit,
    addFolder: () -> Unit, removeFolder: (LibraryFolder) -> Unit, status: String,
    folders: List<LibraryFolder>, tracks: List<AudioTrackItem>, scan: () -> Unit,
    playTrack: (AudioTrackItem, List<AudioTrackItem>) -> Unit,
    audioSourceLabel: String,
    currentTag: AudioTag?,
    isPlaying: Boolean,
    outputSampleRateHz: Int,
    outputBitDepth: Int,
    outputBackend: String,
    volume: Float,
    onVolume: (Float) -> Unit,
    usbVolumeDetected: Boolean,
    usbVolumeWritable: Boolean,
    usbVolumeVerified: Boolean,
    usbVolumeDb: Float) {
    val context = LocalContext.current
    val routeResolver = remember(context) { AndroidOutputRouteResolver(context) }
    val usbAudioScanner = remember(context) { UsbAudioDeviceScanner(context) }
    var detectedOutput by remember(routeResolver) { mutableStateOf(routeResolver.detectCurrent()) }
    var usbAudioScan by remember(usbAudioScanner) { mutableStateOf(usbAudioScanner.scan()) }
    DisposableEffect(routeResolver, usbAudioScanner) {
        val refreshAudioState = {
            detectedOutput = routeResolver.detectCurrent()
            usbAudioScan = usbAudioScanner.scan()
        }
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>) {
                refreshAudioState()
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>) {
                refreshAudioState()
            }
        }
        val manager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))

        val usbReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(receiverContext: android.content.Context, intent: android.content.Intent) {
                if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED ||
                    intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED
                ) refreshAudioState()
            }
        }
        val usbFilter = android.content.IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(usbReceiver, usbFilter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(usbReceiver, usbFilter)
        }

        refreshAudioState()
        onDispose {
            manager.unregisterAudioDeviceCallback(callback)
            runCatching { context.unregisterReceiver(usbReceiver) }
        }
    }
    var encodingExpanded by remember(page) { mutableStateOf(false) }
    if (page != SettingsPage.ROOT) Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = color) }
        Heading(when(page) {
            SettingsPage.AUDIO -> "Audio"; SettingsPage.INFO_AUDIO -> "Info Audio"; SettingsPage.LIBRARY -> "Library"
            SettingsPage.APPEARANCE -> "Appearance"; SettingsPage.OTHER -> "Other"; SettingsPage.ABOUT -> "About"; SettingsPage.OPEN_SOURCE -> "Open Source"
            SettingsPage.TRANSLATOR -> "Translator"; SettingsPage.CONTRIBUTORS -> "Contributor"; SettingsPage.ROOT -> "Settings"
        }, color)
    } else Heading("Settings", color)
    when(page) {
        SettingsPage.ROOT -> {
            SettingCard("Audio", "Output route", color, muted, Icons.Default.GraphicEq) { navigate(SettingsPage.AUDIO) }
            SettingCard("Appearance", "Theme and language", color, muted, Icons.Default.Palette) { navigate(SettingsPage.APPEARANCE) }
            SettingCard("Library", "Folder management and scanning", color, muted, Icons.Default.Folder) { navigate(SettingsPage.LIBRARY) }
            SettingCard("Other", "Battery optimization", color, muted, Icons.Default.Tune) { navigate(SettingsPage.OTHER) }
            SettingCard("About", "Open-source licenses, credits and updates", color, muted, Icons.Default.Info) { navigate(SettingsPage.ABOUT) }
        }
        SettingsPage.AUDIO -> {
            SettingCard("Info Audio", "Source, decoder, PCM and active output details", color, muted, Icons.Default.Info) {
                navigate(SettingsPage.INFO_AUDIO)
            }
            Text("OUTPUT ROUTE", color = color, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall)
            Text(detectedOutput.displayName, color = color,
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("USB DAC HARDWARE VOLUME", color = color,
                        fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                    val usbDirectActive = outputBackend == "USB Direct"
                    val volumeStatus = when {
                        !usbDirectActive -> "Inactive · start USB Direct playback"
                        !usbVolumeDetected -> "No readable Feature Unit volume control detected"
                        !usbVolumeWritable -> "Detected · hardware volume is read-only"
                        usbVolumeVerified -> "Verified · SET_CUR read back successfully with GET_CUR"
                        else -> "Writable · adjust the slider to verify hardware control"
                    }
                    Text(volumeStatus, color = if (usbVolumeVerified) MaterialTheme.colorScheme.primary else muted,
                        style = MaterialTheme.typography.bodySmall)
                    if (usbVolumeDetected && usbVolumeDb.isFinite()) {
                        Text("Hardware level: %.2f dB".format(java.util.Locale.ROOT, usbVolumeDb),
                            color = color, style = MaterialTheme.typography.labelMedium)
                    }
                    Slider(
                        value = volume.coerceIn(0f, 1f),
                        onValueChange = onVolume,
                        enabled = usbDirectActive && usbVolumeWritable
                    )
                    Text(
                        if (usbDirectActive && usbVolumeWritable)
                            "This control changes the DAC Feature Unit. It does not change Android system volume or PCM samples."
                        else
                            "Connect a supported USB DAC and start playback using USB Direct to enable this control.",
                        color = muted, style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            HorizontalDivider(color = muted.copy(alpha = .25f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("USB AUDIO DEVICES", modifier = Modifier.weight(1f), color = color,
                    fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { usbAudioScan = usbAudioScanner.scan() }) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text(" RESCAN")
                }
            }
            if (usbAudioScan.usbDevices.isEmpty()) {
                Text("No USB Audio Class devices detected.", color = muted,
                    style = MaterialTheme.typography.bodySmall)
            } else {
                usbAudioScan.usbDevices.forEach { device ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(device.productName, color = color, fontWeight = FontWeight.Medium,
                            style = MaterialTheme.typography.titleMedium)
                        Text("VID: %04X · PID: %04X".format(device.vendorId, device.productId),
                            color = muted, style = MaterialTheme.typography.bodySmall)
                        Text("Audio Control: ${if (device.hasAudioControl) "Yes" else "No"} · " +
                            "Audio Streaming: ${if (device.hasAudioStreaming) "Yes" else "No"}",
                            color = muted, style = MaterialTheme.typography.bodySmall)
                        Text("Isochronous OUT: ${if (device.hasIsochronousOutput) "Yes" else "No"} · " +
                            "Isochronous IN: ${if (device.hasIsochronousInput) "Yes" else "No"}",
                            color = muted, style = MaterialTheme.typography.bodySmall)
                        Text(
                            if (device.isPotentialExclusiveOutput) "Potential exclusive output · not verified"
                            else "Exclusive output capability not established",
                            color = muted, style = MaterialTheme.typography.bodySmall
                        )
                    }
                    HorizontalDivider(color = muted.copy(alpha = .16f))
                }
            }
            Text("Android USB audio outputs: ${usbAudioScan.androidOutputs.size}",
                color = color, style = MaterialTheme.typography.labelMedium)
            usbAudioScan.androidOutputs.forEach { output ->
                Text("${output.productName} · type ${output.type} · rates " +
                    output.sampleRates.joinToString().ifBlank { "unspecified" },
                    color = muted, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Detection does not enable direct USB streaming or prove bit-perfect playback. " +
                    "Android's public USB Host API does not expose isochronous streaming transfers.",
                color = muted, style = MaterialTheme.typography.bodySmall
            )
        }


        SettingsPage.INFO_AUDIO -> {
            val active = isPlaying ||
                status.startsWith("Paused", ignoreCase = true) ||
                status.startsWith("Seeking", ignoreCase = true) ||
                status.startsWith("Switching", ignoreCase = true)
            val usbDirect = active && outputBackend == "USB Direct"
            val route = if (active) outputBackend else "—"
            val sampleRate = currentTag?.sampleRate?.takeIf { it > 0 }?.let { "$it Hz" } ?: "—"
            val bitDepth = currentTag?.bitDepth?.takeIf { it > 0 }?.let { "$it-bit" } ?: "—"
            val channels = currentTag?.channels?.takeIf { it > 0 }?.let {
                when (it) { 1 -> "Mono"; 2 -> "Stereo"; else -> "$it channels" }
            } ?: "—"
            val pcmFormat = currentTag?.bitDepth?.takeIf { it > 0 }?.let {
                when (it) { 16 -> "S16"; 24 -> "S24"; 32 -> "S32"; else -> "$it-bit integer PCM" }
            } ?: "—"
            Text(
                if (active) "PLAYBACK ACTIVE" else "NO ACTIVE PLAYBACK",
                color = if (active) MaterialTheme.colorScheme.primary else muted,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                if (active) currentTag?.title?.ifBlank { audioSourceLabel } ?: audioSourceLabel
                else "Audio information is available while a track is loaded.",
                color = color, style = MaterialTheme.typography.bodyMedium
            )
            HorizontalDivider(color = muted.copy(alpha = .25f))
            AudioInfoSection("SOURCE", color, muted) {
                AudioInfoRow("File format", currentTag?.fileType?.takeIf { it.isNotBlank() } ?: "—", color, muted)
                AudioInfoRow("Sample rate", sampleRate, color, muted)
                AudioInfoRow("Bit depth", bitDepth, color, muted)
                AudioInfoRow("Channels", channels, color, muted)
                AudioInfoRow("Bitrate", currentTag?.bitrateKbps?.takeIf { it > 0 }?.let { "$it kbps" } ?: "—", color, muted)
            }
            AudioInfoSection("DECODER / PCM", color, muted) {
                AudioInfoRow("Decoder", if (currentTag != null) "Symphonia" else "—", color, muted)
                AudioInfoRow("PCM format", pcmFormat, color, muted)
                AudioInfoRow("Resampling", if (currentTag != null) "No · source rate preserved" else "—", color, muted)
                AudioInfoRow("Flowery DSP", if (currentTag != null) "Off" else "—", color, muted)
            }
            AudioInfoSection("OUTPUT", color, muted) {
                AudioInfoRow("Backend", route, color, muted)
                AudioInfoRow("Device", if (!active) "—" else if (usbDirect) usbAudioScan.usbDevices.firstOrNull()?.productName ?: detectedOutput.displayName else detectedOutput.displayName, color, muted)
                AudioInfoRow("Output sample rate", outputSampleRateHz.takeIf { active && it > 0 }?.let { "$it Hz" } ?: "—", color, muted)
                AudioInfoRow("Output bit depth", outputBitDepth.takeIf { active && it > 0 }?.let { "$it-bit" } ?: "—", color, muted)
                AudioInfoRow("Stream format", outputBitDepth.takeIf { active && it > 0 }?.let {
                    when (it) { 16 -> "S16"; 24 -> "S24"; 32 -> "S32"; else -> "$it-bit PCM" }
                } ?: "—", color, muted)
                if (usbDirect) {
                    AudioInfoRow("USB Audio Class", "—", color, muted)
                    AudioInfoRow("Clock / feedback", "—", color, muted)
                    AudioInfoRow("Transfer type", "—", color, muted)
                    AudioInfoRow("Bit-perfect", "Not verified", color, muted)
                } else {
                    AudioInfoRow("Bit-perfect", "Not applicable", color, muted)
                }
            }
            Text(
                "Values marked — are not exposed by the active playback telemetry. Bit-perfect is not claimed without end-to-end verification.",
                color = muted, style = MaterialTheme.typography.bodySmall
            )
        }
        SettingsPage.LIBRARY -> {
            Text("FOLDER SELECTION", color = color, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall)
            Text("Remove only removes a folder from Flowery's list. Original folders and audio files are never deleted.",
                color = muted, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = addFolder,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Icon(Icons.Default.Add, null)
                    Text(" ADD")
                }
                val scanInProgress = status.startsWith("Scanning selected folders") ||
                    status.startsWith("Refreshing ")
                val scanTransition = rememberInfiniteTransition(label = "library-scan")
                val scanRotation by scanTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 900, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "scan-rotation"
                )
                Button(
                    onClick = scan,
                    enabled = !scanInProgress,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Icon(Icons.Default.Refresh, null,
                        modifier = Modifier.graphicsLayer { rotationZ = if (scanInProgress) scanRotation else 0f })
                    Text(if (scanInProgress) " Scanning…" else " Scan")
                }
            }
            Text("${folders.size} folders in library", color = muted,
                style = MaterialTheme.typography.labelSmall)
            folders.forEach { folder ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(27.dp))
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(folder.name, color = color, fontWeight = FontWeight.Medium,
                            style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text("Included in library", color = muted, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { removeFolder(folder) }) {
                        Icon(Icons.Default.Close, "Remove folder from Flowery", tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(25.dp))
                    }
                }
                HorizontalDivider(color = muted.copy(alpha = .16f))
            }
            if (folders.isEmpty()) Text("No folders added yet. Tap ADD to choose a folder. You can add more folders at any time.",
                color = muted)
            Text(status, color = muted, style = MaterialTheme.typography.labelSmall)
        }
        SettingsPage.APPEARANCE -> {
            Text("Theme", color = color, fontWeight = FontWeight.SemiBold)
            listOf("Light", "Dark", "System").forEach { option ->
                Row(Modifier.fillMaxWidth().clickable { changeTheme(option) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(theme == option, { changeTheme(option) }); Text(option, color = color)
                }
            }
            HorizontalDivider(color = muted.copy(alpha = .25f))
            Text("Language", color = color, fontWeight = FontWeight.SemiBold)
            Text("Language selection is a concept for now. Localization will be implemented later.", color = muted)
            Text("System default · English · Bahasa Indonesia", color = muted)
        }
        SettingsPage.OTHER -> {
            Text("TEXT ENCODING", color = color, fontWeight = FontWeight.SemiBold)
            Text("Choose the character encoding used to read CUE, M3U and PLS files. M3U8 is always decoded as UTF-8.", color = muted)
            Box {
                OutlinedButton(onClick = { encodingExpanded = true }) {
                    Text(libraryEncoding, color = color)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose text encoding", tint = color)
                }
                DropdownMenu(
                    expanded = encodingExpanded,
                    onDismissRequest = { encodingExpanded = false },
                    modifier = Modifier.heightIn(max = 360.dp)
                ) {
                    availableEncodings.forEach { encoding ->
                        DropdownMenuItem(
                            text = { Text(encoding) },
                            onClick = {
                                changeEncoding(encoding)
                                encodingExpanded = false
                            }
                        )
                    }
                }
            }
            Text("Run Scan again after changing this setting to re-read playlist names and labels.", color = muted, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(color = muted.copy(alpha = .25f))
            SettingCard("Battery optimization", "Open Android battery optimization settings", color, muted, Icons.Default.BatteryFull, battery)
        }
        SettingsPage.ABOUT -> {
            SettingCard("Open Source", "Libraries and licenses used by Flowery", color, muted, Icons.Default.Code) { navigate(SettingsPage.OPEN_SOURCE) }
            SettingCard("Translator", "Translation credits · planned", color, muted, Icons.Default.Translate) { navigate(SettingsPage.TRANSLATOR) }
            SettingCard("Contributor", "Project contributors", color, muted, Icons.Default.Groups) { navigate(SettingsPage.CONTRIBUTORS) }
            SettingCard("Update", "Open GitHub releases in browser", color, muted, Icons.Default.SystemUpdate, update)
        }
        SettingsPage.OPEN_SOURCE -> {
            Text("Direct dependencies · versions and declared licenses", color = muted)
            Detail("Symphonia", "0.5.4 · audio demuxing and decoding · MPL-2.0", color, muted)
            Detail("Lofty", "0.25.5 · audio metadata and tag editing · MIT OR Apache-2.0", color, muted)
            Detail("jni", "0.21 · Rust/JVM native interface · MIT OR Apache-2.0", color, muted)
            Detail("serde / serde_json", "1.x · serialization and JSON · MIT OR Apache-2.0", color, muted)
            Detail("base64", "0.22 · Base64 encoding · MIT OR Apache-2.0", color, muted)
            Detail("libc", "0.2 · native platform bindings · MIT OR Apache-2.0", color, muted)
            Detail("Jetpack Compose", "Compose BOM 2024.12.01 · UI and Material 3 · Apache-2.0", color, muted)
            Detail("Activity Compose", "androidx.activity:activity-compose 1.9.3 · Apache-2.0", color, muted)
            Detail("Lifecycle", "lifecycle-runtime-ktx and lifecycle-viewmodel-compose 2.8.7 · Apache-2.0", color, muted)
            Detail("Room", "room-runtime and room-ktx 2.6.1 · Apache-2.0", color, muted)
            Detail("DataStore", "datastore-preferences 1.1.1 · Apache-2.0", color, muted)
            Detail("AndroidX Core", "core-ktx 1.15.0 · Apache-2.0", color, muted)
            Detail("Android media", "androidx.media:media 1.7.0 · Apache-2.0", color, muted)
            Detail("Document provider", "androidx.documentfile:documentfile 1.0.1 · Apache-2.0", color, muted)
            Detail("AAudio", "Android NDK platform API · Android platform component", color, muted)
        }
        SettingsPage.TRANSLATOR -> Text("Translator credits will be listed when localization work begins.", color = muted)
        SettingsPage.CONTRIBUTORS -> Text("Contributor credits will be listed when contributions are recorded and confirmed.", color = muted)
    }
}
@Composable
private fun AudioInfoSection(title: String, color: Color, muted: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            title, color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.primary.copy(alpha = .09f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        )
        content()
    }
}

@Composable
private fun AudioInfoRow(label: String, value: String, color: Color, muted: Color) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), color = muted, style = MaterialTheme.typography.bodyMedium)
        Text(value, color = color, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
    HorizontalDivider(color = muted.copy(alpha = .14f))
}

@Composable
private fun AudioFlowCard(
    title: String, active: Boolean, subtitle: String, stages: List<String>,
    color: Color, muted: Color
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (active) Color(0xFF2EAD68).copy(alpha = .10f)
                else color.copy(alpha = .06f)
        )
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f), color = color,
                    fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                if (active) Text("ACTIVE", color = Color(0xFF2EAD68),
                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
            }
            Text(subtitle, color = muted, style = MaterialTheme.typography.bodySmall)
            stages.forEachIndexed { index, stage ->
                Text(stage, modifier = Modifier.fillMaxWidth()
                    .background(color.copy(alpha = .07f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                    color = color, style = MaterialTheme.typography.bodySmall)
                if (index != stages.lastIndex) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = "Next audio stage",
                        tint = muted, modifier = Modifier.align(Alignment.CenterHorizontally).size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun SettingCard(title: String, subtitle: String, color: Color, muted: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.ChevronRight, action: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = action)
                .padding(horizontal = 4.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = color, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium)
                Text(subtitle, color = muted, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = muted)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun Detail(title: String, value: String, color: Color, muted: Color) {
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = color, fontWeight = FontWeight.SemiBold); Text(value, color = muted)
    }
}


@Composable
internal fun Heading(title: String, color: Color) {
    Text(
        text = title,
        color = color,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1
    )
}

