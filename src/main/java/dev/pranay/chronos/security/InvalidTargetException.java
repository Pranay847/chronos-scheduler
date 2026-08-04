package dev.pranay.chronos.security;

/**
 * A webhook target that is syntactically fine but must not be requested.
 *
 * <p>Separate from a general validation error because it is also thrown at <em>delivery</em> time,
 * where there is no request to return a 400 to — it aborts the delivery instead.
 */
public class InvalidTargetException extends RuntimeException {

    public InvalidTargetException(String message) {
        super(message);
    }
}
