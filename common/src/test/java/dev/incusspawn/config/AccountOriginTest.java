package dev.incusspawn.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AccountOriginTest {

    @Test
    void roundTrips() {
        for (var origin : new AccountOrigin[] {
                AccountOrigin.template("tpl-acme"), AccountOrigin.EXPLICIT, AccountOrigin.copiedFrom("dev-1")}) {
            assertEquals(origin, AccountOrigin.decode(origin.encode()));
        }
    }

    /** Absent, hand-edited or from a newer isx: never guessed into something it is not. */
    @Test
    void anythingElseIsUnknown() {
        for (var value : new String[] {null, "", "template:", "copied:", "manual", "EXPLICIT"}) {
            assertEquals(AccountOrigin.UNKNOWN, AccountOrigin.decode(value), String.valueOf(value));
        }
    }

    @Test
    void copyingKeepsATemplatesChoiceAndNamesTheInstanceForAnythingElse() {
        assertEquals(AccountOrigin.template("tpl-acme"), AccountOrigin.template("tpl-acme").copiedOnto("dev-1"));
        assertEquals(AccountOrigin.copiedFrom("dev-1"), AccountOrigin.EXPLICIT.copiedOnto("dev-1"));
        assertEquals(AccountOrigin.copiedFrom("dev-0"), AccountOrigin.copiedFrom("dev-0").copiedOnto("dev-1"),
                "the instance where it was chosen, not every hop since");
        assertEquals(AccountOrigin.copiedFrom("dev-1"), AccountOrigin.UNKNOWN.copiedOnto("dev-1"));
    }
}
