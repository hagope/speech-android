package audio.soniqo.speech.demo.overlay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import audio.soniqo.speech.ModelManager
import audio.soniqo.speech.LlmModel
import audio.soniqo.speech.ModelPrecision
import audio.soniqo.speech.PipelineMode
import audio.soniqo.speech.PipelineState
import audio.soniqo.speech.SpeechConfig
import audio.soniqo.speech.SpeechEvent
import audio.soniqo.speech.SpeechPipeline
import audio.soniqo.speech.SttModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil

/**
 * Floating dictation bubble drawn over other apps.
 *
 * Idle it is a single mic button. Tapping it starts recording and swaps in
 * **Stop** and **Cancel**: Stop types the transcript into whatever text field
 * has input focus (via [DictationAccessibilityService]), Cancel throws it away.
 *
 * The overlay window is non-focusable on purpose — if it took focus, the text
 * field we are dictating into would lose it and there would be nowhere to type.
 */
class OverlayBubbleService : Service() {

    private enum class UiState { LOADING, IDLE, RECORDING, TRANSCRIBING, POLISHING }

    private lateinit var windowManager: WindowManager
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var bubble: LinearLayout
    private lateinit var micButton: TextView
    private lateinit var recordingRow: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var stopPill: TextView
    private lateinit var cancelPill: TextView
    private lateinit var micDot: GradientDrawable
    private lateinit var busyBubble: FrameLayout
    private lateinit var polishBubble: FrameLayout

    /**
     * Where the user put the bubble. The window resizes as the state changes,
     * so the drawn position is sometimes clamped inward; keeping the intended
     * position separately is what lets it return there when the window shrinks
     * back, instead of creeping a little further each time.
     */
    private var anchorX = 0
    private var anchorY = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile private var pipeline: SpeechPipeline? = null
    private var audioRecord: AudioRecord? = null
    private var micJob: Job? = null
    private var finalizeJob: Job? = null
    @Volatile private var recording = false

    /**
     * Open only for the dictation currently being captured or finalized.
     * Results that arrive outside it belong to a committed or cancelled
     * dictation and must not leak into the next one.
     */
    @Volatile private var sessionActive = false

    /** When the engine last emitted anything; drives [drainEngine]. */
    @Volatile private var lastEngineEventAt = 0L

    /** When the current dictation started; scales the finalize wait. */
    @Volatile private var recordingStartedAt = 0L

    /** Null until the optional cleanup model has downloaded and loaded. */
    @Volatile private var cleanupRuntime: CleanupRuntime? = null

    /** Human-readable cleanup state, shown on the setup screen. */
    @Volatile private var cleanupStatus = "off"

    /** What cleanup did to the most recent dictation, for the setup screen. */
    @Volatile private var lastCleanupReport: String? = null

    /** Pause tolerance the live pipeline was built with, for staleness checks. */
    @Volatile private var loadedPauseToleranceSec = OverlaySettings.DEFAULT_PAUSE_SEC

    private var state = UiState.LOADING
    private val transcript = StringBuilder()
    private var partialText = ""
    private var speechActive = false

