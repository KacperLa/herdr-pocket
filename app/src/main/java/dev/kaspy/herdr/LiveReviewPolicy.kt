package dev.kaspy.herdr

/** Only fresh caller input may skip review. An agent question is never an automatic action. */
internal object LiveReviewPolicy {
    private val answerLike = Regex(
        """(?i)^(?:(?:y|yes|n|no|ok|okay|sure|proceed|continue|confirm|accept|reject|cancel|approve|deny|allow|always|never)(?:\b.*)?|[0-9][.!?]?)$"""
    )

    fun canSendPrompt(requireReview: Boolean, freshCallerInput: Boolean, draft: String): Boolean =
        !requireReview && freshCallerInput && LiveAgentHerdr.validPrompt(draft) && !answerLike.matches(draft)

    fun canReadBack(requireReview: Boolean, kind: PocketLiveReview.Kind, verifiedStoredReply: Boolean,
        draft: String, profile: LiveVoiceProfile): Boolean =
        !requireReview && kind == PocketLiveReview.Kind.REPLY && verifiedStoredReply && LiveReadback.plan(profile,draft)!=null
}

/** FOSS-Live reports a committed caller turn even in native audio mode without text. */
internal class LiveVoiceCallerInput {
    private var fresh: LiveVoiceProtocol.Event.InputCommit? = null
    private var lastEnd = -1L

    fun record(commit: LiveVoiceProtocol.Event.InputCommit) {
        if (commit.end <= lastEnd) return
        lastEnd = commit.end
        fresh = commit
    }

    fun claimAt(offset: Long?): Boolean {
        val commit = fresh ?: return false
        if (offset == null || offset - commit.end !in 0L..45_000L) return false
        fresh = null
        return true
    }
}
