package com.waizusora.flowery

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import java.util.Locale

@Composable
internal fun DspDestination(
    volume: Float,
    equalizerGains: List<Float>,
    equalizerEnabled: Boolean,
    foreground: Color,
    muted: Color,
    onVolume: (Float) -> Unit,
    onBandGain: (Int, Float) -> Unit,
    onEqualizerEnabled: (Boolean) -> Unit
) {
    Heading("DSP", foreground)
    Text("Output volume", color = foreground, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    Slider(value = volume, onValueChange = onVolume)
    Text("${(volume * 100).toInt()}%", color = muted, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End)
    Text(
        "USB DAC: software gain is saved separately for each DAC and remains available without a physical volume control. Speaker, AUX and Bluetooth follow Android media volume.",
        color = muted,
        style = MaterialTheme.typography.bodySmall
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Equalizer", color = foreground, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            Text(
                if (equalizerEnabled) "Enabled · 31-band processing" else "Bypassed · band settings retained",
                color = muted,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Switch(checked = equalizerEnabled, onCheckedChange = onEqualizerEnabled)
    }
    Text("31-band ISO equalizer · ±12 dB", color = foreground, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
            ISO_BANDS.forEachIndexed { index, band ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(48.dp)) {
                    VerticalEqualizerSlider(equalizerGains[index], { onBandGain(index, it) }, Modifier.height(250.dp).width(44.dp))
                    Text(band, color = foreground, style = MaterialTheme.typography.labelSmall)
                    Text(String.format(Locale.ROOT, "%.1f", equalizerGains[index]), color = muted,
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
