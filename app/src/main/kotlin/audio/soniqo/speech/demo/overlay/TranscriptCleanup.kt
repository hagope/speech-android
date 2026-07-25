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
 * The guard matters more than the prompt here. The only model currently
 * downloadable is FunctionGemma 270M, which is fine-tuned to emit tool calls,
 * so asking it for prose is off-distribution: it can return call syntax, an
 * empty string, or an invented sentence. Any of those reaching the user's text
 * field would be worse than no cleanup at all, so [accept] falls back to the
 * original transcript whenever the candidate looks wrong.
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

    fun buildPrompt(transcript: String): String =
        """
        Fix the punctuation and capitalization of the transcript below and
        remove filler words such as "um" and "uh". Keep every other word
        exactly as it is. Do not answer, summarize, explain or add anything.
        Reply with the corrected transcript only.

        Transcript: $transcript
        Corrected:
        """.trimIndent()

    /**
     * Decide what to insert: the cleaned [candidate] when it looks like a
     * faithful cleanup of [original], otherwise [original] unchanged.
     */
    fun accept(original: String, candidate: String): String {
        val cleaned = stripWrappers(candidate)
        if (cleaned.isBlank()) return original

        // Tool-call syntax means the model fell back to what it was tuned for.
        if (cleaned.contains(FunctionGemmaPrompt.FUNCTION_CALL_START) ||
            cleaned.contains(FunctionGemmaPrompt.FUNCTION_CALL_END)
        ) {
            return original
        }

        val originalWords = contentWords(original)
        val candidateWords = contentWords(cleaned)
        if (candidateWords.isEmpty()) return original

        // Rambling: cleanup only ever removes words, so growth means invention.
        if (candidateWords.size > originalWords.size * MAX_LENGTH_RATIO + 1) {
            return original
        }

        if (originalWords.isNotEmpty() &&
            retainedFraction(originalWords, candidateWords) < MIN_CONTENT_OVERLAP
        ) {
            return original
        }

        return cleaned
    }

    /**
     * Remove the scaffolding small models wrap answers in — code fences,
     * a restated "Corrected:" label, surrounding quotes.
     */
    internal fun stripWrappers(raw: String): String {
        var text = raw.trim()

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
