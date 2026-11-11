package com.waizusora.flowery

import java.util.Locale
import kotlin.math.ln

/**
 * The user-facing equalizer is a fixed 31-band ISO one-third-octave graphic EQ.
 * Native DSP owns the biquad processing; this object defines UI and persistence metadata.
 */
internal object IsoGraphicEqualizer {
    const val BAND_COUNT = 31
    const val MIN_GAIN_DB = -12f
    const val MAX_GAIN_DB = 12f

    val centerFrequenciesHz = floatArrayOf(
        20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f, 200f,
        250f, 315f, 400f, 500f, 630f, 800f, 1000f, 1250f, 1600f, 2000f,
        2500f, 3150f, 4000f, 5000f, 6300f, 8000f, 10000f, 12500f, 16000f, 20000f
    )

    fun preferenceKey(index: Int): String {
        require(index in 0 until BAND_COUNT)
        return "equalizer_band_${index}_db"
    }

    fun formatFrequency(index: Int): String {
        require(index in 0 until BAND_COUNT)
        val frequency = centerFrequenciesHz[index]
        return if (frequency >= 1000f) {
            String.format(Locale.ROOT, "%.2f", frequency / 1000f)
                .trimEnd('0').trimEnd('.') + "k"
        } else {
            String.format(Locale.ROOT, "%.1f", frequency)
                .trimEnd('0').trimEnd('.')
        }
    }

    /**
     * One-time compatibility migration from the former Bass/Mid/Treble controls.
     * New settings are always stored and edited as 31 independent gains.
     */
    fun expandLegacyThreeBand(controlsDb: List<Float>): FloatArray {
        require(controlsDb.size == 3) { "Legacy Bass, Mid, and Treble gains are required" }
        val bass = controlsDb[0].coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        val mid = controlsDb[1].coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        val treble = controlsDb[2].coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)

        return FloatArray(BAND_COUNT) { index ->
            val frequency = centerFrequenciesHz[index]
            when {
                frequency <= 200f -> bass
                frequency < 500f -> interpolateLog(bass, mid, frequency, 200f, 500f)
                frequency <= 2000f -> mid
                frequency < 5000f -> interpolateLog(mid, treble, frequency, 2000f, 5000f)
                else -> treble
            }.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        }
    }

    private fun interpolateLog(start: Float, end: Float, frequency: Float, low: Float, high: Float): Float {
        val position = ((ln(frequency) - ln(low)) / (ln(high) - ln(low))).coerceIn(0f, 1f)
        val smooth = position * position * (3f - 2f * position)
        return start + (end - start) * smooth
    }
}
