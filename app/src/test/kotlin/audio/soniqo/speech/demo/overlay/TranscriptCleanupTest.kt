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
        // model completes rather than continuing the user's text.
        assertEquals(3, prompt.split("<|im_start|>user").size - 1)
        assertEquals(3, prompt.split("<|im_start|>assistant").size - 1)
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
