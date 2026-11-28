package dev.incusspawn.command;

import java.time.Instant;

/** Keeps a guest's answer as its last reading, for tests outside this package. */
public final class KeptReadsAccess {
    private KeptReadsAccess() {}

    public static void put(KeptReads kept, String name, String incarnation, String output, Instant at) {
        kept.put(name, incarnation, new GuestRead.Answer(0, output, false, at));
    }

    /** A reading of {@code output}, made at {@code at}. */
    public static GuestRead parsed(String output, Instant at) {
        return new GuestRead.Answer(0, output, false, at).parsed();
    }
}
