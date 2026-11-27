import Foundation

public let feedbackMinPlayedSeconds = 300
public let feedbackEndToleranceSeconds = 30

/// Shows the one-time feedback prompt only after the TV confirmed playback started and either
/// the TV-reported position advanced at least 5 minutes or playback reached the end of the title.
public func shouldAskForFeedback(
    seen: Bool,
    confirmedStart: Bool,
    playedSeconds: Int,
    reachedEnd: Bool
) -> Bool {
    !seen && confirmedStart && (playedSeconds >= feedbackMinPlayedSeconds || reachedEnd)
}

public func reachedPlaybackEnd(positionMs: Int64, durationSeconds: Int?) -> Bool {
    guard let durationSeconds, durationSeconds > 0, positionMs > 0 else { return false }
    let thresholdMs = Int64(max(0, durationSeconds - feedbackEndToleranceSeconds)) * 1000
    return positionMs >= thresholdMs
}

/// How long the title played, for the feedback prompt. The TV's position is only read while the app is on screen, so a
/// film watched with the phone locked would look seconds long; the time since the TV confirmed the start covers that.
/// Pauses count too, which is fine for "did they really watch".
public func playedSecondsForFeedback(startPositionMs: Int64?, maxPositionMs: Int64, confirmedAt: Date?, now: Date) -> Int {
    let sampled = startPositionMs.map { Int(max(0, maxPositionMs - $0) / 1000) } ?? 0
    let elapsed = confirmedAt.map { Int(max(0, now.timeIntervalSince($0))) } ?? 0
    return max(sampled, elapsed)
}
