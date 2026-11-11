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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    Text("Output volume", color = foreground, fontWeight = FontWeight.SemiBold)
    Slider(value = volume, onValueChange = onVolume)
    Text(
        "${(volume * 100).toInt()}%",
        color = muted,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.End
    )
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
            Text("31-Band ISO Equalizer", color = foreground, fontWeight = FontWeight.SemiBold)
            Text(
                if (equalizerEnabled) "Enabled · 20 Hz–20 kHz · ±12 dB"
                else "Bypassed · all 31 band gains retained",
                color = muted,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Switch(checked = equalizerEnabled, onCheckedChange = onEqualizerEnabled)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "FREQUENCY · GAIN (dB)",
                color = muted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                (0 until IsoGraphicEqualizer.BAND_COUNT).forEach { index ->
                    val gain = equalizerGains.getOrElse(index) { 0f }
                    Column(
                        modifier = Modifier.width(48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            String.format(java.util.Locale.ROOT, "%+.1f", gain),
                            color = muted,
                            fontSize = 9.sp,
                            maxLines = 1
                        )
                        VerticalEqualizerSlider(
                            value = gain,
                            onValueChange = { onBandGain(index, it) },
                            modifier = Modifier.height(176.dp).width(40.dp)
                        )
                        Text(
                            IsoGraphicEqualizer.formatFrequency(index),
                            color = foreground,
                            fontSize = 9.sp,
                            maxLines = 1
                        )
                    }
                }
            }
            Text(
                "Swipe to adjust",
                color = muted,
                fontSize = 10.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }
}
