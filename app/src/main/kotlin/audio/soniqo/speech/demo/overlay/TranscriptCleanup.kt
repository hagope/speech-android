package audio.soniqo.speech.demo.overlay

import audio.soniqo.speech.llm.FunctionGemmaPrompt

/**
 * Prompt and output guard for LLM cleanup of a dictated transcript.
 *
 * Cleanup is punctuation, capitalization and filler removal — never rewriting.
 * That constraint is what makes the result checkable: the cleaned text should
 * carry the same content words as the raw transcript, so anything that drifts
 * is a model failure rather than a better sentence.
 *
 * The guard matters more than the prompt here. Cleanup runs on a small
 * instruction-tuned model, which can return an empty string, an answer to the
 * dictation, or an invented sentence. Any of those reaching the user's text
 * field would be worse than no cleanup at all, so [accept] falls back to the
 * original transcript whenever the candidate looks wrong. The tool-call check
 * survives from the FunctionGemma prototype and is cheap to keep.
 */
object TranscriptCleanup {

    /** Dropped before comparing, so removing them never counts as drift. */
    private val FILLERS = setOf(
        "um", "uh", "erm", "uhm", "ah", "er", "eh", "hmm", "mhm",
    )

    /** Candidate may not exceed this multiple of the original word count. */
    private const val MAX_LENGTH_RATIO = 1.6f

    /** Fraction of the original's content words the candidate must retain. */
    private const val MIN_CONTENT_OVERLAP = 0.6f

    /** Why a candidate was rejected — surfaced by the setup screen's test. */
    data class Verdict(val text: String, val accepted: Boolean, val reason: String)

    /**
     * Worked examples, as real conversation turns.
     *
     * A model this small follows demonstrations far more reliably than
     * instructions, especially negative ones ("do not explain"). Showing the
     * transformation twice is worth more than prose telling it what not to do.
     */
    private val EXAMPLES = listOf(
        "um so send it uh on friday" to "So send it on Friday.",
        "i think uh we should meet um on monday morning" to
            "I think we should meet on Monday morning.",
    )

    /**
     * Full prompt including the model's turn structure.
     *
     * litertlm 0.14.0 does not apply a chat template of its own, so an
     * untemplated prompt leaves an instruction-tuned model completing text
     * rather than answering — which is exactly the failure this had. The
     * markers must match the model: SmolLM2 is ChatML, so Gemma's
     * `<start_of_turn>` would be as wrong as no template at all.
     */
    fun buildPrompt(transcript: String): String = buildString {
        append("$SYSTEM_START\n${SYSTEM_PROMPT}$TURN_END\n")
        EXAMPLES.forEach { (input, output) ->
            append("$USER_START\n$input$TURN_END\n")
            append("$MODEL_START\n$output$TURN_END\n")
        }
        append("$USER_START\n$transcript$TURN_END\n")
        append("$MODEL_START\n")
    }

    // ChatML, as used by SmolLM2.
    private const val SYSTEM_START = "<|im_start|>system"
    private const val USER_START = "<|im_start|>user"
    private const val MODEL_START = "<|im_start|>assistant"
    private const val TURN_END = "<|im_end|>"

    private const val SYSTEM_PROMPT =
        "You clean up dictated text. Remove filler words such as \"um\" and " +
            "\"uh\", fix punctuation and capitalization, and keep every other " +
            "word exactly as it is. Reply with the corrected text only."

    /**
     * Decide what to insert: the cleaned [candidate] when it looks like a
     * faithful cleanup of [original], otherwise [original] unchanged.
     */
    fun accept(original: String, candidate: String): String =
        evaluate(original, candidate).text

    /** [accept] with the reasoning kept, for diagnostics. */
    fun evaluate(original: String, candidate: String): Verdict {
        val cleaned = stripWrappers(candidate)
        if (cleaned.isBlank()) {
            return Verdict(original, false, "model returned nothing")
        }

        // Tool-call syntax means the model fell back to what it was tuned for.
        if (cleaned.contains(FunctionGemmaPrompt.FUNCTION_CALL_START) ||
            cleaned.contains(FunctionGemmaPrompt.FUNCTION_CALL_END)
        ) {
            return Verdict(original, false, "model emitted tool-call syntax")
        }

        val originalWords = contentWords(original)
        val candidateWords = contentWords(cleaned)
        if (candidateWords.isEmpty()) {
            return Verdict(original, false, "no words left after cleanup")
        }

        // Rambling: cleanup only ever removes words, so growth means invention.
        if (candidateWords.size > originalWords.size * MAX_LENGTH_RATIO + 1) {
            return Verdict(
                original, false,
                "model added text (${originalWords.size} words in, ${candidateWords.size} out)",
            )
        }

        if (originalWords.isNotEmpty()) {
            val retained = retainedFraction(originalWords, candidateWords)
            if (retained < MIN_CONTENT_OVERLAP) {
                return Verdict(
                    original, false,
                    "only ${(retained * 100).toInt()}% of the words survived",
                )
            }
        }

        return Verdict(cleaned, true, "accepted")
    }

    /**
     * Remove the scaffolding small models wrap answers in — code fences,
     * a restated "Corrected:" label, surrounding quotes.
     */
    internal fun stripWrappers(raw: String): String {
        var text = raw

        // litertlm returns raw decoded text, so control tokens can come back
        // with it. Cut at the first turn boundary — anything past it is the
        // model starting a new turn, not part of the answer. Both ChatML and
        // Gemma markers are listed so a model swap cannot silently regress.
        val turnMarkers = listOf(
            "<|im_end|>", "<|im_start|>", "<|endoftext|>",
            "<end_of_turn>", "<start_of_turn>", "<eos>",
        )
        for (token in turnMarkers) {
            val at = text.indexOf(token)
            if (at >= 0) text = text.substring(0, at)
        }
        text = text.trim()

        if (text.startsWith("```")) {
            text = text.removePrefix("```").substringAfter('\n', "").substringBeforeLast("```")
            text = text.trim()
        }

        for (label in listOf("Corrected:", "Corrected transcript:", "Output:", "Transcript:")) {
            if (text.startsWith(label, ignoreCase = true)) {
                text = text.substring(label.length).trim()
            }
        }

        // Only strip quotes that wrap the whole string, not a quoted phrase
        // inside an otherwise normal sentence.
        if (text.length >= 2) {
            val first = text.first()
            val last = text.last()
            val paired = (first == '"' && last == '"') ||
                (first == '\'' && last == '\'') ||
                (first == '“' && last == '”')
            if (paired && text.count { it == first } <= 2) {
                text = text.substring(1, text.length - 1).trim()
            }
        }

        return text
    }

    /** Lowercased alphanumeric words, minus fillers — the comparison unit. */
    internal fun contentWords(text: String): List<String> =
        text.lowercase()
            .map { if (it.isLetterOrDigit() || it == '\'') it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotBlank() && it !in FILLERS }

    /**
     * Fraction of [original]'s words still present in [candidate], counting
     * duplicates, so dropping one of two "the"s costs only that one.
     */
    internal fun retainedFraction(original: List<String>, candidate: List<String>): Float {
        if (original.isEmpty()) return 1f
        val remaining = HashMap<String, Int>()
        for (word in candidate) remaining[word] = (remaining[word] ?: 0) + 1
        var kept = 0
        for (word in original) {
            val count = remaining[word] ?: 0
            if (count > 0) {
                remaining[word] = count - 1
                kept++
            }
        }
        return kept.toFloat() / original.size
    }
}
