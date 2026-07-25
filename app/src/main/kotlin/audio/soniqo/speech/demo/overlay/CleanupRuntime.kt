package audio.soniqo.speech.demo.overlay

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig

/**
 * Text-in, text-out generation for transcript cleanup.
 *
 * Deliberately narrower than [audio.soniqo.speech.llm.FunctionGemma.Runtime]:
 * cleanup wants raw completion with no tool-call templating. Keeping it an
 * interface is what lets the model be swapped — the current bundle is
 * FunctionGemma 270M only because it is the one `ModelManager` can fetch, and
 * it is tuned for tool calls rather than prose.
 */
interface CleanupRuntime : AutoCloseable {
    fun generate(prompt: String): String
}

/** [CleanupRuntime] over the litertlm Engine/Conversation API. */
class LiteRtCleanupRuntime(private val modelPath: String) : CleanupRuntime {

    private var engine: Engine? = null

    /** Blocking, several seconds — call from a background thread. */
    fun initialize() {
        val e = Engine(EngineConfig(modelPath = modelPath, backend = Backend.CPU()))
        e.initialize()
        engine = e
    }

    override fun generate(prompt: String): String {
        val e = checkNotNull(engine) { "LiteRtCleanupRuntime not initialized" }
        // Greedy: cleanup wants the single most likely rendering, and sampling
        // makes a 270M model wander off the transcript.
        val config = ConversationConfig(
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
        )
        // A fresh conversation per call keeps the KV cache from growing across
        // dictations and stops one transcript leaking into the next.
        return e.createConversation(config).use { conversation ->
            conversation.sendMessage(prompt).toString()
        }
    }

    override fun close() {
        engine?.close()
        engine = null
    }
}
