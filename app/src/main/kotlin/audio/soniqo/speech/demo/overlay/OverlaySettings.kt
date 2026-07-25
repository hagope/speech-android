package audio.soniqo.speech.demo.overlay

import android.content.Context
import kotlin.math.roundToInt

/**
 * Persisted overlay preferences.
 *
 * Pause tolerance is how long you may pause mid-sentence before the VAD calls
 * the utterance finished. The engine default (0.5 s) favours snappy short
 * commands and cuts people off when they think mid-sentence or dictate digit
 * sequences; raising it trades latency for room to breathe.
 *
 * Slider positions rather than raw floats are the storage unit for the UI
 * mapping, so a value can never land between steps.
 */
object OverlaySettings {

    const val MIN_PAUSE_SEC = 0.3f
    const val MAX_PAUSE_SEC = 2.0f
    const val DEFAULT_PAUSE_SEC = 0.5f
    const val STEP_SEC = 0.1f

    private const val PREFS = "voice_overlay"
    private const val KEY_PAUSE = "pause_tolerance_sec"

    /** Number of discrete slider positions between min and max, inclusive. */
    val steps: Int = ((MAX_PAUSE_SEC - MIN_PAUSE_SEC) / STEP_SEC).roundToInt()

    /** Clamp to range and snap to [STEP_SEC], avoiding float drift. */
    fun clampPause(seconds: Float): Float {
        val clamped = seconds.coerceIn(MIN_PAUSE_SEC, MAX_PAUSE_SEC)
        val stepsFromMin = ((clamped - MIN_PAUSE_SEC) / STEP_SEC).roundToInt()
        return progressToSeconds(stepsFromMin)
    }

    /** Slider position → seconds. */
    fun progressToSeconds(progress: Int): Float {
        val clamped = progress.coerceIn(0, steps)
        // Round-trip through tenths so the result is exactly representable
        // as one decimal place (0.7f, not 0.70000005f).
        return (MIN_PAUSE_SEC * 10f + clamped).roundToInt() / 10f
    }

    /** Seconds → slider position. */
    fun secondsToProgress(seconds: Float): Int {
        val clamped = seconds.coerceIn(MIN_PAUSE_SEC, MAX_PAUSE_SEC)
        return ((clamped - MIN_PAUSE_SEC) / STEP_SEC).roundToInt().coerceIn(0, steps)
    }

    fun pauseToleranceSec(context: Context): Float =
        clampPause(
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getFloat(KEY_PAUSE, DEFAULT_PAUSE_SEC)
        )

    fun setPauseToleranceSec(context: Context, seconds: Float) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_PAUSE, clampPause(seconds))
            .apply()
    }

    /** e.g. "0.5 s" — one decimal place, locale-independent. */
    fun format(seconds: Float): String = "${(seconds * 10).roundToInt() / 10f} s"
}
