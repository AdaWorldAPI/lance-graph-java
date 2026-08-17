package com.adaworldapi.lancegraph;

/**
 * Thrown when a resource is used after it was closed, closed twice, or when a value derived from
 * it (a {@link View}, a {@link Mask}) is used after its owner was closed.
 *
 * <p>Two independent mechanisms produce this, deliberately — belt and braces:
 *
 * <ol>
 *   <li>Java's own bookkeeping fails fast, so a stale handle never even reaches native code.
 *   <li>If it somehow did, the native registry's generation check returns {@code INVALID_HANDLE}
 *       or {@code PARENT_CLOSED} rather than dereferencing freed memory, and that status maps
 *       back to this same exception.
 * </ol>
 *
 * <p>There is no code path in which a stale handle dereferences freed memory.
 */
public final class ClosedResourceException extends LanceGraphException {

    private static final long serialVersionUID = 1L;

    public ClosedResourceException(String message) {
        super(message);
    }
}
