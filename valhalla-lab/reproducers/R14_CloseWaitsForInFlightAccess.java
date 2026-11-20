// Reproducer R14 — does closing a shared Arena wait for an access that is already in flight on
// another thread, and can the release (the arena cleanup) ever run while that access is still
// reading? Evidence for open question Q9 of .claude/plans/native-lease-v1.md, which R13 could not
// settle because R13 had no way to tell whether a close actually overlapped a copy.
//
// What makes overlap observable here (and was missing in R13): a SIGNATURE that only an
// overlapping round can produce. On the pinned build, MemorySegment.copy reaches
// ScopedMemoryAccess.copyMemory (a @Scoped method): a liveness check, then ONE call to
// Unsafe.copyMemory0 (no chunking in Java, Unsafe.java:1000-1010), then return. HotSpot's
// closeScope0 (scopedMemoryAccess.cpp, jdk-28+16) handshakes every thread; a thread found inside a
// @Scoped frame holding the session gets an asynchronous ScopedAccessError, which the @Scoped
// method turns into IllegalStateException; closeScope0 then WAITS until every such thread has been
// stopped at a safepoint poll and marked to throw (it then makes no further memory access) before
// it returns, and only then does MemorySessionImpl.close() run the cleanup.
// Hence, for one round:
//
//   copy threw ISE AND the destination holds every source byte
//        -> the copy passed the liveness check (so it started before the close marked the session
//           closed) and was still inside its access when the handshake reached it (otherwise it
//           would have returned normally). That round OVERLAPPED.
//   copy threw ISE AND the destination is untouched
//        -> the copy started after the close. No overlap.
//   copy returned normally
//        -> the copy ended before the close's handshake. No overlap.
//
// That inference uses the JDK source named above; it is not a black-box fact. Note also that
// closeScope0 waits for its counter, which drops when the target runs the handshake, just before
// the exception is installed: the cleanup can run while the target is still stopped inside its
// @Scoped frame. The guarantee is that the target makes no further memory access.
//
// The ordering oracle is black-box: the cleanup (the stand-in for lgj_lease_release) overwrites
// the last 1 MiB of the source with a poison pattern, BACKWARD from the last word, through an
// alias that is never freed, and only then sets a flag. The copy reads forward. A release that
// starts while the copy still has words of that region left to read puts poison into the
// destination, unless the copy reads all of them before the poison writer reaches them. A release
// after the copy's final read is not detected, and needs no detecting: no access remains. So a
// clean destination is evidence against a release during the copy for the timings the oracle can
// see, not proof for every timing.
//
//   G1  overlap rounds exist, and in none of them does poison reach the destination;
//   G2  close duration in overlap rounds grows with the copy size (close waits for the access);
//   G3  sensitivity: the same oracle, with the "release" run from another thread WITHOUT closing
//       the arena (the lease-release-unchecked red arm), while the copy is confirmed in progress
//       (first destination word written, last not yet), does see poison -- so G1's clean result
//       is not an oracle that cannot fail. Rounds without that confirmation are inconclusive;
//   G4  a view passed to a downcall (libc memcpy) acquires the session: a close during that
//       downcall throws IllegalStateException and does not run the cleanup; after the downcall,
//       close succeeds and runs it once. A round counts only when the downcall is confirmed in
//       progress across the close (first destination word written before it, last word still
//       unwritten after it); other rounds are inconclusive.
//
// Memory safety: all backing memory comes from Arena.global() and is never freed. Only the VIEW
// is bound to the shared arena. No arm can read freed memory; the poison is ordinary data.
//
//   javac --release 28 --enable-preview -d out R14_CloseWaitsForInFlightAccess.java
//   java  --enable-preview --enable-native-access=ALL-UNNAMED -cp out R14_CloseWaitsForInFlightAccess
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class R14_CloseWaitsForInFlightAccess {

    static int failures = 0;
    static final long POISON = 0xDEADDEADDEADDEADL;
    static final long TAIL = 1L << 20; // the last 1 MiB of the source is poisoned by the release

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + what);
        if (!ok) failures++;
    }

    static long value(long i) { return i * 0x9E3779B97F4A7C15L + 1; }

    static MemorySegment pinned(long bytes) {
        MemorySegment m = Arena.global().allocate(bytes, 64);
        for (long i = 0; i < bytes / 8; i++) m.setAtIndex(ValueLayout.JAVA_LONG, i, value(i));
        return m;
    }

    static void restoreTail(MemorySegment src) {
        long n = src.byteSize() / 8;
        for (long i = n - TAIL / 8; i < n; i++) src.setAtIndex(ValueLayout.JAVA_LONG, i, value(i));
    }

    /** Writes poison BACKWARD from the last word, so the words a forward copy reads last are
     *  poisoned first: a release that starts before the copy's final read is caught unless the
     *  copy reads its remaining words before the poison writer reaches them. */
    static void poisonTail(MemorySegment src) {
        long n = src.byteSize() / 8;
        for (long i = n - 1; i >= n - TAIL / 8; i--) src.setAtIndex(ValueLayout.JAVA_LONG, i, POISON);
    }

    static boolean firstWordWritten(MemorySegment dst) {
        return dst.getAtIndex(ValueLayout.JAVA_LONG, 0) != 0;
    }

    static boolean lastWordWritten(MemorySegment dst, long bytes) {
        return dst.getAtIndex(ValueLayout.JAVA_LONG, bytes / 8 - 1) != 0;
    }

    /** Spin until the copy has visibly started writing the destination, or give up. */
    static boolean awaitProgress(MemorySegment dst, long timeoutNs) {
        long end = System.nanoTime() + timeoutNs;
        while (!firstWordWritten(dst)) {
            if (System.nanoTime() > end) return false;
            Thread.onSpinWait();
        }
        return true;
    }

    /** 0 = untouched (all zero), 1 = equals source, 2 = poison present, 3 = other/partial. */
    static int classify(MemorySegment dst, long bytes) {
        long n = bytes / 8;
        boolean allZero = true, allSrc = true, poison = false;
        for (long i = 0; i < n; i++) {
            long v = dst.getAtIndex(ValueLayout.JAVA_LONG, i);
            if (v != 0) allZero = false;
            if (v != value(i)) allSrc = false;
            if (v == POISON) poison = true;
        }
        return poison ? 2 : allSrc ? 1 : allZero ? 0 : 3;
    }

    static void spinNanos(long ns) {
        long end = System.nanoTime() + ns;
        while (System.nanoTime() < end) Thread.onSpinWait();
    }

    static double median(long[] xs, int n) {
        long[] c = Arrays.copyOf(xs, n);
        Arrays.sort(c);
        return n == 0 ? Double.NaN : c[n / 2] / 1e6;
    }

    public static void main(String[] args) throws Throwable {
        System.out.println("java.version=" + System.getProperty("java.version")
                + " vm=" + System.getProperty("java.vm.version"));
        double[] closeMs = g1g2();
        g3();
        g4();
        System.out.println(failures == 0 ? "RESULT: all observations as expected"
                                         : "RESULT: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }

    // G1, G2 ------------------------------------------------------------------------------------
    static double[] g1g2() throws Exception {
        long[] sizes = {32L << 20, 64L << 20, 128L << 20, 256L << 20};
        final int rounds = 20;
        final long delayNs = 1_000_000; // close is issued 1 ms after the reader says "copying now"
        System.out.println("G1/G2 close vs in-flight copy, " + rounds + " rounds per size, close "
                + (delayNs / 1e6) + " ms after the reader's start signal");
        double[] medClose = new double[sizes.length];
        long totalOverlap = 0, totalPoison = 0;
        for (int si = 0; si < sizes.length; si++) {
            long bytes = sizes[si];
            MemorySegment src = pinned(bytes);
            MemorySegment dst = Arena.global().allocate(bytes, 64);
            // standalone copy time, no close
            long[] copyNs = new long[5];
            for (int k = 0; k < copyNs.length; k++) {
                long t0 = System.nanoTime();
                MemorySegment.copy(src, 0, dst, 0, bytes);
                copyNs[k] = System.nanoTime() - t0;
            }
            long[] closeNs = new long[rounds];
            long[] overlapCloseNs = new long[rounds];
            int overlap = 0, after = 0, normal = 0, poison = 0, other = 0, closeThrew = 0,
                cleanupMissing = 0;
            for (int r = 0; r < rounds; r++) {
                dst.fill((byte) 0);
                restoreTail(src);
                AtomicBoolean released = new AtomicBoolean();
                AtomicInteger cleanups = new AtomicInteger();
                Arena arena = Arena.ofShared();
                MemorySegment view = MemorySegment.ofAddress(src.address())
                        .reinterpret(bytes, arena, s -> {
                            poisonTail(src);           // the "release": tail becomes poison
                            released.set(true);
                            cleanups.incrementAndGet();
                        });
                CountDownLatch go = new CountDownLatch(1);
                AtomicInteger outcome = new AtomicInteger(-1); // 0 normal, 1 ISE
                Thread reader = new Thread(() -> {
                    go.countDown();
                    try {
                        MemorySegment.copy(view, 0, dst, 0, bytes);
                        outcome.set(0);
                    } catch (IllegalStateException e) {
                        outcome.set(1);
                    }
                });
                reader.start();
                go.await();
                spinNanos(delayNs);
                long t0 = System.nanoTime();
                try { arena.close(); } catch (IllegalStateException e) { closeThrew++; }
                closeNs[r] = System.nanoTime() - t0;
                reader.join();
                if (cleanups.get() != 1) cleanupMissing++;
                int d = classify(dst, bytes);
                if (d == 2) poison++;
                if (outcome.get() == 1 && d == 1) { overlapCloseNs[overlap++] = closeNs[r]; }
                else if (outcome.get() == 1 && d == 0) after++;
                else if (outcome.get() == 0 && d == 1) normal++;
                else if (d != 2) other++;
            }
            medClose[si] = median(overlapCloseNs, overlap);
            totalOverlap += overlap;
            totalPoison += poison;
            System.out.printf("  size %4d MiB: standalone copy median %.1f ms | rounds: overlap %d,"
                    + " copy-after-close %d, copy-ended-first %d, poison %d, other %d |"
                    + " close threw %d, cleanup count != 1 in %d | close duration median in"
                    + " overlap rounds %.1f ms%n",
                    bytes >> 20, median(copyNs, copyNs.length), overlap, after, normal, poison,
                    other, closeThrew, cleanupMissing, medClose[si]);
            check(poison == 0, "size " + (bytes >> 20) + " MiB: no destination carries poison"
                    + " (the release never ran while a copy was still reading)");
            check(cleanupMissing == 0 && closeThrew == 0, "size " + (bytes >> 20)
                    + " MiB: every close returned normally and ran the cleanup exactly once");
        }
        check(totalOverlap > 0, "overlap rounds observed: " + totalOverlap
                + " (copy threw ISE after writing every byte)");
        boolean grows = true;
        for (int i = 1; i < medClose.length; i++)
            if (!(medClose[i] > medClose[i - 1])) grows = false;
        check(grows, "close duration in overlap rounds grows with copy size: "
                + Arrays.toString(medClose) + " ms");
        return medClose;
    }

    // G3 ----------------------------------------------------------------------------------------
    // Red arm: the "release" is run by another thread while the copy is reading, with no arena
    // close (lease-release-unchecked). The oracle must be able to see this.
    static void g3() throws Exception {
        long bytes = 256L << 20;
        final int rounds = 10;
        System.out.println("G3 sensitivity: release without close, during the copy, " + rounds
                + " rounds");
        MemorySegment src = pinned(bytes);
        MemorySegment dst = Arena.global().allocate(bytes, 64);
        int poison = 0, inconclusive = 0, confirmed = 0;
        for (int r = 0; r < rounds; r++) {
            dst.fill((byte) 0);
            restoreTail(src);
            Arena arena = Arena.ofShared();
            MemorySegment view = MemorySegment.ofAddress(src.address()).reinterpret(bytes, arena, s -> {});
            CountDownLatch go = new CountDownLatch(1);
            Thread reader = new Thread(() -> {
                go.countDown();
                MemorySegment.copy(view, 0, dst, 0, bytes);
            });
            reader.start();
            go.await();
            // Confirm the copy is in progress: it has written the first word and not yet the last.
            boolean inFlight = awaitProgress(dst, 1_000_000_000L) && !lastWordWritten(dst, bytes);
            if (inFlight) poisonTail(src);
            reader.join();
            arena.close();
            if (!inFlight) { inconclusive++; continue; }
            confirmed++;
            if (classify(dst, bytes) == 2) poison++;
        }
        System.out.println("  rounds with the copy confirmed in progress when the release ran: "
                + confirmed + " (inconclusive " + inconclusive + "); poison reached the destination in "
                + poison + "/" + confirmed);
        check(confirmed > 0 && poison == confirmed,
                "the oracle detects a release that runs while the copy is in progress");
    }

    // G4 ----------------------------------------------------------------------------------------
    static void g4() throws Throwable {
        long bytes = 256L << 20;
        System.out.println("G4 view passed to a downcall (memcpy), close during the downcall");
        Linker linker = Linker.nativeLinker();
        MethodHandle memcpy = linker.downcallHandle(
                linker.defaultLookup().find("memcpy").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        MemorySegment src = pinned(bytes);
        MemorySegment dst = Arena.global().allocate(bytes, 64);
        final int rounds = 5;
        int closeThrewDuring = 0, cleanupDuring = 0, laterCloseOk = 0, cleanupOnce = 0,
            confirmed = 0, inconclusive = 0, closedDuringConfirmed = 0, threwAny = 0;
        for (int r = 0; r < rounds; r++) {
            dst.fill((byte) 0);
            AtomicInteger cleanups = new AtomicInteger();
            Arena arena = Arena.ofShared();
            MemorySegment view = MemorySegment.ofAddress(src.address())
                    .reinterpret(bytes, arena, s -> cleanups.incrementAndGet());
            CountDownLatch go = new CountDownLatch(1);
            AtomicLong done = new AtomicLong();
            Thread caller = new Thread(() -> {
                go.countDown();
                try { MemorySegment ignored = (MemorySegment) memcpy.invokeExact(dst, view, bytes); } catch (Throwable t) { throw new RuntimeException(t); }
                done.set(1);
            });
            caller.start();
            go.await();
            // memcpy writes dst forward. Overlap is confirmed when the first word was written
            // before the close attempt and the last word was still unwritten after it returned.
            boolean started = awaitProgress(dst, 1_000_000_000L);
            boolean threw = false;
            try { arena.close(); } catch (IllegalStateException e) { threw = true; }
            int cleanupsAtClose = cleanups.get();
            boolean overlap = started && !lastWordWritten(dst, bytes);
            caller.join();
            if (threw) threwAny++;
            if (overlap) {
                confirmed++;
                if (threw) closeThrewDuring++;
                else closedDuringConfirmed++;
                if (cleanupsAtClose != 0) cleanupDuring++;
            } else {
                inconclusive++;
            }
            if (threw) {
                try { arena.close(); laterCloseOk++; } catch (IllegalStateException e) { }
            } else if (arena.scope().isAlive()) {
                arena.close();
            }
            if (cleanups.get() == 1) cleanupOnce++;
        }
        System.out.println("  rounds with the downcall confirmed in progress across the close: "
                + confirmed + " (inconclusive " + inconclusive + "); of those, close threw: "
                + closeThrewDuring + ", close succeeded: " + closedDuringConfirmed
                + "; cleanup ran during: " + cleanupDuring
                + "; close after the downcall succeeded: " + laterCloseOk
                + "; cleanup ran exactly once overall: " + cleanupOnce);
        check(confirmed > 0 && closeThrewDuring == confirmed,
                "a close during a confirmed in-progress downcall that holds the view throws");
        check(cleanupDuring == 0, "the cleanup does not run while the downcall holds the view");
        check(laterCloseOk == threwAny && cleanupOnce == rounds,
                "after the downcall the close succeeds and the cleanup runs once");
    }
}
