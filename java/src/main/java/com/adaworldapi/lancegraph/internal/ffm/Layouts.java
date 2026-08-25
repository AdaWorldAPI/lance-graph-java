package com.adaworldapi.lancegraph.internal.ffm;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.SequenceLayout;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;

/**
 * Java's <em>independently compiled-in</em> mirror of every {@code #[repr(C)]} struct in
 * {@code docs/abi.md} §5.
 *
 * <p>This class is the header replacement's Java half. There is no {@code .h} file, no
 * {@code cbindgen}, and no {@code jextract} anywhere in this project — a header is a text file that
 * <em>claims</em> a layout and can drift silently. Instead the layouts below are written by hand
 * from the normative ABI document, their sizes are <em>derived</em> via
 * {@link MemoryLayout#byteSize()}, and {@link Abi} cross-checks those derived numbers against the
 * sizes the compiled artifact reports about itself. Two independently-derived numbers are compared;
 * a constant is never compared against itself.
 *
 * <p>Field offsets are the System V AMD64 aggregate rule (the same rule {@code #[repr(C)]} names),
 * so every layout here is written with natural packing and asserted to match the byte sizes stated
 * in the ABI document. No explicit padding member is required by any of these four structs — that
 * is checked in {@link #SELF_CHECK}, not assumed.
 *
 * <p><strong>Internal.</strong> Nothing in this class may appear in a public API signature.
 */
public final class Layouts {

    private Layouts() {}

    // ── ABI constants (docs/abi.md §2) ───────────────────────────────────────────────────────

    /** {@code "LGJ_ABI\0"} read as a little-endian {@code u64}; doubles as the endianness probe. */
    public static final long LGJ_MAGIC = 0x4C_47_4A_5F_41_42_49_00L;

    /** Major version this Java build was compiled against. Must match the library exactly. */
    public static final int LGJ_ABI_MAJOR = 0;

    /** Minor version this Java build was compiled against. The library must be {@code >=} this. */
    public static final int LGJ_ABI_MINOR = 1;

    /** {@code endianness} value meaning little-endian. */
    public static final int LGJ_ENDIAN_LITTLE = 0;

    // ── Element kinds (docs/abi.md §5) — start at 1 so a zeroed struct is detectably invalid ──

    public static final int ELEM_U8 = 1;
    public static final int ELEM_I8 = 2;
    public static final int ELEM_U16 = 3;
    public static final int ELEM_I16 = 4;
    public static final int ELEM_U32 = 5;
    public static final int ELEM_I32 = 6;
    public static final int ELEM_U64 = 7;
    public static final int ELEM_I64 = 8;
    public static final int ELEM_F32 = 9;
    public static final int ELEM_F64 = 10;
    /** A {@code u64} of 64 packed row bits, LSB = lowest row index. */
    public static final int ELEM_MASK_WORD = 11;

    // ── Lane descriptor flags (docs/abi.md §5) ───────────────────────────────────────────────

    public static final int FLAG_READABLE = 1 << 0;
    public static final int FLAG_WRITABLE = 1 << 1;
    public static final int FLAG_CONTIGUOUS = 1 << 2;

    // ── Resource kinds (docs/abi.md §5) ──────────────────────────────────────────────────────

    public static final int RESOURCE_PATTERN = 1;
    public static final int RESOURCE_MASK = 2;

    // ── Mask initial fill (docs/abi.md §7) ───────────────────────────────────────────────────

    public static final int MASK_INIT_EMPTY = 0;
    public static final int MASK_INIT_ALL = 1;

    // ── Opcodes and combiners (docs/abi.md §5 `LgjOpCode`) ───────────────────────────────────
    //
    // NOTE (doc gap, reported): abi.md §5 names the `op` field's type as `LgjOpCode` but never
    // tabulates its numeric values, unlike LgjElemKind / status codes / simd backends which are all
    // tabulated. The values below follow the document's own stated convention for enumerations that
    // must be zero-invalid (element kinds "start at 1, so a zeroed struct is detectably invalid"),
    // and were confirmed against the compiled artifact. Combiners are fixed by §5's own prose:
    // "0 = AND (narrow), 1 = OR (widen)".

    /** {@code lane[i] == (u32) operand}. Requires a {@code U32} lane. */
    public static final int OP_EQ_U32 = 1;

    /** {@code lane[i] > (i32) operand}, signed. Requires an {@code I32} lane. */
    public static final int OP_GT_I32 = 2;

    /** Intersect into the accumulator — narrowing. */
    public static final int COMBINE_AND = 0;

    /** Union into the accumulator — widening. Deliberately unreachable from {@code View.where}. */
    public static final int COMBINE_OR = 1;

    // ── SIMD backend ids (docs/abi.md §5) ────────────────────────────────────────────────────

