package com.waizusora.flowery

import kotlin.math.ln

/**
 * Maps the three user-facing tone controls onto the native 31-band ISO equalizer.
 *
 * Gains remain bounded to the native equalizer's -12 dB to +12 dB range. Log-frequency
 * smoothstep transitions avoid abrupt gain discontinuities between adjacent ISO bands.
 */
internal object ThreeBandEqualizer {
    private val centerFrequenciesHz = floatArrayOf(
        20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f, 200f,
        250f, 315f, 400f, 500f, 630f, 800f, 1000f, 1250f, 1600f, 2000f,
        2500f, 3150f, 4000f, 5000f, 6300f, 8000f, 10000f, 12500f, 16000f, 20000f
    )

    fun expand(controlsDb: List<Float>): FloatArray {
        require(controlsDb.size == 3) { "Bass, Mid, and Treble gains are required" }
        val bass = controlsDb[0].coerceIn(-12f, 12f)
        val mid = controlsDb[1].coerceIn(-12f, 12f)
        val treble = controlsDb[2].coerceIn(-12f, 12f)

        return FloatArray(centerFrequenciesHz.size) { index ->
            val frequency = centerFrequenciesHz[index]
            when {
                frequency <= 200f -> bass
                frequency < 500f -> interpolateLog(bass, mid, frequency, 200f, 500f)
                frequency <= 2000f -> mid
                frequency < 5000f -> interpolateLog(mid, treble, frequency, 2000f, 5000f)
                else -> treble
            }
        }
    }

    private fun interpolateLog(start: Float, end: Float, frequency: Float, low: Float, high: Float): Float {
        val position = ((ln(frequency) - ln(low)) / (ln(high) - ln(low))).coerceIn(0f, 1f)
        val smooth = position * position * (3f - 2f * position)
        return start + (end - start) * smooth
    }
}
