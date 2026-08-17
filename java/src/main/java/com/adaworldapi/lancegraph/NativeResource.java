package com.adaworldapi.lancegraph;

/**
 * The common face of row-shaped native resources a {@link Mask} can select over.
 *
 * <p>Both {@link NativePattern} and {@link RowStore} hold rows natively and let a {@link Mask}
 * parent onto them — abi.md §11 states this directly: "masks may parent onto a pattern OR a row
 * store — both are read-only, row-shaped resources." This interface names that shared shape so
 * {@link Mask} does not need to know, or care, which kind of resource it selects over.
 *
 * <p>Deliberately small: just enough for {@link Mask} to report a count and to check liveness
 * before every operation. Anything specific to how a resource's rows are laid out or generated
 * belongs on the concrete type, not here.
 */
public interface NativeResource {

    /** How many rows this resource holds. */
    long rowCount();

    /** False once the resource has been closed. */
    boolean isOpen();
}