    public static final int SIMD_SCALAR = 0;
    public static final int SIMD_AVX2 = 1;
    public static final int SIMD_AVX512 = 2;
    public static final int SIMD_NEON = 3;
    public static final int SIMD_WASM = 4;

    // ── LgjAbiManifest ───────────────────────────────────────────────────────────────────────

    /** Length of the manifest's {@code simd_backend_name} byte array. */
    public static final int SIMD_NAME_BYTES = 32;

    /** Length of the manifest's {@code build_profile} byte array. */
    public static final int BUILD_PROFILE_BYTES = 16;

    /**
     * Slots in the manifest's {@code carvings} table (ABI minor 8).
     *
     * <p>A fixed-width array, not a pointer, because the whole table is 16 bytes and a pointer
     * would add a lifetime question to a struct that deliberately has none. {@code carving_count}
     * says how many slots are populated; the rest are zero.
     */
    public static final int CARVING_SLOTS = 8;

    public static final StructLayout MANIFEST = MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withName("magic"),
            ValueLayout.JAVA_INT.withName("abi_major"),
            ValueLayout.JAVA_INT.withName("abi_minor"),
            ValueLayout.JAVA_INT.withName("size_of_manifest"),
            ValueLayout.JAVA_INT.withName("size_of_lane_desc"),
            ValueLayout.JAVA_INT.withName("size_of_op_desc"),
            ValueLayout.JAVA_INT.withName("size_of_resource_info"),
            ValueLayout.JAVA_INT.withName("align_of_lane_desc"),
            ValueLayout.JAVA_INT.withName("align_of_op_desc"),
            ValueLayout.JAVA_INT.withName("align_of_resource_info"),
            ValueLayout.JAVA_INT.withName("pointer_bytes"),
            ValueLayout.JAVA_INT.withName("endianness"),
            ValueLayout.JAVA_INT.withName("simd_backend"),
            MemoryLayout.sequenceLayout(SIMD_NAME_BYTES, ValueLayout.JAVA_BYTE)
                    .withName("simd_backend_name"),
            MemoryLayout.sequenceLayout(BUILD_PROFILE_BYTES, ValueLayout.JAVA_BYTE)
                    .withName("build_profile"),
            // ── added at ABI minor 8 ──
            ValueLayout.JAVA_INT.withName("carving_count"),
            MemoryLayout.sequenceLayout(CARVING_SLOTS, ValueLayout.JAVA_SHORT)
                    .withName("carvings"),
            // repr(C) rounds the struct up to its 8-byte alignment: 108 + 16 = 124 -> 128.
            MemoryLayout.paddingLayout(4))
            .withName("LgjAbiManifest");

    // The manifest is read by OFFSET rather than through a struct VarHandle, and that is a
    // requirement rather than a style choice. A VarHandle derived from a struct layout bounds-checks
    // the *whole enclosing layout* against the segment, so it cannot be used to read a deliberate
    // prefix — and reading a prefix first is exactly how the size disagreement is detected safely
    // (see Abi#readAndVerifyManifest). The offsets are still derived from the layout, so this stays
    // an independent derivation and never a hand-counted constant.
    public static final long OFF_MAGIC = off("magic");
    public static final long OFF_ABI_MAJOR = off("abi_major");
    public static final long OFF_ABI_MINOR = off("abi_minor");
    public static final long OFF_SIZE_OF_MANIFEST = off("size_of_manifest");
    public static final long OFF_SIZE_OF_LANE_DESC = off("size_of_lane_desc");
    public static final long OFF_SIZE_OF_OP_DESC = off("size_of_op_desc");
    public static final long OFF_SIZE_OF_RESOURCE_INFO = off("size_of_resource_info");
    public static final long OFF_ALIGN_OF_LANE_DESC = off("align_of_lane_desc");
    public static final long OFF_ALIGN_OF_OP_DESC = off("align_of_op_desc");
    public static final long OFF_ALIGN_OF_RESOURCE_INFO = off("align_of_resource_info");
    public static final long OFF_POINTER_BYTES = off("pointer_bytes");
    public static final long OFF_ENDIANNESS = off("endianness");
    public static final long OFF_SIMD_BACKEND = off("simd_backend");
    public static final long OFF_SIMD_BACKEND_NAME = off("simd_backend_name");
    public static final long OFF_BUILD_PROFILE = off("build_profile");
    public static final long OFF_CARVING_COUNT = off("carving_count");
    public static final long OFF_CARVINGS = off("carvings");

    /**
     * Bytes of the manifest a MINOR-1 library is guaranteed to carry — everything through
     * {@code build_profile}.
     *
     * <p>This is the prefix the load gate may require, and requiring more would break docs/abi.md
     * §2's additive promise in the direction that matters here: a library OLDER than this Java
     * build still loads, and each later minor gates independently at its own call site
     * ({@code Abi.requireMinor}). Gating the load on the FULL layout size would have turned every
     * future manifest field into a hard incompatibility with every older artifact — including the
     * ones {@code OldAbiCompatTest} runs against.
     */
    public static final long MANIFEST_BASE_BYTES = OFF_BUILD_PROFILE + BUILD_PROFILE_BYTES;

    private static long off(String name) {
        return MANIFEST.byteOffset(PathElement.groupElement(name));
    }

    // ── LgjLaneDesc (56 bytes, align 8) ──────────────────────────────────────────────────────

    public static final StructLayout LANE_DESC = MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withName("addr"),
            ValueLayout.JAVA_LONG.withName("len_elems"),
            ValueLayout.JAVA_LONG.withName("byte_len"),
            ValueLayout.JAVA_LONG.withName("owner"),
            ValueLayout.JAVA_LONG.withName("epoch"),
            ValueLayout.JAVA_INT.withName("elem_kind"),
            ValueLayout.JAVA_INT.withName("elem_bytes"),
            ValueLayout.JAVA_INT.withName("stride_bytes"),
            ValueLayout.JAVA_INT.withName("flags"))
            .withName("LgjLaneDesc");

    public static final VarHandle LANE_ADDR = field(LANE_DESC, "addr");
    public static final VarHandle LANE_LEN_ELEMS = field(LANE_DESC, "len_elems");
    public static final VarHandle LANE_BYTE_LEN = field(LANE_DESC, "byte_len");
    public static final VarHandle LANE_OWNER = field(LANE_DESC, "owner");
    public static final VarHandle LANE_EPOCH = field(LANE_DESC, "epoch");
    public static final VarHandle LANE_ELEM_KIND = field(LANE_DESC, "elem_kind");
    public static final VarHandle LANE_ELEM_BYTES = field(LANE_DESC, "elem_bytes");
    public static final VarHandle LANE_STRIDE_BYTES = field(LANE_DESC, "stride_bytes");
    public static final VarHandle LANE_FLAGS = field(LANE_DESC, "flags");

    // ── LgjResourceInfo (32 bytes) ───────────────────────────────────────────────────────────

    public static final StructLayout RESOURCE_INFO = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("kind"),
            ValueLayout.JAVA_INT.withName("lane_count"),
            ValueLayout.JAVA_LONG.withName("n_rows"),
            ValueLayout.JAVA_LONG.withName("epoch"),
            ValueLayout.JAVA_LONG.withName("parent"))
            .withName("LgjResourceInfo");

    public static final VarHandle INFO_KIND = field(RESOURCE_INFO, "kind");
    public static final VarHandle INFO_LANE_COUNT = field(RESOURCE_INFO, "lane_count");
    public static final VarHandle INFO_N_ROWS = field(RESOURCE_INFO, "n_rows");
    public static final VarHandle INFO_EPOCH = field(RESOURCE_INFO, "epoch");
    public static final VarHandle INFO_PARENT = field(RESOURCE_INFO, "parent");

    // ── LgjOpDesc (24 bytes, align 8) ────────────────────────────────────────────────────────

    public static final StructLayout OP_DESC = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("op"),
            ValueLayout.JAVA_INT.withName("lane_id"),
            ValueLayout.JAVA_LONG.withName("operand"),
            ValueLayout.JAVA_INT.withName("combine"),
            ValueLayout.JAVA_INT.withName("_reserved"))
            .withName("LgjOpDesc");

    public static final VarHandle OP_OP = field(OP_DESC, "op");
    public static final VarHandle OP_LANE_ID = field(OP_DESC, "lane_id");
    public static final VarHandle OP_OPERAND = field(OP_DESC, "operand");
    public static final VarHandle OP_COMBINE = field(OP_DESC, "combine");
    public static final VarHandle OP_RESERVED = field(OP_DESC, "_reserved");

    // ── LgjRow — the SoA row store (512 bytes, docs/abi.md §11, ABI minor ≥ 2) ──────────────
    //
    // 32 facet lanes of 16 bytes each: a 4-byte little-endian classid followed by a 12-byte opaque
    // payload (.claude/knowledge/soa-row-store-layout.md — the lance-graph V3 content-blind facet
    // shape). A facet is naturally 4-byte aligned within the row (offset f*16, always a multiple of
    // 4), so no repr(C)-style padding member is introduced between classid and payload, or between
    // consecutive facets — the struct-in-sequence composition below is exactly 512 bytes with no
    // gaps, which SELF_CHECK proves rather than assumes.
    //
    // JAVA_INT_UNALIGNED, not plain JAVA_INT, deliberately breaking with every other layout in this
    // file: those structs (LgjLaneDesc / LgjResourceInfo / LgjOpDesc / LgjAbiManifest) come from
    // Rust #[repr(C)] types whose own alignment is 8 and is cross-checked field-by-field against the
    // manifest in Abi — so a segment holding one is always properly aligned. The row store's buffer
    // is different by explicit ABI statement: "the base is u8-aligned (Arc<[u8]>; stable Rust
    // promises no more)" (docs/abi.md §11 "Alignment", stated honestly). A plain aligned JAVA_INT
    // VarHandle throws IllegalArgumentException the moment the backing segment's actual address
    // isn't 4-byte aligned, which this ABI does not guarantee here — so every classid read in this
    // layout uses the unaligned value layout, exactly as §11 prescribes ("Java reads via
    // JAVA_INT_UNALIGNED-class layouts"). This is the one facet field with numeric interpretation;
    // the 12-byte payload is opaque bytes and has no alignment requirement either way.
    public static final StructLayout ROW_FACET = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT_UNALIGNED.withName("classid"),
            MemoryLayout.sequenceLayout(12, ValueLayout.JAVA_BYTE).withName("payload"))
            .withName("LgjRowFacet");

    /** One 512-byte row: 32 facets of 16 bytes each. {@link #SELF_CHECK} proves the byte size. */
    public static final SequenceLayout ROW_LAYOUT = MemoryLayout.sequenceLayout(32, ROW_FACET)
            .withName("LgjRow");

    /**
     * Element layout of the caller-allocated {@code lgj_row_facet_match} out buffer (docs/abi.md
     * §11): one {@code u32} bitset per row, into a Java-arena segment the caller owns — so, unlike
     * {@link #ROW_FACET}, ordinary alignment applies and no unaligned variant is needed.
     */
    public static final ValueLayout.OfInt FACET_MATCH_ELEM = ValueLayout.JAVA_INT;

    /**
     * Compile-time-ish self check: the byte sizes the ABI document states in prose, checked against
     * the sizes these layouts actually derive. If a layout is edited wrongly this fails at class
     * initialisation, before any native call, and names the struct that disagreed.
     *
     * <p>This does not replace {@link Abi}'s manifest cross-check — it catches a Java-side typo;
     * the manifest catches a Java-vs-Rust divergence.
     */
    public static final boolean SELF_CHECK = selfCheck();

    private static boolean selfCheck() {
        expect("LgjLaneDesc size", 56, LANE_DESC.byteSize());
        expect("LgjLaneDesc align", 8, LANE_DESC.byteAlignment());
        expect("LgjResourceInfo size", 32, RESOURCE_INFO.byteSize());
        expect("LgjResourceInfo align", 8, RESOURCE_INFO.byteAlignment());
        expect("LgjOpDesc size", 24, OP_DESC.byteSize());
        expect("LgjOpDesc align", 8, OP_DESC.byteAlignment());
        // docs/abi.md §11: "64K rows x 512 bytes per row, 32 facet lanes of 16 bytes each" — this is
        // the substrate truth the whole stack converges on, so a hand-edit that silently changes
        // facet count or facet width must fail loudly here, at class init, before any row-store call.
        expect("LgjRowFacet size", 16, ROW_FACET.byteSize());
        expect("LgjRow (row store, docs/abi.md §11) size", 512, ROW_LAYOUT.byteSize());
        // The manifest's size is not stated in prose; it is stated by the artifact itself and
        // cross-checked in Abi. What is checked here is that the two trailing byte arrays land
        // where the repr(C) rule puts them, which is the only place a hand-written layout could
        // silently disagree.
        expect("LgjAbiManifest simd_backend_name offset", 56, OFF_SIMD_BACKEND_NAME);
        expect("LgjAbiManifest build_profile offset", 88, OFF_BUILD_PROFILE);
        expect("LgjAbiManifest base prefix bytes", 104, MANIFEST_BASE_BYTES);
        expect("LgjAbiManifest carving_count offset", 104, OFF_CARVING_COUNT);
        expect("LgjAbiManifest carvings offset", 108, OFF_CARVINGS);
        expect("LgjAbiManifest size", 128, MANIFEST.byteSize());
        expect("LgjAbiManifest align", 8, MANIFEST.byteAlignment());
        return true;
    }

    private static void expect(String what, long expected, long actual) {
        if (expected != actual) {
            throw new ExceptionInInitializerError(
                    "Layouts self-check failed: " + what + " expected " + expected
                            + " but this build's MemoryLayout derives " + actual);
        }
    }

    private static VarHandle field(StructLayout layout, String name) {
        // Since JDK 22 a struct field VarHandle takes (MemorySegment, long base) coordinates, so
        // every access site passes an explicit 0L base for a single struct.
        return layout.varHandle(PathElement.groupElement(name));
    }
}
