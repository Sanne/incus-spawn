package dev.incusspawn;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WarningsTest {

    private final List<String> received = new ArrayList<>();
    private final Warnings.Channel channel = new Warnings.Channel(received::add);

    @Test
    void aRedirectReceivesWarningsUntilClosed() {
        var outer = new ArrayList<String>();
        try (var ignored = Warnings.redirect(new Warnings.Channel(outer::add))) {
            try (var inner = Warnings.redirect(channel)) {
                Warnings.warn("to the inner channel");
            }
            Warnings.warn("back to the outer channel");
        }

        assertEquals(List.of("to the inner channel"), received);
        assertEquals(List.of("back to the outer channel"), outer);
    }

    @Test
    void aChannelReportsAMessageOnce() {
        try (var ignored = Warnings.redirect(channel)) {
            Warnings.warn("repeated");
            Warnings.warn("repeated");
            Warnings.warn("another");
        }

        assertEquals(List.of("repeated", "another"), received);
    }

    @Test
    void aChannelRemembersWhatItReportedAcrossRedirects() {
        try (var ignored = Warnings.redirect(channel)) {
            Warnings.warn("seen in the first session");
        }
        try (var ignored = Warnings.redirect(channel)) {
            Warnings.warn("seen in the first session");
        }

        assertEquals(List.of("seen in the first session"), received);
    }

    /** A build run on the TUI's released terminal must still print what the TUI logged. */
    @Test
    void anotherChannelReportsWhatThisOneAlreadyDid() {
        var other = new ArrayList<String>();
        try (var ignored = Warnings.redirect(channel)) {
            Warnings.warn("broken tool file");
        }
        try (var ignored = Warnings.redirect(new Warnings.Channel(other::add))) {
            Warnings.warn("broken tool file");
        }

        assertEquals(List.of("broken tool file"), received);
        assertEquals(List.of("broken tool file"), other);
    }

    @Test
    void forgettingLetsAMessageBeReportedAgain() {
        try (var ignored = Warnings.redirect(channel)) {
            Warnings.warn("still true");
            channel.forgetReported();
            Warnings.warn("still true");
        }

        assertEquals(List.of("still true", "still true"), received);
    }

    @Test
    void blankMessagesAreIgnored() {
        try (var ignored = Warnings.redirect(channel)) {
            Warnings.warn(null);
            Warnings.warn("  ");
        }

        assertEquals(List.of(), received);
    }
}
