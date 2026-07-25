package audio.soniqo.speech.demo.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptCleanupTest {

    // --- accepted cleanups -------------------------------------------------

    @Test
    fun acceptsPunctuationAndCapitalization() {
        val original = "send it on friday and let me know"
        val candidate = "Send it on Friday, and let me know."
        assertEquals(candidate, TranscriptCleanup.accept(original, candidate))
    }

    @Test
    fun acceptsFillerRemoval() {
        val original = "um so send it uh on friday"
        val candidate = "So send it on Friday."
        assertEquals(candidate, TranscriptCleanup.accept(original, candidate))
    }

    // --- rejected candidates ----------------------------------------------

    @Test
    fun rejectsToolCallSyntax() {
        val original = "set a timer for five minutes"
        val candidate = "<start_function_call>call:set_timer{minutes:5}<end_function_call>"
        assertEquals(original, TranscriptCleanup.accept(original, candidate))
    }

    @Test
    fun rejectsBlankOutput() {
        val original = "send it on friday"
        assertEquals(original, TranscriptCleanup.accept(original, ""))
        assertEquals(original, TranscriptCleanup.accept(original, "   \n  "))
    }

    @Test
    fun rejectsAnsweringInsteadOfCleaning() {
        // The failure mode that matters: a chat-tuned model replies to the
        // dictation rather than tidying it.
        val original = "what time does the train leave"
        val candidate = "The train leaves at 6:30 PM from platform 4."
        assertEquals(original, TranscriptCleanup.accept(original, candidate))
    }

    @Test
    fun rejectsInventedContent() {
        val original = "send it friday"
        val candidate = "Send it Friday. I have also added a reminder to your " +
            "calendar and notified the team about the schedule change."
        assertEquals(original, TranscriptCleanup.accept(original, candidate))
    }

    @Test
    fun rejectsALetterWrittenAroundTheTranscript() {
        // The failure this guard exists for: a long dictation reads like a
        // request, so the model composes rather than tidies. It keeps most of
        // the original words, so retention alone would let it through.
        val original = "hey so i wanted to check in about the report you sent last " +
            "week i think the numbers in section three look off and we should " +
            "probably go over them before the meeting"
        val candidate = """
            Dear Colleague,

            I hope this message finds you well. I wanted to check in regarding
            the report you sent last week. Upon careful review, I believe the
            numbers presented in section three may contain inaccuracies that
            warrant further attention. I would like to propose that we go over
            them together before the meeting so that we can address any
            discrepancies in advance.

            Please let me know a convenient time.

            Kind regards,
        """.trimIndent()
        assertEquals(original, TranscriptCleanup.accept(original, candidate))
    }

    @Test
    fun rejectsModestPaddingOfALongTranscript() {
        // 1.6x used to be allowed, which on a long transcript is a lot of room.
        val original = List(40) { "word$it" }.joinToString(" ")
        val candidate = original + " " + List(12) { "extra$it" }.joinToString(" ")
        assertEquals(original, TranscriptCleanup.accept(original, candidate))
    }

    @Test
    fun inventedFractionCountsOnlyWordsNotInTheOriginal() {
        val original = listOf("send", "it", "friday")
        assertEquals(0f, TranscriptCleanup.inventedFraction(original, listOf("send", "friday")), 1e-4f)
        assertEquals(
            0.5f,
            TranscriptCleanup.inventedFraction(original, listOf("send", "regards")),
            1e-4f,
        )
    }

    @Test
    fun rejectsDroppingMostOfTheTranscript() {
        val original = "send the quarterly report to marcus before the meeting on friday"
        val candidate = "Send it."
        assertEquals(original, TranscriptCleanup.accept(original, candidate))
    }

    // --- wrapper stripping -------------------------------------------------

    @Test
    fun stripsCodeFence() {
        val original = "send it on friday"
        val candidate = "```\nSend it on Friday.\n```"
        assertEquals("Send it on Friday.", TranscriptCleanup.accept(original, candidate))
    }

    @Test
    fun stripsRestatedLabel() {
        val original = "send it on friday"
        assertEquals(
            "Send it on Friday.",
            TranscriptCleanup.accept(original, "Corrected: Send it on Friday."),
        )
    }

    @Test
    fun stripsWrappingQuotes() {
        val original = "send it on friday"
        assertEquals(
            "Send it on Friday.",
            TranscriptCleanup.accept(original, "\"Send it on Friday.\""),
        )
    }

    @Test
    fun keepsQuotesInsideASentence() {
        val original = "he said send it friday and hung up"
        val candidate = "He said \"send it Friday\" and hung up."
        assertEquals(candidate, TranscriptCleanup.accept(original, candidate))
    }

    // --- helpers -----------------------------------------------------------

    @Test
    fun contentWordsDropFillersAndPunctuation() {
        assertEquals(
            listOf("so", "send", "it", "friday"),
            TranscriptCleanup.contentWords("Um, so send it — uh — Friday!"),
        )
    }

    @Test
    fun retainedFractionCountsDuplicates() {
        val original = listOf("the", "the", "report")
        assertEquals(2f / 3f, TranscriptCleanup.retainedFraction(original, listOf("the", "report")), 1e-4f)
        assertEquals(1f, TranscriptCleanup.retainedFraction(original, listOf("the", "the", "report")), 1e-4f)
    }

    @Test
    fun promptContainsTheTranscript() {
        val prompt = TranscriptCleanup.buildPrompt("send it friday")
        assert(prompt.contains("send it friday"))
    }

    @Test
    fun promptShowsWorkedExamplesAndEndsOnAModelTurn() {
        val prompt = TranscriptCleanup.buildPrompt("send it friday")
        // Few-shot pairs plus the real one, and an open model turn so the
        // model completes rather than continuing the user's text. Counted
        // relative to each other so adding an example does not fail this.
        val userTurns = prompt.split("<|im_start|>user").size - 1
        val modelTurns = prompt.split("<|im_start|>assistant").size - 1
        assertEquals(userTurns, modelTurns)
        assert(userTurns >= 3) { "expected at least two examples plus the real turn" }
        assert(prompt.endsWith("<|im_start|>assistant\n"))
        assert(prompt.contains("So send it on Friday."))
        // ChatML, not Gemma — the wrong markers are as bad as none.
        assert(!prompt.contains("<start_of_turn>"))
    }

    @Test
    fun stripsTrailingControlTokens() {
        val original = "send it on friday"
        assertEquals(
            "Send it on Friday.",
            TranscriptCleanup.accept(original, "Send it on Friday.<|im_end|>"),
        )
    }

    @Test
    fun cutsOffAHallucinatedNextTurn() {
        val original = "send it on friday"
        val candidate = "Send it on Friday.<end_of_turn>\n<start_of_turn>user\nAnd then?"
        assertEquals("Send it on Friday.", TranscriptCleanup.accept(original, candidate))
    }
}
