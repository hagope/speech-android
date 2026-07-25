package audio.soniqo.speech.demo.overlay

import android.content.Context
import audio.soniqo.speech.SttModel
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
    private const val KEY_BUBBLE_X = "bubble_x"
    private const val KEY_BUBBLE_Y = "bubble_y"
    private const val KEY_CLEANUP = "cleanup_enabled"
    private const val KEY_BLUETOOTH = "bluetooth_mic"
    private const val KEY_STT = "stt_model"

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

    /** Nothing saved yet — the caller picks a starting corner. */
    const val UNSET_POSITION = Int.MIN_VALUE

    /**
     * Where the user left the bubble, in window coordinates.
     *
     * Stored as the position they dragged it to, not where it was last drawn:
     * the window resizes as the state changes, so the drawn position is
     * sometimes clamped inward and would drift a little further each time if
     * it were saved back.
     */
    fun bubblePosition(context: Context): Pair<Int, Int> {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_BUBBLE_X, UNSET_POSITION) to
            prefs.getInt(KEY_BUBBLE_Y, UNSET_POSITION)
    }

    fun setBubblePosition(context: Context, x: Int, y: Int) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_BUBBLE_X, x)
            .putInt(KEY_BUBBLE_Y, y)
            .apply()
    }

    /**
     * Whether to run the on-device LLM over a transcript before inserting it.
     * Off by default: it costs a large download, memory alongside the speech
     * models, and latency on every dictation.
     */
    fun cleanupEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CLEANUP, false)

    fun setCleanupEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CLEANUP, enabled)
            .apply()
    }

    /**
     * Whether to capture from a Bluetooth headset when one is connected.
     * Off by default: headset mics are usually lower quality than the phone's,
     * so this should be a deliberate choice rather than an automatic one.
     */
    fun bluetoothMicEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_BLUETOOTH, false)

    fun setBluetoothMicEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BLUETOOTH, enabled)
            .apply()
    }

    /**
     * Which recogniser the overlay loads. Parakeet TDT is the default:
     * 114 languages and streaming partials. Whisper Small is offline per
     * utterance — no partials — but a useful comparison point, and the two
     * differ enough in size and behaviour to be worth switching between.
     */
    fun sttModel(context: Context): SttModel {
        val name = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STT, null)
        return SttModel.entries.firstOrNull { it.name == name } ?: DEFAULT_STT_MODEL
    }

    fun setSttModel(context: Context, model: SttModel) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STT, model.name)
            .apply()
    }

    /** Models the overlay offers, in the order the picker cycles them. */
    val STT_CHOICES = listOf(
        SttModel.PARAKEET,
        SttModel.WHISPER_SMALL,
        SttModel.CANARY_180M,
    )

    val DEFAULT_STT_MODEL = SttModel.PARAKEET

    fun sttLabel(model: SttModel): String = when (model) {
        SttModel.PARAKEET -> "Parakeet TDT — 114 languages, streaming (891 MB)"
        SttModel.WHISPER_SMALL -> "Whisper Small — offline per utterance (374 MB)"
        SttModel.CANARY_180M -> "Canary 180M — offline per utterance (213 MB)"
        else -> model.name
    }

    /** e.g. "0.5 s" — one decimal place, locale-independent. */
    fun format(seconds: Float): String = "${(seconds * 10).roundToInt() / 10f} s"
}
