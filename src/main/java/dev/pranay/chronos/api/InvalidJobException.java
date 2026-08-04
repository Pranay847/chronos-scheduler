package dev.pranay.chronos.api;

/** A job definition that parsed but doesn't make sense. Maps to 400. */
public class InvalidJobException extends RuntimeException {

    public InvalidJobException(String message) {
        super(message);
    }
}
