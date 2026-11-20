package com.adaworldapi.lancegraph;

import com.adaworldapi.lancegraph.DescriptorParityTest.Geometry;
import com.adaworldapi.lancegraph.internal.ffm.Engine;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Alignment as THREE separate facts, for both store layouts, as Panama sees them. They are
 * measured apart and never merged, and none is inferred from the row width:
 *
 * <ol>
 *   <li>LOGICAL GEOMETRY: lane offset from the store base, stride, element size, the exact covered
 *       span {@code (len-1)*stride + elem}, the last element in bounds, the span ending at or
 *       before {@code 512n}. No address is involved.
 *   <li>ACTUAL BASE ALIGNMENT: the raw lane's descriptor address modulo 8, 64 and 128. RECORDED,
 *       NEVER ASSERTED to a value. The native allocation is {@code Arc<[u8]>} and docs/abi.md
 *       promises {@code u8} alignment only, so a 16- or 64-aligned measured base is an allocator
 *       accident, not a contract.
 *   <li>PER-LANE ALIGNMENT: {@code (base + lane_offset) % elem_bytes} and {@code % 64}, from the
 *       ACTUAL address.
 * </ol>
 *
 * <p>Also: tails of every mask this test makes at n = 257 (bits {@code >= n} of the last word are
 * zero), byte order read from bytes (little-endian, on a fixture whose big-endian reading differs),
 * and unaligned reads on a copy at an odd address. Every Java read here is an existing accessor or
 * the {@code *_UNALIGNED} layouts production uses; the byte-wise recomputations are TEST ORACLES
 * only, nothing in {@code src/main} gains a compute path from this file.
 *
 * <p>DISABLE (verified red-then-green): in {@link #checkRowWidthDoesNotDecideAlignment} change
 * the expected base-relative 64-aligned lane count at n = 257 facet-major from 8 to 9 and the
 * check goes red; or "simplify" alignment to {@code rowStride % 64 == 0} by asserting all 96 lanes
 * are 64-aligned and the n = 257 facet-major check goes red.
 */
public final class AlignmentFactsTest {

    private AlignmentFactsTest() {}

    private static final int FACETS = 32;
    private static final int LANES = 1 + 3 * FACETS;
    private static final int ROW_BYTES = 512;

    public static void main(String[] args) {
        System.out.println("AlignmentFactsTest");
        Checks c = new Checks("AlignmentFactsTest");
        run(c);
        System.exit(c.report());
    }

    public static void run(Checks c) {
        for (boolean columnar : new boolean[] {false, true}) {
            String name = columnar ? "facet-major" : "row-major";
            System.out.println("alignment table, " + name
                    + " (96 facet lanes; base values are MEASURED, NOT a contract)");
            System.out.println("  n    | base%8 base%64 base%128 | elem-aligned | 64-aligned (actual"
                    + " addr) | 64-aligned (base-relative)");
            for (long n : new long[] {16, 257, 4096}) {
                c.section(name + " alignment facts at n=" + n);
                try (RowStore s = columnar ? RowStore.openColumnar(n, 0x1A7EL)
                                           : RowStore.open(n, 0x1A7EL)) {
                    threeFacts(c, s, columnar, n, name);
                }
            }
        }
        c.section("n=257: tails, byte order, unaligned reads");
        for (boolean columnar : new boolean[] {false, true}) {
            String name = columnar ? "facet-major" : "row-major";
            long n = 257;
            try (RowStore s = columnar ? RowStore.openColumnar(n, 0xF00DCAFEL)
                                       : RowStore.open(n, 0xF00DCAFEL)) {
                tails(c, s, columnar, n, name);
                byteOrder(c, s, columnar, n, name);
                unalignedCopy(c, s, columnar, n, name);
            }
        }
    }

    private static void threeFacts(Checks c, RowStore s, boolean columnar, long n, String name) {
        long handle = s.handle();
        Engine.LaneWindow raw = Engine.describeLane(handle, 0);
        // ---- FACT 2: the actual base. Recorded, never asserted to a value. ----
        long base = raw.segment().address();
        c.eq(name + " n=" + n + ": raw lane covers exactly 512n bytes", 512 * n, raw.byteLength());
        c.that(name + " n=" + n + ": base is a real address", base != 0);

        int bad1 = 0;
        int elemOk = 0;
        int a64 = 0;
        int rel64 = 0;
        int u32Aligned = 0;
        int u64Aligned = 0;
        int badLaw = 0;
        for (int lane = 1; lane < LANES; lane++) {
            Engine.LaneWindow w = Engine.describeLane(handle, lane);
            Geometry g = DescriptorParityTest.expected(columnar, n, lane);
            // ---- FACT 1: logical geometry, from the documentation, no address involved ----
            long span = (g.len() - 1) * g.stride() + g.elemBytes();
            long lastStart = g.offset() + (g.len() - 1) * g.stride();
            boolean geometryOk = w.byteLength() == span
                    && w.segment().byteSize() == span
                    && lastStart + g.elemBytes() == g.offset() + span
                    && g.offset() + span <= 512 * n
                    && span < 512 * n
                    && g.stride() >= g.elemBytes();
            if (!geometryOk) {
                bad1++;
            }
            // ---- FACT 3: per-lane alignment from the ACTUAL address ----
            long addr = w.segment().address();
            if (addr != base + g.offset()) {
                bad1++;
            }
            boolean aligned = addr % g.elemBytes() == 0;
            elemOk += aligned ? 1 : 0;
            a64 += addr % 64 == 0 ? 1 : 0;
            rel64 += g.offset() % 64 == 0 ? 1 : 0;
            if (g.elemBytes() == 8) {
                u64Aligned += aligned ? 1 : 0;
            } else {
                u32Aligned += aligned ? 1 : 0;
            }
            // Layout law: offsets are multiples of the element size, except row-major lo64 at
            // 4 mod 8. So element alignment is decided by the base, never by n or by 512.
            boolean law = (!columnar && g.elemBytes() == 8) ? g.offset() % 8 == 4
                    : g.offset() % g.elemBytes() == 0;
            if (!law) {
                badLaw++;
            }
        }
        c.eq(name + " n=" + n + ": fact 1 (logical geometry) violations", 0L, bad1);
        c.eq(name + " n=" + n + ": layout offset-law violations", 0L, badLaw);
        // The measured base decides element alignment, per field kind (fact 2 -> fact 3).
        c.eq(name + " n=" + n + ": u32 lanes element-aligned, derived from base%4",
                base % 4 == 0 ? 64L : 0L, u32Aligned);
        long wantU64 = ((!columnar && base % 8 == 4) || (columnar && base % 8 == 0)) ? 32L : 0L;
        c.eq(name + " n=" + n + ": u64 lanes element-aligned, derived from base%8 (base%8="
                + base % 8 + ")", wantU64, u64Aligned);
        System.out.printf("  %-4d | %-7d%-8d%-8d | %3d/96       | %3d/96                   | %3d/96%n",
                n, base % 8, base % 64, base % 128, elemOk, a64, rel64);
        if (base % 16 == 0) {
            System.out.println("    (n=" + n + ": this base happens to be 16-aligned"
                    + (base % 64 == 0 ? " and 64-aligned" : "")
                    + "; Arc<[u8]> promises u8 only, so that is NOT a contract)");
        }
        checkRowWidthDoesNotDecideAlignment(c, columnar, n, name, rel64, a64, base);
    }

    /**
     * The row width (512) is a multiple of 64, which would suggest every lane is 64-aligned. It is
     * not, and the fact is established from addresses and offsets, never from the width. This is
     * the check that fails if alignment is "simplified" to {@code rowStride % 64 == 0}.
     */
    private static void checkRowWidthDoesNotDecideAlignment(Checks c, boolean columnar, long n,
            String name, int rel64, int actual64, long base) {
        boolean widthSuggestsAligned = ROW_BYTES % 64 == 0;
        c.that("the row width suggests 64-alignment (that is the trap)", widthSuggestsAligned);
        int suggested = widthSuggestsAligned ? 96 : 0;
        if (!(columnar && n % 16 == 0)) {
            // (facet-major with n % 16 == 0 really is 96/96 base-relative: asserted below. That
            // is a fact about OFFSETS; the actual-address count next is the one that can differ.)
            c.notEq(name + " n=" + n + ": base-relative 64-aligned lanes differ from what the row"
                    + " width suggests", suggested, rel64);
        }
        if (columnar && n % 16 == 0) {
            // Every offset is a multiple of 64, so the ACTUAL count is decided by the measured
            // base alone: all 96 if base % 64 == 0, none otherwise. (Not asserted to a value.)
            c.eq(name + " n=" + n + ": actual-address 64-aligned lanes follow the measured base",
                    base % 64 == 0 ? 96L : 0L, actual64);
        } else {
            c.notEq(name + " n=" + n + ": actual-address 64-aligned lanes differ from what the row"
                    + " width suggests", suggested, actual64);
        }
        if (columnar && n == 257) {
            // classid and hi32: f % 16 == 0 -> 2 each; lo64: f % 8 == 0 -> 4.
            c.eq("facet-major n=257: base-relative 64-aligned facet lanes", 8L, rel64);
        }
        if (columnar && n % 16 == 0) {
            c.eq("facet-major n=" + n + ": base-relative 64-aligned facet lanes", 96L, rel64);
        }
    }

    // ---- tails: every mask made here, at n = 257 ----

    private static void tails(Checks c, RowStore s, boolean columnar, long n, String name) {
        int words = (int) ((n + 63) / 64);
        int bad = 0;
        int made = 0;
        for (int f : new int[] {0, 1, 17, 31}) {
            for (int cls : new int[] {0, 5}) {
                try (Mask m = s.maskOfFacetClass(FacetId.of(f), cls)) {
                    made++;
                    bad += tailAndOracleViolations(s, m, n, words, f, cls);
                }
            }
        }
        c.eq(name + " n=" + n + ": class masks with a dirty tail or an oracle mismatch", 0L, bad);
        if (!columnar) {
            int badT = 0;
            for (int f : new int[] {0, 9, 31}) {
                long pLo = s.payloadLow64At(0, FacetId.of(f));
                int pHi = s.payloadHi32At(0, FacetId.of(f));
                long cLo = 0xC0L; // the register's first byte (facet byte 4), top two bits
                int cHi = 0xC0000000; // the register's last byte (facet byte 15), top two bits
                try (Mask m = s.maskOfFacetTernaryMatch(FacetId.of(f), pLo, pHi, cLo, cHi)) {
                    made++;
                    Engine.LaneWindow w = Engine.describeMask(m.handle());
                    long pop = 0;
                    for (int r = 0; r < n; r++) {
                        boolean want = ((s.payloadLow64At(r, FacetId.of(f)) ^ pLo) & cLo) == 0
                                && ((s.payloadHi32At(r, FacetId.of(f)) ^ pHi) & cHi) == 0;
                        boolean got = bit(w, r);
                        pop += got ? 1 : 0;
                        if (want != got) {
                            badT++;
                        }
                    }
                    if (!tailClear(w, n, words)) {
                        badT++;
                    }
                    if (pop == 0 || pop == n) {
                        badT++; // vacuous or total
                    }
                }
            }
            c.eq(name + " n=" + n + ": ternary masks with a dirty tail, mismatch or vacuity", 0L,
                    badT);
        }
        c.that(name + " n=" + n + ": masks were made", made >= 8);
    }

    private static int tailAndOracleViolations(RowStore s, Mask m, long n, int words, int f,
            int cls) {
        Engine.LaneWindow w = Engine.describeMask(m.handle());
        int bad = tailClear(w, n, words) ? 0 : 1;
        long pop = 0;
        for (int r = 0; r < n; r++) {
            boolean want = s.classidAt(r, FacetId.of(f)) == cls;
            boolean got = bit(w, r);
            pop += got ? 1 : 0;
            if (want != got) {
                bad++;
            }
        }
        if (pop == 0 || pop == n) {
            bad++;
        }
        return bad;
    }

    private static boolean bit(Engine.LaneWindow w, long row) {
        long word = w.segment().get(ValueLayout.JAVA_LONG_UNALIGNED, 8L * (row / 64));
        return ((word >>> (row % 64)) & 1L) != 0;
    }

    /** Bits at positions {@code >= n} of the last word are zero, and the window is that long. */
    private static boolean tailClear(Engine.LaneWindow w, long n, int words) {
        if (w.segment().byteSize() != 8L * words) {
            return false;
        }
        long last = w.segment().get(ValueLayout.JAVA_LONG_UNALIGNED, 8L * (words - 1));
        int used = (int) (n % 64);
        return used == 0 || (last >>> used) == 0;
    }

    // ---- byte order, from bytes ----

    private static void byteOrder(Checks c, RowStore s, boolean columnar, long n, String name) {
        MemorySegment raw = Engine.describeLane(s.handle(), 0).segment();
        long cells = 0;
        long asym = 0;
        long bad = 0;
        for (int f = 0; f < FACETS; f++) {
            Geometry gc = DescriptorParityTest.expected(columnar, n, 1 + f);
            Geometry gl = DescriptorParityTest.expected(columnar, n, 33 + f);
            Geometry gh = DescriptorParityTest.expected(columnar, n, 65 + f);
            for (long r = 0; r < n; r++) {
                long co = gc.offset() + r * gc.stride();
                long lo = gl.offset() + r * gl.stride();
                long ho = gh.offset() + r * gh.stride();
                int cle = (int) le(raw, co, 4);
                long lle = le(raw, lo, 8);
                int hle = (int) le(raw, ho, 4);
                int cbe = (int) be(raw, co, 4);
                long lbe = be(raw, lo, 8);
                int hbe = (int) be(raw, ho, 4);
                FacetId fid = FacetId.of(f);
                if (cle != s.classidAt(r, fid) || lle != s.payloadLow64At(r, fid)
                        || hle != s.payloadHi32At(r, fid)) {
                    bad++;
                }
                // classid < 16: bytes are [c, 0, 0, 0] in little-endian storage.
                if (raw.get(ValueLayout.JAVA_BYTE, co + 1) != 0
                        || raw.get(ValueLayout.JAVA_BYTE, co + 2) != 0
                        || raw.get(ValueLayout.JAVA_BYTE, co + 3) != 0) {
                    bad++;
                }
                cells++;
                if (cle != 0 && cbe != cle && lbe != lle && hbe != hle) {
                    asym++;
                }
            }
        }
        c.eq(name + " n=" + n + ": cells whose from-bytes LE read differs from the accessor", 0L,
                bad);
        c.that(name + " n=" + n + ": fixture is asymmetric (BE read differs) on most cells: "
                + asym + "/" + cells, asym > cells / 2);
    }

    private static long le(MemorySegment seg, long off, int bytes) {
        long v = 0;
        for (int i = bytes - 1; i >= 0; i--) {
            v = (v << 8) | (seg.get(ValueLayout.JAVA_BYTE, off + i) & 0xFFL);
        }
        return v;
    }

    private static long be(MemorySegment seg, long off, int bytes) {
        long v = 0;
        for (int i = 0; i < bytes; i++) {
            v = (v << 8) | (seg.get(ValueLayout.JAVA_BYTE, off + i) & 0xFFL);
        }
        return v;
    }

    // ---- unaligned reads on a copy at an odd address ----

    private static void unalignedCopy(Checks c, RowStore s, boolean columnar, long n,
            String name) {
        MemorySegment raw = Engine.describeLane(s.handle(), 0).segment();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment big = arena.allocate(raw.byteSize() + 16, 1);
            int shift = 0;
            while ((big.address() + shift) % 8 != 1) {
                shift++;
            }
            MemorySegment copy = big.asSlice(shift, raw.byteSize());
            MemorySegment.copy(raw, 0, copy, 0, raw.byteSize());
            c.eq(name + " n=" + n + ": constructed copy base%8", 1L, copy.address() % 8);
            long mis32 = 0;
            long mis64 = 0;
            long bad = 0;
            for (int lane = 1; lane < LANES; lane++) {
                Geometry g = DescriptorParityTest.expected(columnar, n, lane);
                if ((copy.address() + g.offset()) % g.elemBytes() != 0) {
                    if (g.elemBytes() == 8) {
                        mis64++;
                    } else {
                        mis32++;
                    }
                }
                int field = (lane - 1) / FACETS;
                FacetId f = FacetId.of((lane - 1) % FACETS);
                for (long r = 0; r < n; r++) {
                    long at = g.offset() + r * g.stride();
                    boolean eq = switch (field) {
                        case 0 -> copy.get(ValueLayout.JAVA_INT_UNALIGNED, at)
                                == s.classidAt(r, f);
                        case 1 -> copy.get(ValueLayout.JAVA_LONG_UNALIGNED, at)
                                == s.payloadLow64At(r, f);
                        default -> copy.get(ValueLayout.JAVA_INT_UNALIGNED, at)
                                == s.payloadHi32At(r, f);
                    };
                    if (!eq) {
                        bad++;
                    }
                }
            }
            c.that(name + " n=" + n + ": at least one u32 and one u64 lane is NOT element-aligned",
                    mis32 >= 1 && mis64 >= 1);
            c.eq(name + " n=" + n + ": misaligned u32 lanes (all 64)", 64L, mis32);
            c.eq(name + " n=" + n + ": misaligned u64 lanes (all 32)", 32L, mis64);
            c.eq(name + " n=" + n + ": unaligned reads that differ from the aligned accessors", 0L,
                    bad);
            System.out.println("  " + name + " n=" + n + " odd-based copy: base%8=1, misaligned u32"
                    + " lanes " + mis32 + "/64, u64 lanes " + mis64 + "/32");
        }
    }
}
