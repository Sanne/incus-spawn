package dev.incusspawn.incus;

public class IncusException extends RuntimeException {
    public IncusException(String message) {
        super(message);
    }

    public IncusException(String message, Throwable cause) {
        super(message, cause);
    }
}
