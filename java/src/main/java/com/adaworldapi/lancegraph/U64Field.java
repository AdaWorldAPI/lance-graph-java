package com.adaworldapi.lancegraph;

/**
 * An unsigned 64-bit column — an identity, a join key.
 *
 * <p>Offers no predicate <em>yet</em>, and that absence is deliberate rather than an oversight: the
 * ABI implements no 64-bit comparison kernel, so offering {@code eq(long)} here would compile and
 * then fail at runtime with an unknown opcode. The vocabulary a schema can express is exactly the
 * vocabulary the membrane can execute. When a {@code u64} kernel lands, this class gains the method
 * and nothing else changes.
 */
public final class U64Field extends Field {

    /** <strong>Generator surface.</strong> Called by schema-vocabulary code, not by consumers. */
    public U64Field(String name, LaneId lane, Ordinal ordinal) {
        super(name, lane, ordinal);
    }

    @Override
    public String elementType() {
        return "u64";
    }
}
