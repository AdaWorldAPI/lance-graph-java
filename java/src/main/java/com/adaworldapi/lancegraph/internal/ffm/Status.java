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
     * {@code lgj_hop} (docs/abi.md §13, ABI minor &ge; 4) called with a {@code decode_mode} this
     * build does not yet implement. Mode {@code 0} (the §12 fixture convention) is the only mode
     * implemented today; modes {@code 1..=3} are RESERVED, mirroring {@code EdgeCodecFlavor as u32
     * + 1}, until real class data lands. This check runs before {@code store}/{@code src_mask}/
     * {@code dst_mask} are even resolved, so {@code dst_mask} is provably untouched on this status.
     */
    UNSUPPORTED_DECODE_MODE(-14, "lgj_hop was called with a decode_mode this build does not yet"
            + " implement"),
    /**
     * {@code lgj_reduce_facet_sum} was given a {@code carving} outside {@code 0..=2}. The three
     * legal readings of the same 12-byte register are {@code 0} rails, {@code 1} triplets,
     * {@code 2} quads (docs/abi.md §14). Checked before the store or mask are resolved, so
     * {@code out_sum} is provably untouched on this status.
     *
     * <p>Deliberately distinct from {@link #UNSUPPORTED_DECODE_MODE}: that names the edge-decode
     * axis, and an unknown register reading must never alias a known one.
     */
    UNSUPPORTED_CARVING(-15, "lgj_reduce_facet_sum was called with a carving outside 0..=2"
            + " (0=rails 6x2, 1=triplets 4x3, 2=quads 3x4)"),
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
