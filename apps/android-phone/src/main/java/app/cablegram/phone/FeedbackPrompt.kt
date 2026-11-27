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

/**
 * How long the title played, for the feedback prompt. The TV's position is only read while the phone shows the app, so
 * a film watched with the phone locked would look seconds long; the time since the TV confirmed the start covers that
 * (and Cast sessions, which report no position here). Pauses count too, which is fine for "did they really watch".
 */
fun playedSecondsForFeedback(startPositionMs: Long?, maxPositionMs: Long, confirmedAtMs: Long?, nowMs: Long): Int {
    val sampled = startPositionMs?.let { ((maxPositionMs - it).coerceAtLeast(0L) / 1000L).toInt() } ?: 0
    val elapsed = confirmedAtMs?.let { ((nowMs - it).coerceAtLeast(0L) / 1000L).toInt() } ?: 0
    return maxOf(sampled, elapsed)
}
