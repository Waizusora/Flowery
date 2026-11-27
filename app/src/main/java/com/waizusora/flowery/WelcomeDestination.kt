package com.waizusora.flowery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun WelcomeDestination(
    foreground: Color,
    muted: Color,
    notificationsGranted: Boolean,
    batteryOptimizationGranted: Boolean,
    usbAudioConnected: Boolean,
    usbAudioPermissionGranted: Boolean,
    onRequestUsbPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    onContinue: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Welcome to Flowery",
            color = foreground,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Text(
            "Your local Hi-Fi music player.",
            color = muted,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("GET STARTED", color = foreground, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium)
                GuideStep(Icons.Default.FolderOpen, "Choose your music folder",
                    "Open Folders, tap Add, then select the folder that contains your music.", foreground, muted)
                GuideStep(Icons.Default.LibraryMusic, "Scan your library",
                    "Tap Scan to index supported audio files and playlists.", foreground, muted)
                GuideStep(Icons.Default.PlayCircle, "Start listening",
                    "Choose a song in Folders. Playback controls are available in Now Playing.", foreground, muted)
            }
        }
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("OPTIONAL", color = foreground, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium)
                PermissionStatusRow(Icons.Default.Notifications, "Notifications",
                    "Playback controls in the notification shade and lock screen.",
                    notificationsGranted, foreground, muted)
                if (!notificationsGranted && android.os.Build.VERSION.SDK_INT >= 33) {
                    OutlinedButton(onClick = onRequestNotificationPermission, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.NotificationsActive, contentDescription = null)
                        Text("  ALLOW NOTIFICATIONS")
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.BatteryFull, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Background playback", color = foreground, fontWeight = FontWeight.SemiBold)
                        Text("Exempt Flowery from battery optimization to help reduce playback interruptions.",
                            color = muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (!batteryOptimizationGranted) {
                    OutlinedButton(onClick = onRequestBatteryOptimization, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.BatteryChargingFull, contentDescription = null)
                        Text("  ALLOW BACKGROUND PLAYBACK")
                    }
                }
                if (usbAudioConnected) {
                    PermissionStatusRow(Icons.Default.Usb, "USB DAC access",
                        "Android grants access separately for each connected DAC.",
                        usbAudioPermissionGranted, foreground, muted)
                    if (!usbAudioPermissionGranted) {
                        OutlinedButton(onClick = onRequestUsbPermission, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Usb, contentDescription = null)
                            Text("  GRANT USB DAC ACCESS")
                        }
                    }
                }
            }
        }
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(28.dp)
        ) { Text("NEXT") }
    }
}

@Composable
private fun GuideStep(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    foreground: Color,
    muted: Color
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = foreground, fontWeight = FontWeight.SemiBold)
            Text(description, color = muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PermissionStatusRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    foreground: Color,
    muted: Color
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, modifier = Modifier.weight(1f), color = foreground, fontWeight = FontWeight.SemiBold)
                Text(if (granted) "Granted" else "Not granted",
                    color = if (granted) MaterialTheme.colorScheme.primary else muted,
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            Text(description, color = muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
