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
    musicFolderGranted: Boolean,
    notificationsGranted: Boolean,
    batteryOptimizationGranted: Boolean,
    onAddMusicFolder: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    onContinue: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(18.dp))
        Text("Welcome to Flowery", color = foreground, style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text("Your local Hi-Fi music player. Follow these steps to add your music folder.",
            color = muted, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = foreground.copy(alpha = .07f)),
            shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("HOW TO ADD A MUSIC FOLDER", color = foreground, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
                    Column(Modifier.weight(1f)) {
                        Text("1. Grant music folder access", color = foreground, fontWeight = FontWeight.SemiBold)
                        Text("Choose a folder and confirm Use this folder / Allow. Flowery only accesses folders you select.",
                            color = muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
                    Column(Modifier.weight(1f)) {
                        Text("2. Tap Scan", color = foreground, fontWeight = FontWeight.SemiBold)
                        Text("Flowery indexes supported audio files and playlists in the folders you selected.",
                            color = muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.PlayCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
                    Column(Modifier.weight(1f)) {
                        Text("3. Open Folders and play", color = foreground, fontWeight = FontWeight.SemiBold)
                        Text("Choose a song to start playback. Your playback controls will appear in Now Playing.",
                            color = muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (!musicFolderGranted) {
                    OutlinedButton(onClick = onAddMusicFolder, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Text("  CHOOSE MUSIC FOLDER")
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = foreground.copy(alpha = .07f)),
            shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Text("APP PERMISSIONS", color = foreground, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium)
                PermissionStatusRow(Icons.Default.FolderOpen, "Music folder access",
                    "Required to index and play music from your selected folder.", musicFolderGranted, foreground, muted)
                if (!musicFolderGranted) {
                    OutlinedButton(onClick = onAddMusicFolder, modifier = Modifier.fillMaxWidth()) {
                        Text("GRANT FOLDER ACCESS")
                    }
                }
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    PermissionStatusRow(Icons.Default.Notifications, "Notifications",
                        "Optional. Enables playback controls in the notification shade and lockscreen.",
                        notificationsGranted, foreground, muted)
                    if (!notificationsGranted) {
                        OutlinedButton(onClick = onRequestNotificationPermission, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.NotificationsActive, contentDescription = null)
                            Text("  ALLOW NOTIFICATIONS")
                        }
                    }
                } else {
                    PermissionStatusRow(Icons.Default.Notifications, "Notifications",
                        "Runtime permission is not required on this Android version.", true, foreground, muted)
                }
                PermissionStatusRow(Icons.Default.BatteryFull, "Background playback",
                    "Battery optimization exemption is optional but can reduce playback interruptions.",
                    batteryOptimizationGranted, foreground, muted)
                if (!batteryOptimizationGranted) {
                    OutlinedButton(onClick = onRequestBatteryOptimization, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.BatteryChargingFull, contentDescription = null)
                        Text("  ALLOW BACKGROUND PLAYBACK")
                    }
                }
            }
        }
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(28.dp)) { Text("NEXT") }
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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, modifier = Modifier.weight(1f), color = foreground, fontWeight = FontWeight.SemiBold)
                Text(if (granted) "Granted" else if (title == "Music folder access") "Required" else "Not granted",
                    color = if (granted) MaterialTheme.colorScheme.primary
                        else if (title == "Music folder access") MaterialTheme.colorScheme.error else muted,
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            Text(description, color = muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
