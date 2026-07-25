package audio.soniqo.speech.demo.overlay

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Setup screen for the floating dictation bubble.
 *
 * The overlay needs three separate grants, each behind its own system screen:
 * microphone, "draw over other apps", and the accessibility service that does
 * the actual typing. This screen shows which are missing and starts the bubble
 * once they are all in place.
 */
class VoiceOverlayActivity : ComponentActivity() {

    private lateinit var micRow: TextView
    private lateinit var overlayRow: TextView
    private lateinit var a11yRow: TextView
    private lateinit var toggleButton: TextView
    private lateinit var testField: EditText
    private lateinit var pauseValueView: TextView
    private lateinit var cleanupToggle: TextView
    private lateinit var bluetoothToggle: TextView
    private lateinit var cleanupStatusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F0F0F"))
            setPadding(64, 160, 64, 64)
        }

        root.addView(TextView(this).apply {
            text = "Voice overlay"
            textSize = 24f
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, 16)
        })

        root.addView(TextView(this).apply {
            text = "A floating mic button over other apps. Tap it, speak, then " +
                "Stop to type the text into the focused field — or Cancel to discard it."
            textSize = 14f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 0, 0, 48)
        })

        micRow = permissionRow("1. Microphone") { requestMic() }
        overlayRow = permissionRow("2. Display over other apps") { openOverlaySettings() }
        a11yRow = permissionRow("3. Accessibility service (types the text)") { openA11ySettings() }
        root.addView(micRow)
        root.addView(overlayRow)
        root.addView(a11yRow)

        root.addView(pauseToleranceSection())
        root.addView(bluetoothSection())
        root.addView(cleanupSection())

        toggleButton = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(48, 36, 48, 36)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 48 }
            setOnClickListener { toggleOverlay() }
        }
        root.addView(toggleButton)
        root.addView(testSection())

        // The scratch field raises the keyboard, so the screen has to scroll.
        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(Color.parseColor("#0F0F0F"))
                isFillViewport = true
                addView(root)
            }
        )
    }

    /**
     * A scratch text field for trying the overlay without leaving the app.
     *
     * It is an ordinary EditText on purpose: dictating into it exercises the
     * same focused-field path as any third-party app, so if it works here the
     * accessibility service and the non-focusable overlay window are both
     * behaving.
     */
    private fun testSection(): LinearLayout {
        val heading = TextView(this).apply {
            text = "Try it"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 56, 0, 4)
        }

        val hintView = TextView(this).apply {
            text = "Tap this box, then tap the bubble and speak. " +
                "Stop types the text in; Cancel discards it."
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 0, 0, 12)
        }

        testField = EditText(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(32, 32, 32, 32)
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(heading)
            addView(hintView)
            addView(testField)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (this::cleanupToggle.isInitialized) renderCleanupToggle()
    }

    // -------------------------------------------------------------------------
    // Permission state
    // -------------------------------------------------------------------------

    private fun hasMic() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasOverlay() = Settings.canDrawOverlays(this)

    private fun hasAccessibility(): Boolean {
        // The service object only exists once the system has bound it, so this
        // reflects the real state rather than what the settings row claims.
        if (DictationAccessibilityService.isRunning) return true
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val self = ComponentName(this, DictationAccessibilityService::class.java)
        // Entries use either "pkg/pkg.Service" or the short "pkg/.Service";
        // unflattenFromString normalizes both.
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == self }
    }

    private fun refresh() {
        mark(micRow, "1. Microphone", hasMic())
        mark(overlayRow, "2. Display over other apps", hasOverlay())
        mark(a11yRow, "3. Accessibility service (types the text)", hasAccessibility())

        val ready = hasMic() && hasOverlay() && hasAccessibility()
        toggleButton.isEnabled = ready
        toggleButton.text = when {
            !ready -> "Grant the permissions above"
            OverlayBubbleService.isRunning -> "Hide overlay"
            else -> "Show overlay"
        }
        toggleButton.setTextColor(
            if (ready) Color.WHITE else Color.parseColor("#555555")
        )
    }

    private fun mark(view: TextView, label: String, granted: Boolean) {
        view.text = if (granted) "✓  $label" else "○  $label  — tap to grant"
        view.setTextColor(
            if (granted) Color.parseColor("#4CAF50") else Color.parseColor("#4FC3F7")
        )
    }

    /**
     * Pause tolerance slider. The value is compiled into the native pipeline
     * at creation, so changing it while the overlay runs needs a restart —
     * handled on release rather than on every slider tick.
     */
    private fun pauseToleranceSection(): LinearLayout {
        val current = OverlaySettings.pauseToleranceSec(this)

        val heading = TextView(this).apply {
            text = "Pause tolerance"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 48, 0, 4)
        }

        pauseValueView = TextView(this).apply {
            text = OverlaySettings.format(current)
            textSize = 14f
            setTextColor(Color.parseColor("#4FC3F7"))
        }

        val hint = TextView(this).apply {
            text = "How long you can pause mid-sentence before it finalizes. " +
                "Raise it if you get cut off while thinking or dictating numbers; " +
                "lower it for snappier short phrases."
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 8, 0, 8)
        }

        val slider = SeekBar(this).apply {
            max = OverlaySettings.steps
            progress = OverlaySettings.secondsToProgress(current)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    pauseValueView.text =
                        OverlaySettings.format(OverlaySettings.progressToSeconds(value))
                }

                override fun onStartTrackingTouch(bar: SeekBar) {}

                override fun onStopTrackingTouch(bar: SeekBar) {
                    val seconds = OverlaySettings.progressToSeconds(bar.progress)
                    OverlaySettings.setPauseToleranceSec(this@VoiceOverlayActivity, seconds)
                    applyPauseToleranceChange()
                }
            })
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(heading)
            addView(pauseValueView)
            addView(hint)
            addView(slider)
        }
    }

    /**
     * Bluetooth mic preference. Not automatic: headset mics are usually
     * narrower band than the phone's, so recognition can get worse.
     */
    private fun bluetoothSection(): LinearLayout {
        val heading = TextView(this).apply {
            text = "Bluetooth microphone"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 48, 0, 4)
        }

        val hintView = TextView(this).apply {
            text = "Record from a connected Bluetooth headset instead of the " +
                "phone. Useful in a car or a noisy room, but headset mics are " +
                "usually lower quality, so accuracy can drop. Falls back to the " +
                "phone mic if no headset is connected."
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 0, 0, 12)
        }

        bluetoothToggle = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(32, 28, 32, 28)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setOnClickListener {
                OverlaySettings.setBluetoothMicEnabled(
                    this@VoiceOverlayActivity,
                    !OverlaySettings.bluetoothMicEnabled(this@VoiceOverlayActivity),
                )
                renderBluetoothToggle()
            }
        }
        renderBluetoothToggle()

        val micButton = TextView(this).apply {
            text = "Show which mic was last used"
            textSize = 14f
            setTextColor(Color.parseColor("#4FC3F7"))
            setPadding(0, 16, 0, 8)
            setOnClickListener {
                showReport(
                    "Microphone",
                    OverlayBubbleService.lastMicReport()
                        ?: "No recording has started yet.",
                )
            }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(heading)
            addView(hintView)
            addView(bluetoothToggle)
            addView(micButton)
        }
    }

    private fun renderBluetoothToggle() {
        val on = OverlaySettings.bluetoothMicEnabled(this)
        bluetoothToggle.text = if (on) "On — tap to disable" else "Off — tap to enable"
        bluetoothToggle.setTextColor(
            if (on) Color.parseColor("#4CAF50") else Color.parseColor("#888888")
        )
    }

    /**
     * Opt-in LLM cleanup. Off by default: it costs a 283 MB download, memory
     * alongside the speech models, and latency on every dictation — and the
     * only bundle available today is tuned for tool calls rather than prose.
     */
    private fun cleanupSection(): LinearLayout {
        val heading = TextView(this).apply {
            text = "Clean up dictation (experimental)"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 48, 0, 4)
        }

        val hintView = TextView(this).apply {
            text = "Runs an on-device LLM over the transcript to fix punctuation " +
                "and remove fillers. Downloads 283 MB on first use and adds a " +
                "pause before the text appears. If the result looks wrong, the " +
                "raw transcript is inserted instead."
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 0, 0, 12)
        }

        cleanupToggle = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(32, 28, 32, 28)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setOnClickListener { toggleCleanup() }
        }
        renderCleanupToggle()

        cleanupStatusView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 12, 0, 0)
        }

        val testButton = TextView(this).apply {
            text = "Test cleanup on the text below"
            textSize = 14f
            setTextColor(Color.parseColor("#4FC3F7"))
            setPadding(0, 20, 0, 8)
            setOnClickListener { runCleanupDiagnostic() }
        }

        // The test button proves the model works in isolation; this shows what
        // happened during an actual dictation, which is a different question.
        val lastButton = TextView(this).apply {
            text = "Show last dictation's cleanup"
            textSize = 14f
            setTextColor(Color.parseColor("#4FC3F7"))
            setPadding(0, 8, 0, 8)
            setOnClickListener {
                showReport(
                    "Last dictation",
                    OverlayBubbleService.lastCleanupReport()
                        ?: "No dictation has run through cleanup yet.",
                )
            }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(heading)
            addView(hintView)
            addView(cleanupToggle)
            addView(cleanupStatusView)
            addView(testButton)
            addView(lastButton)
        }
    }

    /**
     * Show what the model actually returned and how the guard judged it.
     * Cleanup falls back silently by design, so without this there is no way
     * to tell "model never loaded" from "guard rejected the output".
     */
    private fun runCleanupDiagnostic() {
        val text = testField.text?.toString()?.trim().orEmpty().ifBlank {
            "um so send it uh on friday i mean monday"
        }
        Toast.makeText(this, "Running cleanup…", Toast.LENGTH_SHORT).show()
        Thread {
            val report = OverlayBubbleService.diagnoseCleanup(text)
            runOnUiThread { showReport("Cleanup diagnostic", report) }
        }.start()
    }

    private fun showReport(title: String, body: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun renderCleanupToggle() {
        if (this::cleanupStatusView.isInitialized) {
            val status = OverlayBubbleService.cleanupStatus()
            cleanupStatusView.text = when {
                status == null -> "Status: overlay not running"
                else -> "Status: $status"
            }
        }
        val on = OverlaySettings.cleanupEnabled(this)
        cleanupToggle.text = if (on) "On — tap to disable" else "Off — tap to enable"
        cleanupToggle.setTextColor(
            if (on) Color.parseColor("#4CAF50") else Color.parseColor("#888888")
        )
    }

    private fun toggleCleanup() {
        val next = !OverlaySettings.cleanupEnabled(this)
        OverlaySettings.setCleanupEnabled(this, next)
        renderCleanupToggle()
        // The model is loaded when the service starts, so a running overlay
        // has to restart to pick this up either way.
        if (OverlayBubbleService.isRunning) {
            OverlayBubbleService.stop(this)
            OverlayBubbleService.start(this)
            val message = if (next) {
                "Reloading overlay — the model downloads in the background"
            } else {
                "Reloading overlay to apply…"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * A live overlay holds a pipeline built with the old value, so restart it.
     * Models are already cached, so this is a reload rather than a download.
     */
    private fun applyPauseToleranceChange() {
        if (!OverlayBubbleService.isRunning) return
        if (!OverlayBubbleService.needsRestartFor(this)) return
        OverlayBubbleService.stop(this)
        OverlayBubbleService.start(this)
        Toast.makeText(this, "Reloading overlay to apply…", Toast.LENGTH_SHORT).show()
    }

    private fun permissionRow(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 16f
        setPadding(0, 24, 0, 24)
        setOnClickListener { onClick() }
    }

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    private fun requestMic() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), REQUEST_MIC)
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
        )
    }

    private fun openA11ySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun toggleOverlay() {
        if (OverlayBubbleService.isRunning) {
            OverlayBubbleService.stop(this)
        } else {
            OverlayBubbleService.start(this)
            // Deliberately stay in the foreground: the scratch field below is
            // the quickest way to confirm the overlay works before trusting it
            // in a real app. Home dismisses this screen when they're ready.
            testField.requestFocus()
        }
        toggleButton.postDelayed({ refresh() }, 300)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC) refresh()
    }

    private companion object {
        const val REQUEST_MIC = 7
    }
}
