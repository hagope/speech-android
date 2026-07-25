package audio.soniqo.speech.demo.overlay

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BluetoothMicTest {

    private val preference = listOf(
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    )

    @Test
    fun prefersLeAudioOverClassicSco() {
        // Both on offer: LE Audio is wider band, so it wins.
        val available = listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
        assertEquals(
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            BluetoothMic.preferredType(available, preference),
        )
    }

    @Test
    fun fallsBackToScoWhenLeAudioIsAbsent() {
        assertEquals(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            BluetoothMic.preferredType(listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO), preference),
        )
    }

    @Test
    fun ignoresNonBluetoothInputs() {
        val available = listOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
        )
        assertNull(BluetoothMic.preferredType(available, preference))
    }

    @Test
    fun noDevicesMeansNoPreference() {
        assertNull(BluetoothMic.preferredType(emptyList(), preference))
    }
}
