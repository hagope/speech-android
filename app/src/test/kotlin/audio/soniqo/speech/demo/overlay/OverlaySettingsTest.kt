package audio.soniqo.speech.demo.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlaySettingsTest {

    @Test
    fun sliderSpansTheWholeRange() {
        assertEquals(OverlaySettings.MIN_PAUSE_SEC, OverlaySettings.progressToSeconds(0), EPS)
        assertEquals(
            OverlaySettings.MAX_PAUSE_SEC,
            OverlaySettings.progressToSeconds(OverlaySettings.steps),
            EPS,
        )
    }

    @Test
    fun progressAndSecondsRoundTrip() {
        for (progress in 0..OverlaySettings.steps) {
            val seconds = OverlaySettings.progressToSeconds(progress)
            assertEquals(progress, OverlaySettings.secondsToProgress(seconds))
        }
    }

    @Test
    fun stepsAreExactTenths() {
        // Guards against float drift producing values like 0.70000005.
        for (progress in 0..OverlaySettings.steps) {
            val seconds = OverlaySettings.progressToSeconds(progress)
            val tenths = Math.round(seconds * 10f)
            assertEquals(tenths / 10f, seconds, 0f)
        }
    }

    @Test
    fun outOfRangeProgressIsClamped() {
        assertEquals(OverlaySettings.MIN_PAUSE_SEC, OverlaySettings.progressToSeconds(-5), EPS)
        assertEquals(
            OverlaySettings.MAX_PAUSE_SEC,
            OverlaySettings.progressToSeconds(OverlaySettings.steps + 5),
            EPS,
        )
    }

    @Test
    fun clampKeepsValuesInRange() {
        assertEquals(OverlaySettings.MIN_PAUSE_SEC, OverlaySettings.clampPause(0f), EPS)
        assertEquals(OverlaySettings.MAX_PAUSE_SEC, OverlaySettings.clampPause(99f), EPS)
        assertEquals(1.0f, OverlaySettings.clampPause(1.0f), EPS)
    }

    @Test
    fun clampSnapsToNearestStep() {
        assertEquals(0.8f, OverlaySettings.clampPause(0.83f), EPS)
        assertEquals(0.9f, OverlaySettings.clampPause(0.87f), EPS)
    }

    @Test
    fun defaultMatchesTheEngineDefault() {
        // SpeechConfig.endOfSpeechSilenceSec defaults to 0.5s; the overlay must
        // not silently change behaviour before the user touches the slider.
        assertEquals(0.5f, OverlaySettings.DEFAULT_PAUSE_SEC, EPS)
        assertEquals(
            OverlaySettings.DEFAULT_PAUSE_SEC,
            OverlaySettings.clampPause(OverlaySettings.DEFAULT_PAUSE_SEC),
            EPS,
        )
    }

    @Test
    fun formatsWithOneDecimal() {
        assertEquals("0.5 s", OverlaySettings.format(0.5f))
        assertEquals("1.2 s", OverlaySettings.format(1.2f))
        assertEquals("2.0 s", OverlaySettings.format(2.0f))
    }

    private companion object {
        const val EPS = 1e-4f
    }
}
