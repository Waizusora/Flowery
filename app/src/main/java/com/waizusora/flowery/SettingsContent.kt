package com.waizusora.flowery

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
    usbDiagnosticRecording: Boolean, usbDiagnosticLogCount: Int, usbDiagnosticDescriptorReport: String,
    onToggleUsbDiagnosticLog: () -> Unit, onSaveUsbDiagnosticLog: () -> Unit,
    onBack: () -> Unit, navigate: (SettingsPage) -> Unit, changeTheme: (String) -> Unit,
    libraryEncoding: String, availableEncodings: List<String>, changeEncoding: (String) -> Unit,
    battery: () -> Unit, update: () -> Unit, openFolders: () -> Unit,
    addFolder: () -> Unit, removeFolder: (LibraryFolder) -> Unit, status: String,
    folders: List<LibraryFolder>, tracks: List<AudioTrackItem>, scan: () -> Unit,
    playTrack: (AudioTrackItem, List<AudioTrackItem>) -> Unit,
    usbBitPerfectEnabled: Boolean, onUsbBitPerfectChanged: (Boolean) -> Unit,
    usbDacUiStatus: String, usbDacDeviceName: String, usbDacConnected: Boolean,
    usbDacPermissionGranted: Boolean, onConnectUsbDac: () -> Unit, onReleaseUsbDac: () -> Unit,
    audioSourceLabel: String) {
    var encodingExpanded by remember(page) { mutableStateOf(false) }
    if (page != SettingsPage.ROOT) Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = color) }
        Heading(when(page) {
            SettingsPage.AUDIO -> "Audio"; SettingsPage.AUDIO_INFO -> "Information Audio"; SettingsPage.USB_DIAGNOSTICS -> "USB Diagnostics"; SettingsPage.LIBRARY -> "Library"
            SettingsPage.APPEARANCE -> "Appearance"; SettingsPage.OTHER -> "Other"; SettingsPage.ABOUT -> "About"; SettingsPage.OPEN_SOURCE -> "Open Source"
            SettingsPage.TRANSLATOR -> "Translator"; SettingsPage.CONTRIBUTORS -> "Contributor"; SettingsPage.ROOT -> "Settings"
        }, color)
    } else Heading("Settings", color)
    when(page) {
        SettingsPage.ROOT -> {
            SettingCard("Audio", "Playback engine and processing", color, muted) { navigate(SettingsPage.AUDIO) }
            SettingCard("Appearance", "Theme and language", color, muted) { navigate(SettingsPage.APPEARANCE) }
            SettingCard("Library", "Folder management and scanning", color, muted) { navigate(SettingsPage.LIBRARY) }
            SettingCard("Other", "Battery optimization", color, muted) { navigate(SettingsPage.OTHER) }
            SettingCard("About", "Open-source licenses, credits and updates", color, muted, Icons.Default.Info) { navigate(SettingsPage.ABOUT) }
        }
        SettingsPage.AUDIO -> {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = color.copy(alpha = .06f))) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("USB DAC", color = color, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium)
                    Text(usbDacDeviceName, color = color, style = MaterialTheme.typography.bodyMedium)
                    Text(usbDacUiStatus, color = muted, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onConnectUsbDac, enabled = usbDacConnected,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7452A6))) {
                            Icon(Icons.Default.Usb, contentDescription = null)
                            Text("  Sambungkan DAC")
                        }
                        OutlinedButton(onClick = onReleaseUsbDac,
                            enabled = usbDacPermissionGranted || status.contains("USB exclusive", ignoreCase = true),
                            modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.LinkOff, contentDescription = null)
                            Text("  Lepas DAC")
                        }
                    }
                    Text("Lepas DAC menghentikan playback USB dan menutup koneksi/interface Flowery agar aplikasi lain dapat mencoba menggunakan DAC. Android tidak menyediakan API publik bagi aplikasi biasa untuk mencabut grant USB Flowery secara paksa; grant sementara berakhir saat DAC dilepas dari perangkat.",
                        color = muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = color.copy(alpha = .06f))) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("USB Exclusive / Bit-Perfect", color = color, fontWeight = FontWeight.SemiBold)
                            Text("Use a descriptor-compatible UAC1/UAC2 DAC through direct USB transport. Supported lossless integer PCM and IEEE Float32 WAV can use the source-preserving route when the exact format, rate and channel layout match and DSP/software gain are inactive.", color = muted, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = usbBitPerfectEnabled, onCheckedChange = onUsbBitPerfectChanged)
                    }
                    Text("Lossless integer PCM and IEEE Float32 WAV may retain their source sample bits when the DAC advertises a matching profile and no EQ, resampling, channel remapping or software gain is active. Other sources are decoded to float32 and processed or quantized to the negotiated DAC format; these paths are not Bit Perfect. Playback stops if strict USB setup fails; there is no AAudio fallback.", color = muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            SettingCard("Information Audio", "Decoding, resampling, equalizer and output", color, muted) { navigate(SettingsPage.AUDIO_INFO) }
            SettingCard("USB Diagnostics", "Record USB descriptors, DAC profiles, clock negotiation and playback telemetry", color, muted) { navigate(SettingsPage.USB_DIAGNOSTICS) }
        }
        SettingsPage.AUDIO_INFO -> {
            val usbRoute = status.contains("USB exclusive", ignoreCase = true)
            val bitPerfectRoute = status.contains("BIT PERFECT", ignoreCase = true) ||
                status.contains("Bit Perfect", ignoreCase = true)
            Text("Flowery automatically selects the active route from the source format, DSP state, negotiated DAC profile and output device.",
                color = muted, style = MaterialTheme.typography.bodySmall)
            Detail("Source file", audioSourceLabel, color, muted)
            AudioFlowCard(
                title = "BIT PERFECT · source-preserving route",
                active = usbRoute && bitPerfectRoute,
                subtitle = if (usbRoute && bitPerfectRoute) "Active now" else "Available when source, DAC and processing conditions match",
                stages = listOf(
                    "$audioSourceLabel · lossless integer PCM or IEEE Float32 WAV",
                    "Symphonia · format-aware decoding without sample conversion",
                    "Native sample representation · integer bits or Float32 bit patterns",
                    "Rust USB PCM producer · bounded queue",
                    "C++ libusb · isochronous USB transfer",
                    "USB DAC · negotiated sample rate, channels and encoding"
                ),
                color = color, muted = muted
            )
            AudioFlowCard(
                title = "DSP / decoded route",
                active = !bitPerfectRoute,
                subtitle = if (!bitPerfectRoute) "Active or selected fallback" else "Standby · selected when processing or conversion is required",
                stages = listOf(
                    "$audioSourceLabel · supported audio format",
                    "Symphonia · decode to float32 PCM",
                    "Rubato · sample-rate conversion when required",
                    "31-band ISO equalizer · gain, volume and other DSP",
                    if (usbRoute) "Rust PCM producer · quantize to negotiated DAC integer format" else "AAudio · float32 output ring buffer",
                    if (usbRoute) "C++ libusb · USB transfer → DAC" else "Android audio route · speaker, AUX or Bluetooth"
                ),
                color = color, muted = muted
            )
            Detail("Playback status", status, color, muted)
            Detail("Route detection", "The highlighted route follows Flowery's current playback status. Bit Perfect is shown only when the native producer confirms an eligible source-to-DAC path; otherwise the decoded/DSP route is used.", color, muted)
            Detail("Runtime telemetry", "AAudio callback and output telemetry plus USB descriptor, clock and transport events are available in Settings → Audio → USB Diagnostics → Record Log.", color, muted)
        }
        SettingsPage.USB_DIAGNOSTICS -> {
            Text("Record Log captures connected DAC descriptors, descriptor-derived playback profiles, UAC2 clock ranges, AAudio runtime telemetry, and direct USB/libusb events in one file.",
                color = muted, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onToggleUsbDiagnosticLog, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = if (usbDiagnosticRecording) Color(0xFFB44758) else Color(0xFF7452A6))) {
                    Icon(if (usbDiagnosticRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord, contentDescription = null)
                    Text(if (usbDiagnosticRecording) "  Stop Log" else "  Record Log")
                }
                Button(onClick = onSaveUsbDiagnosticLog, enabled = !usbDiagnosticRecording &&
                    (usbDiagnosticLogCount > 0 || usbDiagnosticDescriptorReport.isNotBlank()),
                    modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7452A6))) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Text("  Save Log")
                }
            }
            Text(if (usbDiagnosticRecording)
                "Recording. Connect or use the DAC, reproduce the issue, then stop and save the log."
            else "Start recording before reproducing a USB DAC issue. Descriptor inspection runs at the start of the recording session.",
                color = muted, style = MaterialTheme.typography.bodySmall)
            Text("AAudio telemetry samples: $usbDiagnosticLogCount", color = muted, style = MaterialTheme.typography.labelSmall)
            Detail("Descriptor capture", if (usbDiagnosticDescriptorReport.isBlank()) "Not captured yet" else "Captured or pending", color, muted)
            Text("The log is the single diagnostic export. It includes descriptor and clock capability evidence, actual AAudio output state, and USB transport failures where available.",
                color = muted, style = MaterialTheme.typography.bodySmall)
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
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7452A6)),
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
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7452A6)),
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
                    Icon(Icons.Default.Folder, null, tint = Color(0xFFD66B9A), modifier = Modifier.size(27.dp))
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(folder.name, color = color, fontWeight = FontWeight.Medium,
                            style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text("Included in library", color = muted, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { removeFolder(folder) }) {
                        Icon(Icons.Default.Close, "Remove folder from Flowery", tint = Color(0xFFD66B9A),
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
            listOf("Light", "Black", "Auto").forEach { option ->
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
            Detail("Rubato", "5.0.1 · streaming sample-rate conversion · MIT OR Apache-2.0", color, muted)
            Detail("Lofty", "0.25.5 · audio metadata and tag editing · MIT OR Apache-2.0", color, muted)
            Detail("libusb", "1.0.30-0 · USB host transport · LGPL-2.1-or-later", color, muted)
            Detail("jni", "0.21 · Rust/JVM native interface · MIT OR Apache-2.0", color, muted)
            Detail("audioadapter-buffers", "5.1 · audio buffer adapters · MIT OR Apache-2.0", color, muted)
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
            Text("Consult the exact dependency versions and bundled license notices for the complete license inventory, including transitive dependencies.", color = muted,
                style = MaterialTheme.typography.bodySmall)
        }
        SettingsPage.TRANSLATOR -> Text("Translator credits will be listed when localization work begins.", color = muted)
        SettingsPage.CONTRIBUTORS -> Text("Contributor credits will be listed when contributions are recorded and confirmed.", color = muted)
    }
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
    Card(Modifier.fillMaxWidth().clickable(onClick = action), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = .09f))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(27.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = color, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                Text(subtitle, color = muted, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = muted)
        }
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

