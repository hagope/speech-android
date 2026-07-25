package audio.soniqo.speech.demo.overlay

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Routes capture to a Bluetooth headset microphone.
 *
 * Bluetooth output (A2DP) and Bluetooth input are separate paths: Android does
 * not record from a headset just because one is connected, so the route has to
 * be requested before the recorder is opened.
 *
 * LE Audio is preferred where the headset offers it. Classic SCO is narrowband
 * and heavily compressed, which costs recognition accuracy; LE Audio is closer
 * to what the speech models expect.
 */
object BluetoothMic {

    /** Input device types we can use, best first. */
    val PREFERRED_TYPES: List<Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        } else {
            listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        }

    /**
     * Pick the best available type from [available], or null if none qualify.
     * Split out from the routing calls so the priority order is testable.
     */
    fun preferredType(available: List<Int>, preference: List<Int> = PREFERRED_TYPES): Int? =
        preference.firstOrNull { it in available }

    /** The best Bluetooth input device currently offered, if any. */
    fun findDevice(audioManager: AudioManager): AudioDeviceInfo? {
        val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices
        } else {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        }
        val type = preferredType(devices.map { it.type }) ?: return null
        return devices.firstOrNull { it.type == type }
    }

    /**
     * Route communication audio to [device]. Returns false if the platform
     * refuses, in which case the caller should fall back to the built-in mic
     * rather than record silence.
     */
    fun activate(audioManager: AudioManager, device: AudioDeviceInfo): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.setCommunicationDevice(device) }
                .onFailure { Log.w(TAG, "setCommunicationDevice failed", it) }
                .getOrDefault(false)
        } else {
            legacyStartSco(audioManager)
        }
    }

    /**
     * Pre-API-31 path. SCO takes time to come up and audio captured before it
     * is ready is silence, so this blocks until the link reports connected.
     */
    @Suppress("DEPRECATION")
    private fun legacyStartSco(audioManager: AudioManager): Boolean {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true

        val deadline = System.currentTimeMillis() + SCO_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (audioManager.isBluetoothScoOn) return true
            Thread.sleep(SCO_POLL_MS)
        }
        Log.w(TAG, "Bluetooth SCO did not connect within ${SCO_TIMEOUT_MS}ms")
        return false
    }

    /** Hand the route back, so other apps are not left stuck on the headset. */
    @Suppress("DEPRECATION")
    fun release(audioManager: AudioManager) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
                audioManager.mode = AudioManager.MODE_NORMAL
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release the Bluetooth route", e)
        }
    }

    private const val TAG = "BluetoothMic"
    private const val SCO_TIMEOUT_MS = 3000L
    private const val SCO_POLL_MS = 100L
}
