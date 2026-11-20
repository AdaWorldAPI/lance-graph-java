// Reproducer R13 — what a shared Arena actually does when it is closed while another thread
// reads a segment bound to it, on the pinned JDK. Evidence for the native lease contract
// (Bardioc design section 6.5; lance-graph-java J1), which depends on four facts that were
// NOT established when the contract was written:
//
//   F1  after close, every access through a segment bound to the arena throws;
//   F2  the cleanup passed to reinterpret(size, arena, cleanup) runs exactly once, at close,
//       and never before (a second close does not run it again);
//   F3  when close races a reader on another thread, no read that STARTED after the cleanup
//       ran can succeed (the lease is released only once the view is dead);
//   F4  what close does when another thread is inside a long bulk access (does close wait,
//       fail, or complete while the bulk access is still reading?).
//
// The backing memory stands in for lgj's native, lease-pinned memory: it is allocated from
// Arena.global(), so it is NEVER freed here. Only the VIEW is bound to the shared arena. That is
// exactly the shape the contract prescribes (native memory pinned by a lease; the Java view's
// arena close runs the release), and it means no arm of this file can read freed memory.
//
// Every number below is printed by this file. Assertions state what was observed; a broken
// expectation prints FAIL and exits non-zero rather than being smoothed over.
//
//   javac --release 28 --enable-preview -d out R13_SharedArenaCloseVersusRead.java
//   java  --enable-preview -cp out R13_SharedArenaCloseVersusRead
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class R13_SharedArenaCloseVersusRead {

    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + what);
        if (!ok) failures++;
    }

    /** Native memory that is never freed, standing in for a lease-pinned lgj allocation. */
    static MemorySegment pinned(long bytes) {
        MemorySegment m = Arena.global().allocate(bytes, 64);
        for (long i = 0; i < bytes / 8; i++) m.setAtIndex(ValueLayout.JAVA_LONG, i, i);
        return m;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("java.version=" + System.getProperty("java.version")
                + " vm=" + System.getProperty("java.vm.version"));

        f1f2SingleThread();
        f3ConcurrentScalarReads();
        f4ConcurrentBulkRead();

        System.out.println(failures == 0 ? "RESULT: all observations as expected"
                                         : "RESULT: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }

    // F1, F2 ------------------------------------------------------------------------------------
    static void f1f2SingleThread() {
        System.out.println("F1/F2 single thread: close, then access; cleanup count");
        MemorySegment mem = pinned(4096);
        AtomicInteger cleanups = new AtomicInteger();
        Arena arena = Arena.ofShared();
        MemorySegment view = MemorySegment.ofAddress(mem.address())
                .reinterpret(4096, arena, s -> cleanups.incrementAndGet());
        check(view.get(ValueLayout.JAVA_LONG, 8) == 1L, "read before close returns the value");
        check(cleanups.get() == 0, "cleanup has not run before close (count=" + cleanups.get() + ")");
        arena.close();
        check(cleanups.get() == 1, "cleanup ran exactly once at close (count=" + cleanups.get() + ")");
        boolean threw;
        try { view.get(ValueLayout.JAVA_LONG, 8); threw = false; }
        catch (IllegalStateException e) { threw = true; }
        check(threw, "read after close throws IllegalStateException");
        boolean secondThrew;
        try { arena.close(); secondThrew = false; }
        catch (IllegalStateException e) { secondThrew = true; }
        check(secondThrew, "second close throws IllegalStateException");
        check(cleanups.get() == 1, "second close does not run the cleanup again (count="
                + cleanups.get() + ")");
        check(mem.get(ValueLayout.JAVA_LONG, 8) == 1L,
                "the pinned memory itself is untouched (a view close frees nothing)");
    }

    // F3 ----------------------------------------------------------------------------------------
    // A reader thread loops scalar reads. The main thread closes the arena. The cleanup sets a
    // flag. A read that BEGAN after the flag was observed set and still returned a value is a
    // violation: it would mean the release ran while the view could still be read.
    static void f3ConcurrentScalarReads() throws Exception {
        final int rounds = 200;
        System.out.println("F3 concurrent scalar reads vs close, " + rounds + " rounds");
        MemorySegment mem = pinned(1 << 16);
        long totalReads = 0, violations = 0, readerSawIse = 0, closeThrew = 0, cleanupCount = 0;
        for (int round = 0; round < rounds; round++) {
            AtomicBoolean released = new AtomicBoolean();
            AtomicInteger cleanups = new AtomicInteger();
            Arena arena = Arena.ofShared();
            MemorySegment view = MemorySegment.ofAddress(mem.address())
                    .reinterpret(1 << 16, arena, s -> { cleanups.incrementAndGet(); released.set(true); });
            AtomicLong reads = new AtomicLong(), bad = new AtomicLong();
            AtomicBoolean sawIse = new AtomicBoolean();
            CountDownLatch started = new CountDownLatch(1);
            Thread reader = new Thread(() -> {
                started.countDown();
                long i = 0, sink = 0;
                while (true) {
                    boolean relBefore = released.get();
                    long v;
                    try { v = view.getAtIndex(ValueLayout.JAVA_LONG, (int) (i++ & 8191)); }
                    catch (IllegalStateException e) { sawIse.set(true); break; }
                    sink += v;
                    reads.incrementAndGet();
                    if (relBefore) { bad.incrementAndGet(); break; }
                }
                if (sink == 42) System.out.print("");
            });
            reader.start();
            started.await();
            Thread.sleep(0, 200_000);
            try { arena.close(); } catch (IllegalStateException e) { closeThrew++; }
            reader.join(10_000);
            if (reader.isAlive()) { check(false, "round " + round + ": reader did not terminate"); return; }
            totalReads += reads.get();
            violations += bad.get();
            if (sawIse.get()) readerSawIse++;
            cleanupCount += cleanups.get();
        }
        System.out.println("  reads=" + totalReads + " readerSawISE=" + readerSawIse + "/" + rounds
                + " closeThrew=" + closeThrew + " cleanups=" + cleanupCount);
        check(violations == 0, "no read that began after the release returned a value (violations="
                + violations + ")");
        check(readerSawIse == rounds, "every reader ended by an IllegalStateException");
        check(cleanupCount + closeThrew == rounds,
                "each round ran the cleanup once, unless its close threw (cleanups=" + cleanupCount
                + ", closeThrew=" + closeThrew + ")");
    }

    // F4 ----------------------------------------------------------------------------------------
    // A reader thread performs ONE long bulk copy out of the view. The main thread closes the
    // arena while that copy is in progress. Observed: does close throw, block until the copy
    // returns, or complete while the copy continues? And does the copy succeed or throw?
    static void f4ConcurrentBulkRead() throws Exception {
        final long bytes = 256L << 20;     // 256 MiB: long enough to be in flight when close runs
        System.out.println("F4 close while another thread is inside one bulk copy of "
                + (bytes >> 20) + " MiB");
        MemorySegment mem = pinned(bytes);
        long[] heap = new long[(int) (bytes / 8)];
        final int rounds = 10;
        int copyOk = 0, copyIse = 0, closeThrew = 0, closeBeforeCopyEnd = 0, cleanupBeforeCopyEnd = 0;
        for (int round = 0; round < rounds; round++) {
            AtomicLong cleanupAt = new AtomicLong(-1);
            Arena arena = Arena.ofShared();
            MemorySegment view = MemorySegment.ofAddress(mem.address())
                    .reinterpret(bytes, arena, s -> cleanupAt.set(System.nanoTime()));
            AtomicLong copyEnd = new AtomicLong();
            AtomicBoolean ok = new AtomicBoolean(), ise = new AtomicBoolean();
            CountDownLatch started = new CountDownLatch(1);
            Thread reader = new Thread(() -> {
                started.countDown();
                try {
                    MemorySegment.copy(view, ValueLayout.JAVA_LONG, 0, heap, 0, heap.length);
                    ok.set(true);
                } catch (IllegalStateException e) {
                    ise.set(true);
                }
                copyEnd.set(System.nanoTime());
            });
            reader.start();
            started.await();
            Thread.sleep(5);
            long closeStart = System.nanoTime(), closeEnd;
            boolean threw = false;
            try { arena.close(); } catch (IllegalStateException e) { threw = true; }
            closeEnd = System.nanoTime();
            reader.join(30_000);
            if (ok.get()) copyOk++;
            if (ise.get()) copyIse++;
            if (threw) closeThrew++;
            if (!threw && closeEnd < copyEnd.get()) closeBeforeCopyEnd++;
            if (cleanupAt.get() >= 0 && cleanupAt.get() < copyEnd.get()) cleanupBeforeCopyEnd++;
            System.out.println("  round " + round + ": copy " + (ok.get() ? "completed" : ise.get() ? "threw ISE" : "?")
                    + ", close " + (threw ? "threw ISE" : "returned")
                    + " after " + (closeEnd - closeStart) / 1000 + " us"
                    + ", close returned " + (closeEnd < copyEnd.get() ? "BEFORE" : "after") + " the copy ended"
                    + ", cleanup " + (cleanupAt.get() < 0 ? "did not run"
                        : cleanupAt.get() < copyEnd.get() ? "ran BEFORE the copy ended" : "ran after the copy ended"));
        }
        System.out.println("  summary: copyCompleted=" + copyOk + " copyThrew=" + copyIse
                + " closeThrew=" + closeThrew + " closeReturnedBeforeCopyEnded=" + closeBeforeCopyEnd
                + " cleanupBeforeCopyEnded=" + cleanupBeforeCopyEnd + " of " + rounds);
        // The one property the lease contract needs: the release (cleanup) never runs while a
        // read through the view is still in progress. A copy that completed after the cleanup
        // ran would be exactly that.
        check(cleanupBeforeCopyEnd == 0, "the cleanup never ran before the in-flight copy ended "
                + "(cleanupBeforeCopyEnded=" + cleanupBeforeCopyEnd + ")");
        check(closeThrew == 0 && copyIse == rounds,
                "observed: close does not fail; it waits, and the in-flight copy is aborted with "
                + "IllegalStateException (closeThrew=" + closeThrew + ", copyThrew=" + copyIse + ")");
        check(cleanupBeforeCopyEnd == 0 || copyOk == 0,
                "the cleanup never ran before a copy that then COMPLETED (cleanupBeforeCopyEnded="
                + cleanupBeforeCopyEnd + ", copyCompleted=" + copyOk + ")");
    }
}
