package dev.incusspawn.incus;

public class IncusException extends RuntimeException {
    private String stepOutput;

    public IncusException(String message) {
        super(message);
    }

    public IncusException(String message, Throwable cause) {
        super(message, cause);
    }

    public String stepOutput() {
        return stepOutput;
    }

    public IncusException withStepOutput(String output) {
        this.stepOutput = output;
        return this;
    }
}
