package com.adaworldapi.lancegraph;

/**
 * Base type for every failure this library reports.
 *
 * <p>Unchecked on purpose: the failures below are programming errors (using a closed resource,
 * a version-incompatible native library) rather than recoverable conditions a caller is expected
 * to branch on. There is no error string across the membrane — the native side returns a negative
 * {@code i32} status and this layer turns it into a specific, named Java type.
 */
public class LanceGraphException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public LanceGraphException(String message) {
        super(message);
    }

    public LanceGraphException(String message, Throwable cause) {
        super(message, cause);
    }
}
