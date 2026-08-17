package com.adaworldapi.lancegraph.internal.ffm;

import com.adaworldapi.lancegraph.ClosedResourceException;
import com.adaworldapi.lancegraph.LanceGraphException;
import com.adaworldapi.lancegraph.NativeCallException;

/**
 * The ABI's status codes (docs/abi.md §3), and the mapping from a negative status to a specific
 * Java exception type.
 *
 * <p>Every membrane function returns {@code i32}: {@code 0} is success and all failures are
 * negative. There are no error strings across the boundary and no {@code errno} dependence, so
 * this table <em>is</em> the whole error vocabulary.
 *
 * <p><strong>Internal.</strong> Callers see the mapped exception, never this enum.
 */
public enum Status {

    OK(0, "success"),
    NULL_ARGUMENT(-1, "a required out-pointer was null"),
    INVALID_HANDLE(-2, "handle malformed, closed, or generation-stale"),
    WRONG_RESOURCE_KIND(-3, "a handle of the wrong resource kind was passed"),
    INVALID_LANE(-4, "lane_id out of range for this resource"),
    LANE_KIND_MISMATCH(-5, "the operation's element type does not match the lane's"),
    MASK_LENGTH_MISMATCH(-6, "mask row-count does not match resource row-count"),
    PARENT_CLOSED(-7, "the mask outlived its parent resource"),
    VERSION_MISMATCH(-8, "caller's ABI version is incompatible"),
    LENGTH_OVERFLOW(-9, "requested size overflows the allocation limit"),
    UNKNOWN_OPCODE(-10, "the plan contained an opcode this build does not implement"),
    EMPTY_PLAN(-11, "a plan with zero ops was submitted"),
    ALLOCATION_FAILED(-12, "the allocator refused"),
    READ_ONLY(-13, "write attempted against a read-only lane"),
    /**
     * Not tabulated in docs/abi.md §3 (reported as a doc gap) but required by §9: a panic is caught
     * at the boundary and becomes a negative status rather than unwinding into JVM frames, which
     * would be undefined behaviour. This is that status.
     */
    PANIC(-99, "a panic was caught at the membrane and converted to a status");

    private final int code;
    private final String meaning;

    Status(int code, String meaning) {
        this.code = code;
        this.meaning = meaning;
    }

    public int code() {
        return code;
    }

    public String meaning() {
        return meaning;
    }

    /** Resolve a raw status, or {@code null} if the library returned a code this build knows not. */
    public static Status of(int code) {
        for (Status s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        return null;
    }

    /**
     * Throw the most specific exception for a failing status. Never returns.
     *
     * @param function the ABI symbol that failed, for the message
     * @param code     the raw negative status
     */
    public static LanceGraphException toException(String function, int code) {
        Status s = of(code);
        if (s == null) {
            return new NativeCallException(function, code, "UNKNOWN_STATUS",
                    "this Java build does not know this status code; the library is likely newer");
        }
        return switch (s) {
            // Use-after-close and orphaned-child are the same story to a Java caller: the thing you
            // are holding no longer refers to anything live.
            case INVALID_HANDLE -> new ClosedResourceException(
                    function + " was called with a handle that is closed, stale, or fabricated"
                            + " (ABI status INVALID_HANDLE = -2). The native registry rejected it"
                            + " by generation check; no freed memory was dereferenced.");
            case PARENT_CLOSED -> new ClosedResourceException(
                    function + " was called on a mask whose parent resource is closed"
                            + " (ABI status PARENT_CLOSED = -7). A child may exist after its"
                            + " parent closes, but it can never work.");
            default -> new NativeCallException(function, s.code, s.name(), s.meaning);
        };
    }

    /** Throw if {@code code} is not {@link #OK}. */
    public static void check(String function, int code) {
        if (code != 0) {
            throw toException(function, code);
        }
    }
}
