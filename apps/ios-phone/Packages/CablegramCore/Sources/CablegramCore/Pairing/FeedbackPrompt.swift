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
