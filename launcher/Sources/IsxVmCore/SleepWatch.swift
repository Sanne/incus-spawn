// Tells that the host slept, from two clocks: one that goes on while the host sleeps and one
// that stops. The launcher asks every few seconds, and when the first has gained on the second
// the host was asleep in between.
//
// No notification from the system is needed for this, so no AppKit and no IOKit in a program
// that has no other use for them. What it gives up: the launcher learns of a wake up to one
// interval late, and it wakes up every interval to ask.
public struct SleepWatch: Sendable {
    /// Less than this between two readings is the clocks' own difference, not a sleep.
    public let threshold: Duration
    private let startedContinuous = ContinuousClock.now
    private let startedSuspending = SuspendingClock.now
    /// How far the clock that goes on was ahead at the last call of `slept`.
    public private(set) var lead: Duration = .zero

    public init(threshold: Duration = .seconds(1)) {
        self.threshold = threshold
    }

    /// How far the clock that goes on is ahead now: all the time this host has slept since the
    /// watch was made. It only grows, and only in a sleep, so it stamps a moment as before or
    /// after one. Of a copy of the watch too, which has the same start.
    public func ahead() -> Duration {
        startedContinuous.duration(to: .now) - startedSuspending.duration(to: .now)
    }

    /// The stamp that parts what happened before the sleep `slept` last reported from what
    /// happened after it. Half the threshold below the lead: what was stamped after the wake
    /// has the lead but for the clocks' own difference, and what was stamped before it is
    /// lower by the whole sleep, or by the last one when the host slept twice between two calls.
    public var wakeLimit: Duration {
        lead - threshold / 2
    }

    /// How long the host slept since the last call, or nil when it did not.
    public mutating func slept() -> Duration? {
        slept(continuous: startedContinuous.duration(to: .now), suspending: startedSuspending.duration(to: .now))
    }

    /// The same from two readings: the time since a common start by a clock that counts the
    /// time asleep, and by one that does not.
    public mutating func slept(continuous: Duration, suspending: Duration) -> Duration? {
        let now = continuous - suspending
        let gained = now - lead
        lead = now
        return gained >= threshold ? gained : nil
    }
}
