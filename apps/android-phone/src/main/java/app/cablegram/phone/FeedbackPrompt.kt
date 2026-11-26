package app.cablegram.phone

const val FEEDBACK_MIN_PLAYED_SECONDS = 300
const val FEEDBACK_END_TOLERANCE_SECONDS = 30

/**
 * Shows the one-time feedback prompt only after the TV confirmed playback started and either
 * the TV-reported position advanced at least 5 minutes or playback reached the end of the title.
 */
fun shouldAskForFeedback(
    seen: Boolean,
    confirmedStart: Boolean,
    playedSeconds: Int,
    reachedEnd: Boolean,
): Boolean = !seen && confirmedStart && (playedSeconds >= FEEDBACK_MIN_PLAYED_SECONDS || reachedEnd)

fun reachedPlaybackEnd(positionMs: Long, durationSeconds: Int?): Boolean {
    if (durationSeconds == null || durationSeconds <= 0 || positionMs <= 0L) return false
    val thresholdMs = (durationSeconds - FEEDBACK_END_TOLERANCE_SECONDS).coerceAtLeast(0) * 1000L
    return positionMs >= thresholdMs
}
