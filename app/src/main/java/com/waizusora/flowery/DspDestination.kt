package com.waizusora.flowery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign

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
        "USB DAC: this single control uses the DAC's USB hardware volume when its Feature Unit is readable; otherwise it uses Flowery software gain. Speaker, AUX and Bluetooth follow Android media volume.",
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
                if (equalizerEnabled) "Enabled · 31 ISO one-third-octave filters" else "Bypassed · Bass, Mid and Treble settings retained",
                color = muted,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Switch(checked = equalizerEnabled, onCheckedChange = onEqualizerEnabled)
    }
    Text("Bass · Mid · Treble controls · ±12 dB", color = foreground, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    Text(
        "Three controls shape the 31 ISO one-third-octave filters with smooth transitions between ranges.",
        color = muted,
        style = MaterialTheme.typography.bodySmall
    )
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom
        ) {
            listOf("Bass", "Mid", "Treble").forEachIndexed { index, label ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
                    Text(
                        String.format(java.util.Locale.ROOT, "%+.1f dB", equalizerGains[index]),
                        color = muted,
                        style = MaterialTheme.typography.labelSmall
                    )
                    VerticalEqualizerSlider(
                        equalizerGains[index],
                        { onBandGain(index, it) },
                        Modifier.height(220.dp).width(52.dp)
                    )
                    Text(label, color = foreground, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
