package audio.soniqo.speech

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Measures Parakeet TDT against Whisper Small on identical audio.
 *
 * Both models transcribe the same Kokoro-synthesized utterances, so the
 * comparison is not confounded by different recordings. Reports decode time,
 * wall-clock to the final result, native memory held after loading, and the
 * transcripts themselves — the numbers that decide whether an
 * encoder-decoder model is viable on this hardware, and by extension whether
 * a Canary wrapper would be worth writing.
 *
 * Results are logged under [TAG] rather than asserted: this is a measurement,
 * and the only failure worth reporting is a model producing nothing at all.
 */
@RunWith(AndroidJUnit4::class)
class SttModelComparisonTest {

    private val phrases = listOf(
        "The quick brown fox jumps over the lazy dog.",
        "Send the quarterly report to Marcus before the meeting on Friday.",
        "I think we should meet on Monday morning to go over the numbers.",
    )

    @Test
    fun compareParakeetAndWhisper() = runBlocking {
        val results = linkedMapOf<SttModel, List<Utterance>>()
        val models = listOf(SttModel.PARAKEET, SttModel.WHISPER_SMALL, SttModel.CANARY_180M)
        for (model in models) {
            results[model] = measure(model)
        }

        Log.i(TAG, "=".repeat(72))
        for ((model, utterances) in results) {
            val decode = utterances.map { it.sttMs }
            val wall = utterances.map { it.wallMs }
            Log.i(TAG, "$model")
            Log.i(TAG, "  decode ms : ${decode.joinToString(", ") { "%.0f".format(it) }}" +
                "  (mean ${"%.0f".format(decode.average())})")
            Log.i(TAG, "  wall ms   : ${wall.joinToString(", ")}  (mean ${wall.average().toInt()})")
            utterances.forEach { Log.i(TAG, "  text      : ${it.text}") }
        }
        Log.i(TAG, "=".repeat(72))

        results.forEach { (model, utterances) ->
            assertTrue("$model produced no transcriptions", utterances.isNotEmpty())
            assertTrue(
                "$model transcribed nothing",
                utterances.any { it.text.isNotBlank() },
            )
        }
    }

    private data class Utterance(val text: String, val sttMs: Float, val wallMs: Long)

    private suspend fun measure(model: SttModel): List<Utterance> {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = ModelManager.ensureModels(ctx, sttModel = model)

        // Synthesize with the TTS bundle from the default cache so both runs
        // transcribe byte-identical audio.
        val ttsDir = ModelManager.ensureModels(ctx, sttModel = SttModel.PARAKEET)
        val clips = SpeechSynthesizer(
            SpeechSynthesizerConfig(modelDir = ttsDir, useNnapi = false)
        ).use { synth ->
            phrases.map { pcm16ToFloat16k(synth.synthesize(it, "en").let { s -> s.pcm16 to s.sampleRate }) }
        }

        Runtime.getRuntime().gc()
        val heapBefore = Debug.getNativeHeapAllocatedSize()
        val loadStart = System.currentTimeMillis()

        val pipeline = SpeechPipeline(
            SpeechConfig(
                modelDir = dir,
                useNnapi = false,
                sttModel = model,
                precision = ModelPrecision.INT8,
                pipelineMode = PipelineMode.TRANSCRIBE_ONLY,
                language = "en",
            )
        )
        pipeline.start()
        val loadMs = System.currentTimeMillis() - loadStart
        val heldMb = (Debug.getNativeHeapAllocatedSize() - heapBefore) / 1_000_000
        Log.i(TAG, "$model loaded in ${loadMs}ms, native heap +${heldMb} MB")

        val out = mutableListOf<Utterance>()
        try {
            coroutineScope {
            for (clip in clips) {
                val started = System.currentTimeMillis()
                val event = async {
                    withTimeout(120_000) {
                        pipeline.events.first { it is SpeechEvent.TranscriptionCompleted }
                    }
                }
                delay(100)

                for (offset in clip.indices step 512) {
                    pipeline.pushAudio(clip.sliceArray(offset until minOf(offset + 512, clip.size)))
                    delay(32) // real-time pace: 512 samples @ 16 kHz
                }
                val silence = FloatArray(16000)
                for (offset in silence.indices step 512) {
                    pipeline.pushAudio(
                        silence.sliceArray(offset until minOf(offset + 512, silence.size))
                    )
                    delay(32)
                }

                val tc = event.await() as SpeechEvent.TranscriptionCompleted
                out += Utterance(tc.text, tc.sttMs, System.currentTimeMillis() - started)
            }
            }
        } finally {
            pipeline.stop()
            pipeline.close()
        }
        return out
    }

    private fun pcm16ToFloat16k(input: Pair<ByteArray, Int>): FloatArray {
        val (pcm16, sampleRate) = input
        val shorts = ByteBuffer.wrap(pcm16).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = FloatArray(shorts.remaining()) { shorts.get(it) / 32768f }
        if (sampleRate == 16000) return samples

        // Linear resample; adequate for a fixture both models share.
        val ratio = sampleRate.toDouble() / 16000.0
        val outLength = (samples.size / ratio).toInt()
        return FloatArray(outLength) { i ->
            val src = i * ratio
            val lo = src.toInt().coerceIn(0, samples.size - 1)
            val hi = (lo + 1).coerceAtMost(samples.size - 1)
            val frac = (src - lo).toFloat()
            samples[lo] * (1 - frac) + samples[hi] * frac
        }
    }

    private companion object {
        const val TAG = "SttComparison"
    }
}
