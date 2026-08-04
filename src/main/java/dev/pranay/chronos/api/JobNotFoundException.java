package dev.pranay.chronos.api;

/**
 * No job with that id in this tenant. Maps to 404.
 *
 * <p>Deliberately does not distinguish "no such job" from "someone else's job" — telling a
 * caller that an id exists but isn't theirs leaks the existence of other tenants' data.
 */
public class JobNotFoundException extends RuntimeException {

    public JobNotFoundException(String id) {
        super("No job with id " + id);
    }
}