    internal var pipelineFactory: (SpeechConfig) -> SpeechPipeline = { SpeechPipeline(it) }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        liveInstance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundNotification()
        addBubble()
        loadPipeline()
        loadCleanupModel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        if (liveInstance === this) liveInstance = null
        stopMicrophone()
        finalizeJob?.cancel()
        scope.cancel()
        pipeline?.let { p ->
            try { p.stop() } catch (_: Exception) {}
            try { p.close() } catch (_: Exception) {}
        }
        pipeline = null
        try { cleanupRuntime?.close() } catch (_: Exception) {}
        cleanupRuntime = null
        if (this::bubble.isInitialized && bubble.isAttachedToWindow) {
            try { windowManager.removeView(bubble) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Overlay UI
    // -------------------------------------------------------------------------

    private fun addBubble() {
        micButton = TextView(this).apply {
            // The dot is a drawable, not a glyph: text centering depends on
            // font ascent/descent, which left it visibly off-center.
            val size = dp(56)
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = micBackground()
        }

        statusView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#BBBBBB"))
            maxLines = 2
            setPadding(dp(14), 0, dp(6), 0)
            gravity = Gravity.CENTER_VERTICAL
            maxWidth = dp(150)
        }

        // Icon-only so the recording state stays barely wider than the idle
        // bubble — a labelled row ran off the screen edge when docked right.
        stopPill = iconButton("■", "#4CAF50") { stopAndCommit() }
        cancelPill = iconButton("✕", "#FF5252") { cancelRecording() }

        recordingRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            addView(statusView)
            addView(stopPill)
            addView(cancelPill)
        }

        // Shown between Stop and the text landing in the field, so the wait
        // for the final transcription isn't a silent dead spot.
        busyBubble = FrameLayout(this).apply {
            val size = dp(56)
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = circle(Color.parseColor(BUBBLE_BG))
            visibility = View.GONE
            addView(
                ProgressBar(this@OverlayBubbleService).apply {
                    isIndeterminate = true
                    indeterminateTintList =
                        ColorStateList.valueOf(Color.parseColor(ACCENT))
                    layoutParams = FrameLayout.LayoutParams(dp(26), dp(26)).apply {
                        gravity = Gravity.CENTER
                    }
                }
            )
        }

        // Post-processing gets its own indicator: the transcribing wait and
        // the LLM wait have very different durations, and a single spinner
        // covering both looks like one long stall.
        polishBubble = FrameLayout(this).apply {
            val size = dp(56)
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = circle(Color.parseColor(BUBBLE_BG), POLISH)
            visibility = View.GONE
            addView(
                ProgressBar(this@OverlayBubbleService).apply {
                    isIndeterminate = true
                    indeterminateTintList =
                        ColorStateList.valueOf(Color.parseColor(POLISH))
                    layoutParams = FrameLayout.LayoutParams(dp(26), dp(26)).apply {
                        gravity = Gravity.CENTER
                    }
                }
            )
        }

        bubble = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(micButton)
            addView(busyBubble)
            addView(polishBubble)
            addView(recordingRow)
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE keeps input focus (and the keyboard) on the app
            // underneath; without it the target text field deselects the
            // moment the bubble appears.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val (savedX, savedY) = OverlaySettings.bubblePosition(this@OverlayBubbleService)
            // First run starts at the right edge — under the thumb for most
            // right-handed use, and clear of the left-edge back gesture.
            anchorX = if (savedX != OverlaySettings.UNSET_POSITION) savedX
                else screenBounds().first - dp(56)
            anchorY = if (savedY != OverlaySettings.UNSET_POSITION) savedY else dp(240)
            x = anchorX
            y = anchorY
        }

