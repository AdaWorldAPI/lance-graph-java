package com.adaworldapi.lancegraph;

/**
 * Thrown when a native call returned a negative status that is not more specifically modelled.
 *
 * <p>The numeric status is preserved so a test can assert on the exact ABI failure mode rather
 * than on message text.
 */
public final class NativeCallException extends LanceGraphException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String statusName;

    public NativeCallException(String function, int status, String statusName, String meaning) {
        super(function + " failed: " + statusName + " (" + status + ") — " + meaning);
        this.status = status;
        this.statusName = statusName;
    }

    /** The raw negative {@code i32} the membrane returned. */
    public int status() {
        return status;
    }

    /** The ABI's name for that status, e.g. {@code INVALID_LANE}. */
    public String statusName() {
        return statusName;
    }
}
