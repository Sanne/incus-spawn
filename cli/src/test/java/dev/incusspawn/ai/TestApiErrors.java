package dev.incusspawn.ai;

/** A provider's error answer for tests outside this package, whose constructor is package-private. */
public final class TestApiErrors {

    private TestApiErrors() {}

    public static AiHelpClient.ApiError of(int status, String message) {
        return new AiHelpClient.ApiError(status, message, false);
    }
}