        micButton.setOnTouchListener(DragTouchListener { startRecording() })
        windowManager.addView(bubble, layoutParams)
        render()
    }

    /**
     * Colour carried by the glyph, not the disc. A solid green and red circle
     * shouted over whatever app is underneath; this matches the idle bubble —
     * dark circle, coloured mark — so the overlay stays quiet while still
     * reading as stop and cancel at a glance.
     */
    private fun iconButton(glyph: String, color: String, onClick: () -> Unit) =
        TextView(this).apply {
            text = glyph
            textSize = 20f
            setTextColor(Color.parseColor(color))
            gravity = Gravity.CENTER
            // Font padding skews vertical centering of a lone glyph.
            includeFontPadding = false
            background = circle(Color.parseColor(BUBBLE_BG))
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                marginStart = dp(8)
            }
            setOnClickListener { onClick() }
        }

    /** Idle bubble: bordered circle with a concentric dot drawn as a shape. */
    private fun micBackground(): LayerDrawable {
        val base = circle(Color.parseColor(BUBBLE_BG))
        micDot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(ACCENT))
        }
        return LayerDrawable(arrayOf(base, micDot)).apply {
            val inset = dp(19)
            setLayerInset(1, inset, inset, inset, inset)
        }
    }

    private fun circle(fill: Int, stroke: String = BORDER) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        setStroke(dp(2), Color.parseColor(stroke))
    }

    private fun render() {
        when (state) {
            // Loading reuses the status row (without the buttons) so model
            // download progress is visible on the bubble itself.
            UiState.LOADING -> {
                polishBubble.visibility = View.GONE
                micButton.visibility = View.VISIBLE
                micDot.setColor(Color.parseColor("#555555"))
                busyBubble.visibility = View.GONE
                recordingRow.visibility = View.VISIBLE
                statusView.visibility = View.VISIBLE
                stopPill.visibility = View.GONE
                cancelPill.visibility = View.GONE
            }
            UiState.IDLE -> {
                polishBubble.visibility = View.GONE
                micButton.visibility = View.VISIBLE
                micDot.setColor(Color.parseColor(ACCENT))
                busyBubble.visibility = View.GONE
                recordingRow.visibility = View.GONE
            }
            // Buttons only — no label, no status text.
            UiState.RECORDING -> {
                polishBubble.visibility = View.GONE
                micButton.visibility = View.GONE
                busyBubble.visibility = View.GONE
                recordingRow.visibility = View.VISIBLE
                statusView.visibility = View.GONE
                stopPill.visibility = View.VISIBLE
                cancelPill.visibility = View.VISIBLE
            }
            UiState.TRANSCRIBING -> {
                micButton.visibility = View.GONE
                busyBubble.visibility = View.VISIBLE
                polishBubble.visibility = View.GONE
                recordingRow.visibility = View.GONE
            }
            UiState.POLISHING -> {
                micButton.visibility = View.GONE
                busyBubble.visibility = View.GONE
                polishBubble.visibility = View.VISIBLE
                recordingRow.visibility = View.GONE
            }
        }
        applyAnchor()
    }

    /**
     * Pin the bubble to whichever edge it is docked to.
     *
     * The window is WRAP_CONTENT, so it changes width as the state changes.
     * Anchoring the *docked edge* rather than the left corner is what makes it
     * expand inward and land back exactly where it started — clamping alone
     * shifted a right-docked bubble left and never moved it back.
     */
    private fun applyAnchor() {
        bubble.post {
            if (!bubble.isAttachedToWindow) return@post
            val (screenW, screenH) = screenBounds()
            // Clamp for drawing only — the anchor itself is left alone, so
            // growing into the stop/cancel row and shrinking back returns the
            // bubble to exactly where it was put.
            moveNow(
                anchorX.coerceIn(0, (screenW - bubble.width).coerceAtLeast(0)),
                anchorY.coerceIn(0, (screenH - bubble.height).coerceAtLeast(0)),
            )
        }
    }

    private fun moveNow(x: Int, y: Int) {
        if (x == layoutParams.x && y == layoutParams.y) return
        layoutParams.x = x
        layoutParams.y = y
        updateLayout()
    }

    private fun updateLayout() {
        try {
            windowManager.updateViewLayout(bubble, layoutParams)
        } catch (_: IllegalArgumentException) {
            // Detached mid-update — service is shutting down.
        }
    }

    private fun screenBounds(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = resources.displayMetrics
            m.widthPixels to m.heightPixels
        }

    private fun setState(next: UiState) {
        state = next
        render()
    }

    private fun setStatus(text: String) {
        statusView.text = text
    }

    /** Moves the bubble on drag, fires [onTap] on a tap that never became one. */
    private inner class DragTouchListener(private val onTap: () -> Unit) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@OverlayBubbleService).scaledTouchSlop
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var dragging = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = layoutParams.x
                    startY = layoutParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (!dragging && kotlin.math.hypot(dx.toFloat(), dy.toFloat()) < slop) {
                        return true
                    }
                    dragging = true
                    val bounds = screenBounds()
                    anchorX = (startX + dx)
                        .coerceIn(0, (bounds.first - bubble.width).coerceAtLeast(0))
                    anchorY = (startY + dy)
                        .coerceIn(0, (bounds.second - bubble.height).coerceAtLeast(0))
                    layoutParams.x = anchorX
                    layoutParams.y = anchorY
                    updateLayout()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        // Left where it was dropped, and remembered, so the
                        // overlay comes back in the same place next time.
                        OverlaySettings.setBubblePosition(
                            this@OverlayBubbleService, anchorX, anchorY)
                    } else {
                        view.performClick()
                        onTap()
                    }
                    return true
                }
            }
            return false
        }
    }

    // -------------------------------------------------------------------------
    // Pipeline
    // -------------------------------------------------------------------------

    private fun loadPipeline() {
        setState(UiState.LOADING)
        scope.launch(Dispatchers.Default) {
            try {
                val modelDir = ModelManager.ensureModels(
                    context = applicationContext,
                    precision = ModelPrecision.INT8,
                    sttModel = STT_MODEL,
                ) { progress ->
                    scope.launch { setStatus("${progress.completed}/${progress.totalFiles}") }
                }

                // Baked into the native pipeline at creation — changing it
                // later means rebuilding, which is why the setup screen
                // restarts the overlay when this changes.
                val pauseTolerance = OverlaySettings.pauseToleranceSec(this@OverlayBubbleService)
                loadedPauseToleranceSec = pauseTolerance

                val config = SpeechConfig(
                    modelDir = modelDir,
                    sttModel = STT_MODEL,
                    precision = ModelPrecision.INT8,
                    pipelineMode = PipelineMode.TRANSCRIBE_ONLY,
                    emitPartialTranscriptions = true,
                    partialTranscriptionInterval = 0.5f,
                    endOfSpeechSilenceSec = pauseTolerance,
                )

                val p = pipelineFactory(config)
                pipeline = p
                launch { collectEvents(p) }
                p.start()

                withContext(Dispatchers.Main) { setState(UiState.IDLE) }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Overlay pipeline init failed", e)
                withContext(Dispatchers.Main) {
                    toast("Speech models failed to load: ${e.message ?: e.javaClass.simpleName}")
                    stopSelf()
                }
            }
        }
    }

    private suspend fun collectEvents(p: SpeechPipeline) {
        p.events.collect { event ->
            // Tracked regardless of the session gate — the drain needs to know
            // when the engine last produced anything, including results it is
            // about to discard.
            when (event) {
                is SpeechEvent.SpeechStarted,
                is SpeechEvent.SpeechEnded,
                is SpeechEvent.PartialTranscription,
                is SpeechEvent.TranscriptionCompleted ->
                    lastEngineEventAt = System.currentTimeMillis()
                else -> {}
            }

            when (event) {
                is SpeechEvent.SpeechStarted -> withContext(Dispatchers.Main) {
                    if (!sessionActive) return@withContext
                    speechActive = true
                }

                // Partials drive the finalize wait, but are never displayed —
                // the bubble deliberately shows no dictated text.
                is SpeechEvent.PartialTranscription -> withContext(Dispatchers.Main) {
                    if (!sessionActive) return@withContext
                    partialText = event.text
                }

                is SpeechEvent.SpeechEnded -> withContext(Dispatchers.Main) {
                    if (!sessionActive) return@withContext
                    speechActive = false
                }

                // The gate matters most here: the engine keeps draining audio
                // after the mic stops, so a late result from a committed or
                // cancelled dictation would otherwise land in the next one.
                is SpeechEvent.TranscriptionCompleted -> withContext(Dispatchers.Main) {
                    if (!sessionActive) {
                        Log.d(TAG, "Dropping transcription from a finished session")
                        return@withContext
                    }
                    partialText = ""
                    speechActive = false
                    if (event.text.isNotBlank()) {
                        if (transcript.isNotEmpty()) transcript.append(" ")
                        transcript.append(event.text.trim())
                    }
                }

                is SpeechEvent.Error -> withContext(Dispatchers.Main) {
                    Log.w(TAG, "Pipeline error: ${event.message}")
                }

                else -> {}
            }
        }
    }

    /**
     * How much silence the flush must push to close an utterance.
     *
     * The VAD only ends a segment after [SpeechConfig.endOfSpeechSilenceSec] of
     * silence, so a fixed amount is wrong the moment that value is
     * user-configurable: anything shorter than the configured tolerance leaves
     * the utterance open forever and the dictation comes back empty.
     */
    private fun silenceFrames(): Int {
        val seconds = loadedPauseToleranceSec + SILENCE_MARGIN_SEC
        return ceil(seconds * SAMPLE_RATE / FRAME_SAMPLES).toInt()
    }

    /**
     * Push silence so the VAD sees end-of-speech and closes whatever utterance
     * was still open when the mic stopped. Without this the audio sits inside
     * the engine and gets transcribed into the *next* dictation.
     */
    private fun pushSilence() {
        val silence = FloatArray(FRAME_SAMPLES)
        repeat(silenceFrames()) {
            try {
                pipeline?.pushAudio(silence) ?: return
            } catch (e: Exception) {
                Log.w(TAG, "Silence flush stopped early", e)
                return
            }
        }
    }

    /**
     * Wait for the utterance to actually finish transcribing after the flush.
     *
     * Watching `speechActive`/`partialText` alone is not enough: both are false
     * in the window between SpeechEnded and TranscriptionCompleted, so a wait
     * keyed on them returns *before* the result exists and commits nothing.
     * Short phrases that never emit a partial land in the same hole. Waiting
     * for the engine to fall quiet covers both, since every event it emits
     * pushes the quiet window back.
     */
    private suspend fun awaitFinalTranscription() {
        val flushedAt = System.currentTimeMillis()
        val timeout = finalizeTimeoutMs()
        val deadline = flushedAt + timeout
        while (System.currentTimeMillis() < deadline) {
            val now = System.currentTimeMillis()
            val settled = now - flushedAt >= FINALIZE_SETTLE_MS
            val quiet = now - lastEngineEventAt >= FINALIZE_QUIET_MS
            if (settled && quiet && !engineBusy()) return
            delay(50)
        }
        Log.w(TAG, "Final transcription did not settle within ${timeout}ms")
    }

    /**
     * True while the engine still owes us a result.
     *
     * Silence alone cannot tell "finished" from "busy": transcribing a long
     * segment emits nothing for its whole duration, so a wait keyed only on
     * quiet gives up mid-decode and the result is then dropped as belonging to
     * a closed session. The pipeline's own state is the authority.
     */
    private fun engineBusy(): Boolean {
        if (speechActive || partialText.isNotEmpty()) return true
        return try {
            pipeline?.state == PipelineState.Transcribing
        } catch (e: Exception) {
            Log.w(TAG, "Could not read pipeline state", e)
            false
        }
    }

    /**
     * Longer dictations need longer to decode, and speech-core force-splits
     * anything past 15 s into multiple utterances that queue up behind each
     * other — so a fixed cap truncates exactly the long dictations it should
     * protect.
     */
    private fun finalizeTimeoutMs(): Long {
        val recordedMs = System.currentTimeMillis() - recordingStartedAt
        val scaled = FINALIZE_TIMEOUT_MS + recordedMs / FINALIZE_SCALE_DIVISOR
        return scaled.coerceAtMost(FINALIZE_TIMEOUT_CAP_MS)
    }

    /**
     * Wait until the engine stops emitting. speech-core never clears its
     * pending-utterance queue on stop/start, so anything still queued when a
     * new dictation begins would surface inside it. Holding the UI busy until
     * the engine falls quiet is what actually prevents the leak.
     */
    private suspend fun drainEngine() {
        val cap = System.currentTimeMillis() + DRAIN_CAP_MS
        while (System.currentTimeMillis() < cap) {
            val quiet = System.currentTimeMillis() - lastEngineEventAt > DRAIN_QUIET_MS
            if (quiet && !engineBusy()) return
            delay(50)
        }
        Log.w(TAG, "Engine still emitting after ${DRAIN_CAP_MS}ms; giving up on drain")
    }

    /**
     * Run the optional LLM cleanup pass, returning [text] unchanged whenever
     * cleanup is off, unavailable, too slow, or produces something that fails
     * [TranscriptCleanup.accept]. Dictation must never be worse for having
     * this enabled, so every failure path keeps the raw transcript.
     */
    private fun cleanUp(text: String): String {
        val runtime = cleanupRuntime
        if (runtime == null) {
            lastCleanupReport = "not run — model $cleanupStatus"
            return text
        }
        return try {
            val started = System.currentTimeMillis()
            val raw = runtime.generate(TranscriptCleanup.buildPrompt(text))
            val elapsed = System.currentTimeMillis() - started
            val verdict = TranscriptCleanup.evaluate(text, raw)
            lastCleanupReport = buildString {
                appendLine(if (verdict.accepted) "ACCEPTED (${elapsed}ms)" else "REJECTED — ${verdict.reason}")
                appendLine()
                appendLine("Transcript:")
                appendLine(text)
                appendLine()
                appendLine("Model output:")
                appendLine(raw.ifBlank { "(empty)" })
                appendLine()
                appendLine("Inserted:")
                append(verdict.text)
            }
            Log.i(TAG, "Cleanup ${verdict.reason}; raw model output: ${raw.take(200)}")
            verdict.text
        } catch (e: Exception) {
            lastCleanupReport = "threw ${e.javaClass.simpleName}: ${e.message}"
            Log.w(TAG, "Cleanup failed; inserting the raw transcript", e)
            text
        }
    }

    /**
     * Download and load the cleanup model when the setting is on. Kept lazy
     * and entirely separate from the speech pipeline: the overlay must stay
     * usable while this 283 MB bundle downloads, and must keep working if it
     * never arrives.
     */
    private fun loadCleanupModel() {
        if (!OverlaySettings.cleanupEnabled(this)) {
            cleanupStatus = "off"
            return
        }
        cleanupStatus = "downloading…"
        scope.launch(Dispatchers.Default) {
            try {
                ModelManager.ensureLlmModels(applicationContext, CLEANUP_MODEL)
                cleanupStatus = "loading model…"
                val path = ModelManager.llmModelFile(applicationContext, CLEANUP_MODEL)
                val runtime = LiteRtCleanupRuntime(path)
                runtime.initialize()
                cleanupRuntime = runtime
                cleanupStatus = "ready"
                Log.i(TAG, "Transcript cleanup ready")
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                // Non-fatal by design — dictation continues without cleanup.
                cleanupStatus = "unavailable: ${e.message ?: e.javaClass.simpleName}"
                Log.w(TAG, "Cleanup model unavailable", e)
            }
        }
    }

    /** Everything heard this session, including any unfinalized partial. */
    private fun dictatedText(): String =
        (transcript.toString() + if (partialText.isNotEmpty()) " $partialText" else "").trim()

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("Dictation", text))
    }

    // -------------------------------------------------------------------------
    // Recording
    // -------------------------------------------------------------------------

    private fun startRecording() {
        if (state != UiState.IDLE) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            toast("Microphone permission is required")
            return
        }

        val sr = 16000
        val bufSize = AudioRecord.getMinBufferSize(
            sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (bufSize <= 0) {
            toast("Microphone unavailable")
            return
        }

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, bufSize * 4,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            toast("Microphone init failed")
            return
        }
        audioRecord = record

        transcript.clear()
        partialText = ""
        speechActive = false
        sessionActive = true
        recordingStartedAt = System.currentTimeMillis()
        record.startRecording()
        recording = true
        setState(UiState.RECORDING)
        setStatus("listening…")

        micJob = scope.launch(Dispatchers.IO) {
            val buf = FloatArray(512)
            while (recording && isActive) {
                val read = try {
                    record.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                } catch (_: IllegalStateException) {
                    break // released mid-read
                }
                if (read > 0) {
                    val samples = if (read == buf.size) buf else buf.copyOf(read)
                    try {
                        pipeline?.pushAudio(samples) ?: break
                    } catch (e: Exception) {
                        Log.w(TAG, "Dropping mic frame after pipeline shutdown", e)
                        break
                    }
                } else if (read < 0) {
                    Log.w(TAG, "AudioRecord.read returned $read")
                    break
                }
            }
        }
    }

    /** Stop: flush the tail of the utterance, then type it into the focused field. */
    private fun stopAndCommit() {
        if (state != UiState.RECORDING) return
        stopMicrophone()
        setState(UiState.TRANSCRIBING)

        finalizeJob?.cancel()
        finalizeJob = scope.launch {
            // The user almost always taps Stop mid-utterance, before the VAD
            // has closed the segment. Flushing silence closes it now, so the
            // tail is transcribed into *this* dictation instead of lingering.
            withContext(Dispatchers.Default) { pushSilence() }

            awaitFinalTranscription()

            val text = dictatedText()
            // Close the session before the insert, not after: everything the
            // engine emits from here on belongs to a finished dictation.
            sessionActive = false
            transcript.clear()
            partialText = ""

            // Swallow anything still queued behind the committed result, so
            // it cannot reappear in the next dictation.
            drainEngine()

            if (text.isBlank()) {
                setState(UiState.IDLE)
                toast("Nothing heard")
                return@launch
            }
            // Only switch indicators when cleanup can actually run, so the
            // amber state never flashes for a dictation that skips it.
            if (cleanupRuntime != null) setState(UiState.POLISHING)
            val finalText = withContext(Dispatchers.Default) { cleanUp(text) }

            val result = withContext(Dispatchers.Default) {
                DictationAccessibilityService.insertIntoFocusedField(finalText)
            }
            // Only now go idle — staying busy through the insert stops a second
            // dictation from starting on top of this one.
            setState(UiState.IDLE)
            when (result) {
                DictationAccessibilityService.InsertResult.INSERTED -> {}
                // Never drop what the user said — park it on the clipboard so
                // a long-press paste still gets them there.
                DictationAccessibilityService.InsertResult.NO_FOCUSED_FIELD -> {
                    copyToClipboard(finalText)
                    toast("No text field focused — copied to clipboard")
                }
                DictationAccessibilityService.InsertResult.SERVICE_DISABLED -> {
                    copyToClipboard(finalText)
                    toast("Accessibility service off — copied to clipboard")
                }
            }
        }
    }

    /** Cancel: drop everything heard so far and leave the target untouched. */
    private fun cancelRecording() {
        if (state != UiState.RECORDING) return
        finalizeJob?.cancel()
        // Before clearing, so a result still in flight can't refill it.
        sessionActive = false
        stopMicrophone()
        transcript.clear()
        partialText = ""
        speechActive = false

        // Cancelled audio still has to be flushed out of the engine, or it
        // resurfaces in the next dictation — exactly what Cancel must prevent.
        // The busy state keeps a new recording from starting mid-drain.
        setState(UiState.TRANSCRIBING)
        finalizeJob = scope.launch {
            withContext(Dispatchers.Default) { pushSilence() }
            drainEngine()
            setState(UiState.IDLE)
        }
    }

    private fun stopMicrophone() {
        recording = false
        micJob?.cancel()
        micJob = null
        audioRecord?.let { record ->
            try { record.stop() } catch (_: Exception) {}
            record.release()
        }
        audioRecord = null
    }

    // -------------------------------------------------------------------------
    // Plumbing
    // -------------------------------------------------------------------------

    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Dictation overlay",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }

        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayBubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Dictation overlay")
            .setContentText("Tap the bubble to dictate")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null as Icon?, "Stop overlay", stopIntent,
                ).build()
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "OverlayBubble"
        private const val CHANNEL_ID = "dictation_overlay"
        private const val NOTIFICATION_ID = 4711
        private const val FINALIZE_TIMEOUT_MS = 4000L
        /** Extra finalize budget: 1 s per 5 s recorded. */
        private const val FINALIZE_SCALE_DIVISOR = 5L
        private const val FINALIZE_TIMEOUT_CAP_MS = 30000L
        /** Minimum wait after the flush before concluding nothing was heard. */
        private const val FINALIZE_SETTLE_MS = 700L
        /** Engine must be silent this long for the utterance to count as done. */
        private const val FINALIZE_QUIET_MS = 500L
        private const val BUBBLE_BG = "#1E1E1E"
        private const val BORDER = "#8A8A8A"
        private const val ACCENT = "#4FC3F7"
        /** Amber, for the post-processing wait. */
        private const val POLISH = "#FFB300"
        private const val FRAME_SAMPLES = 512
        private const val SAMPLE_RATE = 16000
        /** Pushed on top of the pause tolerance so the VAD reliably trips. */
        private const val SILENCE_MARGIN_SEC = 0.6f
        private const val DRAIN_QUIET_MS = 400L
        private const val DRAIN_CAP_MS = 2500L
        private val STT_MODEL = SttModel.PARAKEET

        /**
         * Instruction-tuned rather than tool-call tuned. FunctionGemma is a
         * similar size but fine-tuned to emit call syntax, which made it a
         * poor fit for rewriting prose.
         */
        private val CLEANUP_MODEL = LlmModel.SMOLLM2_360M_IT

        const val ACTION_STOP = "audio.soniqo.speech.demo.overlay.STOP"

        @Volatile
        private var running = false

        val isRunning: Boolean get() = running

        @Volatile
        private var liveInstance: OverlayBubbleService? = null

        /**
         * True when a running overlay's pipeline was built with a pause
         * tolerance that no longer matches the saved setting.
         */
        /** Cleanup state of the running overlay, or null if it isn't running. */
        fun cleanupStatus(): String? = liveInstance?.cleanupStatus

        /** What cleanup did to the last real dictation, or null if none yet. */
        fun lastCleanupReport(): String? = liveInstance?.lastCleanupReport

        /**
         * Run cleanup on [text] and report what happened, so the setup screen
         * can show whether the model ran, what it returned, and why the guard
         * accepted or rejected it.
         */
        fun diagnoseCleanup(text: String): String {
            val service = liveInstance ?: return "Overlay is not running."
            val runtime = service.cleanupRuntime
                ?: return "Cleanup model not loaded (${service.cleanupStatus})."
            return try {
                val started = System.currentTimeMillis()
                val raw = runtime.generate(TranscriptCleanup.buildPrompt(text))
                val elapsed = System.currentTimeMillis() - started
                val verdict = TranscriptCleanup.evaluate(text, raw)
                buildString {
                    appendLine("Input:")
                    appendLine(text)
                    appendLine()
                    appendLine("Model output (${elapsed}ms):")
                    appendLine(raw.ifBlank { "(empty)" })
                    appendLine()
                    appendLine(if (verdict.accepted) "ACCEPTED" else "REJECTED — ${verdict.reason}")
                    appendLine()
                    appendLine("Would insert:")
                    append(verdict.text)
                }
            } catch (e: Exception) {
                "Cleanup threw ${e.javaClass.simpleName}: ${e.message}"
            }
        }

        fun needsRestartFor(context: Context): Boolean {
            val service = liveInstance ?: return false
            return service.loadedPauseToleranceSec !=
                OverlaySettings.pauseToleranceSec(context)
        }

        fun start(context: Context) {
            val intent = Intent(context, OverlayBubbleService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayBubbleService::class.java))
        }
    }
}
