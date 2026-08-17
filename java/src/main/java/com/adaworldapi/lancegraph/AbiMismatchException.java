package com.adaworldapi.lancegraph;

/**
 * Thrown at library load when the compiled artifact's self-description disagrees with what this
 * Java build expects.
 *
 * <p>This is the header replacement doing its job. A C header <em>claims</em> a layout and can
 * drift from the artifact silently; here the artifact emits its own sizes, alignments, version and
 * endianness at runtime and Java compares them against independently derived
 * {@code MemoryLayout} numbers. The first disagreement is a hard failure naming the exact field,
 * never a silent misread.
 */
public final class AbiMismatchException extends LanceGraphException {

    private static final long serialVersionUID = 1L;

    public AbiMismatchException(String message) {
        super(message);
    }
}
