package com.adaworldapi.lancegraph;

/**
 * The identity of a selection, as an opaque token.
 *
 * <p>Deliberately opaque: it is <em>not</em> a pointer and cannot be turned into one. Underneath it
 * is a generation-checked registry token — freeing a slot bumps its generation, so a token that
 * outlives its resource resolves to "closed", never to freed memory. That is why using a stale
 * selection raises {@link ClosedResourceException} instead of corrupting the process.
 *
 * <p>Exposed at all only so a selection can be logged, compared, or used as a map key. Nothing a
 * caller can do with the two accessors below is useful for reaching the underlying storage.
 *
 * <h2>Valhalla A/B candidate</h2>
 *
 * <p>Same rules as {@link LaneId}: final, immutable, identity-free, so the same source compiles as
 * a {@code value record} under JEP 401. Note the consequence that makes this a good candidate —
 * two masks are "the same mask" when their state is equal, never because they are the same object.
 * Nothing here relies on reference equality, so flattening changes no observable behaviour.
 *
 * @param token the opaque registry token
 */
public record MaskId(long token) {

    /** Which registry slot. Diagnostic only. */
    public int slot() {
        return (int) (token & 0xFFFF_FFFFL);
    }

    /** How many times that slot has been recycled. Diagnostic only. */
    public int generation() {
        return (int) (token >>> 32);
    }

    @Override
    public String toString() {
        return "mask#" + slot() + "@" + generation();
    }
}
