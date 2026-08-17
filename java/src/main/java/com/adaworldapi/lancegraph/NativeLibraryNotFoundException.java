package com.adaworldapi.lancegraph;

/**
 * Thrown when the native artifact could not be located or loaded at all.
 *
 * <p>Distinct from {@link AbiMismatchException}: this one means "there was nothing to check",
 * which lets a test report <em>"the library has not been built yet"</em> instead of failing with
 * an unrelated {@code NullPointerException}.
 */
public final class NativeLibraryNotFoundException extends LanceGraphException {

    private static final long serialVersionUID = 1L;

    public NativeLibraryNotFoundException(String message) {
        super(message);
    }

    public NativeLibraryNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
