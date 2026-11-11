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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun WelcomeDestination(
    foreground: Color,
    muted: Color,
    onRequestNotificationPermission: () -> Unit,
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
                        Text("1. Open Settings → Library", color = foreground, fontWeight = FontWeight.SemiBold)
                        Text("In Flowery, open Settings, select Library, then tap ADD to choose a folder in Android's folder picker. Confirm with Use this folder / Allow.",
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
            }
        }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = foreground.copy(alpha = .07f)),
            shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Text("APP PERMISSIONS", color = foreground, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium)
                PermissionInfoRow(Icons.Default.FolderOpen, "Music folder access",
                    "Granted only to folders you choose. You can add or remove folders later.", foreground, muted)
                PermissionInfoRow(Icons.Default.Notifications, "Notifications",
                    "Optional on Android 13 and newer. Allows playback controls to appear in the notification shade and lockscreen.", foreground, muted)
                PermissionInfoRow(Icons.Default.Usb, "USB DAC access",
                    "Android asks separately when you connect and enable a compatible USB DAC.", foreground, muted)
                PermissionInfoRow(Icons.Default.BatteryFull, "Battery optimization",
                    "Optional. You can review it in Settings if background playback is interrupted.", foreground, muted)
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    OutlinedButton(onClick = onRequestNotificationPermission, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.NotificationsActive, contentDescription = null)
                        Text("  ALLOW NOTIFICATIONS")
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
internal fun PermissionInfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    foreground: Color,
    muted: Color
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = foreground, fontWeight = FontWeight.SemiBold)
            Text(description, color = muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

