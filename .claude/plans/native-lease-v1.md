# native-lease-v1 — the native lifetime lease contract (work package J1)

**Status: PROPOSED contract (2026-11-20).** It amends `epoch-recheck-v3.md` §3
for borrowed native-memory views. It is a contract only. No ABI symbol, Rust
code, Java code or build file changes with this document. Implementation is a
later, separate change, and it starts only after the owner accepts this one.

The owner's standing rule governs the order of work: *"Do not add new ABI
symbols before their native capabilities and lifetime contracts have been
established."* This document is that contract. §4 proposes an ABI shape so the
contract can be judged against something concrete. Everything in §4 is a
proposal and is not added.

Source of the requirement: the Bardioc integration design,
`LANCE_ENGINE_INTEGRATION_DESIGN.md` §6.5 (`:487-579`), marked there "APPROVED
(owner decision 1, 2026-11-20). Not built." (`:489`) The design records the
decision in its supersession table (`:162`). The parts of the decision that this
contract carries:

- Leases are approved. A lease count above zero pins the memory behind a view
  (`:513-516`).
- Pinning is native. "The count is native state; Java holds no authority over
  reclamation." (`:515-516`)
- Lease handles are generation-checked (`:517-520`).
- Java Arena closure and native release cooperate. The design says the owner
  "stated that Java Arena closure and native release must cooperate and that the
  Arena cleanup callback must not be the only protection" (`:505-506`), and
  "Native lifetime safety does not depend on the Arena cleanup callback"
  (`:510-511`). The owner's own words, in decision 1 of 2026-11-20: "Do NOT
  rely exclusively on the Arena cleanup callback for native lifetime safety."
  The design paraphrases that sentence in the two places quoted.
- Required tests: concurrent read/close, double release, stale handles,
  eventual reclamation (the owner's four), and the design's further rows,
  release ordering and transaction closure versus valid segments (`:557-566`).

The JDK-side facts the contract relies on come from the new reproducer
`valhalla-lab/reproducers/R13_SharedArenaCloseVersusRead.java` and its output
`R13-observed.txt` (JDK `28-beta+16-ea`, 4 vCPU Xeon, `R13-observed.txt:1-3`), and
from the later reproducer `R14_CloseWaitsForInFlightAccess.java` with the HotSpot
source reading (§3.5). §2.4 states R13's. They differ from the design's wording in
one place, which matters (§7, departure 1). The open decisions were tested and
balanced on 2026-11-20; the record is §9.

---

## 1. What is superseded and what is not

### 1.1 Superseded

`epoch-recheck-v3.md` §3 (`:198-244`) decided: "No read/close lock, no native
lifetime lease." That sentence is superseded **for borrowed native-memory
views** only. A borrowed view is a Java segment over native memory that lgj
lends out through the new borrow call (§4). Nothing else changes scope.

The three reasons v3 gave, and what the lease does to each:

| v3 §3 reason | Effect of this contract |
|---|---|
| 1. The suite has no threads, so an interleaving test's red is a crash ("the guard that cannot bark") (`:205-210`) | Answered by §5. Every test has a disable arm that removes the pin and keeps the allocation mapped, or that checks a count or status only. No red arm reads freed memory. This follows v3 C1 (`:97-133`). |
| 2. `Engine.windowOf` builds an unbounded segment with no scope check (`Engine.java:542`), and `Engine.close` is reachable outside the facade (`Engine.java:91-93`) (`:211-217`) | A borrowed view is not built by `windowOf`. It is bound to a shared Arena (§3.5). The native pin does not depend on who calls `Engine.close`. |
| 3. The window is pre-existing, cross-thread-only and already unguarded facade-wide (`:218-226`) | Closed for borrowed views, in the lifetime sense. See §1.3 for what stays open. |

### 1.2 Not superseded

These stay exactly as they are:

- The shipped liveness probe (v3 §6, `:570-625`; the seam at
  `RowStore.java:453-455`). It guards cached windows that are not leased.
- The generation-checked handle rule (`abi.md` §4, `:309-353`). A lease handle
  follows it (§3.2).
- Every existing falsifier (v3 D4, `:596-599`).
- The v3 lesson at `:125-133`: when a correction targets the observation of an
  unsafe operation, ask whether the operation still happens. §5 applies it to
  every test.
- The unleased facades. `RowStore.lanes[]` (`RowStore.java:443`) and
  `Mask.words()` (`Mask.java:317-345`) still hold unbounded windows built by
  `Engine.windowOf`. This contract does not touch them. Whether and when they
  move to leased views is open question Q5.

### 1.3 Effect on `ISS-LGJ-CACHED-DESCRIPTOR-CROSS-THREAD-WINDOW` and the sole-closer contract

`abi.md` states the issue and its closing condition at `:398-400`: it "closes
when the ABI defines a concurrent access protocol that makes this enforceable".
The window it names is a close landing between a probe's return and the segment
read (`abi.md:390-392`).

For a borrowed view that window is closed by the pin. A close of the owner
handle, from any thread and by any route, moves the owner to `closing` (§3.3)
and frees nothing while a lease is outstanding. The segment read then reads
live memory.

What the lease closes:

- Reclamation of memory under a live borrowed view, by a second closer, a
  concurrent closer or a violated sole-closer contract.

What remains, and stays recorded:

- Any site that still reads an unleased `windowOf` segment. For those the
  sole-closer contract (`abi.md:364-396`) and the probe narrowing apply
  unchanged. The issue stays OPEN for those sites.
- Concurrent Java writers. A mask word lane is `WRITABLE`, and the registry's
  own note says a concurrent Java writer "would need a documented protocol,
  which this ABI version does not define" (`exports.rs:393-397`). A lease
  protects lifetime. It says nothing about data races on contents.
- Native code that is handed a raw address (§3.6).

Proposal for the board, following v3 W4 (`:288-301`): close the issue per site,
as each site moves to a leased view, and mint no new status word. The status
vocabulary belongs to the board owner.

---

## 2. Current state, with sources

### 2.1 Native

- A handle is `(generation << 32) | index`. Generation starts at 1, so handle
  `0` never resolves (`registry.rs:17-18`, `:237-246`).
- `resolve` takes a short read lock, rejects a stale generation or an empty
  slot with `INVALID_HANDLE`, clones the `Arc`, and drops the lock
  (`registry.rs:276-287`).
- `close` takes the entry out, bumps the generation (skipping 0), and drops the
  `Arc` outside the registry lock. The comment states that other in-flight calls
  "may still hold clones; the storage is freed when the last one goes"
  (`registry.rs:322-345`).
- `insert` reuses a free slot at its already advanced generation
  (`registry.rs:249-270`).
- A mask resolved with its parent compares the parent's generation and returns
  `PARENT_CLOSED` (`registry.rs:302-315`). `create_mask` resolves its parent
  through `resolve` (`registry.rs:431`).
- A mask owns its words, a boxed slice allocated once (`registry.rs:54-58`). Its
  `lgj_close` never cascades to children (`Mask.java:247-256`).
- `ResourceEntry` has immutable fields today: kind, epoch, rows, parent,
  parent_gen, payload (`registry.rs:134-152`).
- `lgj_close` is a thin call to `registry::close` (`exports.rs:236-238`).
  `lgj_resource_info` and `lgj_lane_describe` resolve the handle first
  (`exports.rs:251-265`, `:290-352`). `lgj_mask_describe` resolves the mask with
  its parent (`exports.rs:408-439`).
- The registry serializes only open and close globally. Bulk operations do not
  (`abi.md:355-362`).
- There are 40 call sites of `registry::resolve*` in `exports.rs` (counted with
  a content search; the count is a measure of audit size, not a claim about
  behaviour). All pass through `registry.rs:276` or `:302`.

### 2.2 Java

- `Engine.windowOf` builds `MemorySegment.ofAddress(addr).reinterpret(byteLen)`
  (`Engine.java:531-545`). The segment has no scope tied to the resource.
- `Engine.close(long)` is public inside `internal.ffm` (`Engine.java:91-93`).
- `RowStore.lane()` calls `requireOpen`, fills `lanes[]` lazily through
  `Engine.describeLane`, and passes cached windows through `LaneProbe.check`
  (`RowStore.java:445-456`). `RowStore.close()` sets a plain boolean, calls
  `Engine.close(handle)`, then closes its Arena (`:548-558`).
- `Mask.words()` re-describes before each use of its cache and compares epochs
  (`Mask.java:317-345`).
- `NativePattern.close()` holds a lock and closes its child masks, then the
  handle (`NativePattern.java:123-145`). `RowStore` and `Mask` have no such lock.

### 2.3 What the ABI says today

`abi.md` §4: "There is **no code path in which a stale handle dereferences freed
memory**" (`:338-339`). The exception it states is the cached descriptor Java
reads directly: "that read resolves nothing, so no status is returned at all"
(`:352`). The sole-closer contract is "documented, not enforced" (`:371-381`).

### 2.4 What R13 observed (JDK `28-beta+16-ea`)

The backing memory in R13 comes from `Arena.global()` and is never freed. It
stands in for lease-pinned native memory. Only the view is bound to a shared
Arena through `MemorySegment.ofAddress(a).reinterpret(size, arena, cleanup)`
(`R13_...java:14-17`, `:67-68`). No arm reads freed memory.

| Id | Observation | Output |
|---|---|---|
| F1 | After the shared arena is closed, a read through the view throws `IllegalStateException`. | `R13-observed.txt:8` |
| F2 | The cleanup runs exactly once, at close, never before. A second close throws `IllegalStateException` and does not run the cleanup again. The backing memory is untouched. | `:6-11` |
| F3 | 200 rounds, one reader doing scalar reads, main thread closing: 7,711,808 reads; zero reads that observed the cleanup flag set before starting their access returned a value; every reader ended with `IllegalStateException`; close never threw. | `:12-16` |
| F4 | 10 rounds, close attempted alongside another thread's one 256 MiB `MemorySegment.copy` out of the view: close never threw and always ran the cleanup; it returned after about 24-36 ms; all ten copies threw `IllegalStateException`; no copy that started after the release completed normally. Whether each close overlapped its copy is not established (see below). | `:17-31` |

F4's summary line: `copyCompleted=0 copyThrew=10 closeThrew=0
closeReturnedBeforeCopyEnded=0 cleanupStampBeforeCopyEndStamp=0 of 10`
(`R13-observed.txt`).

Three limits on how to read F3 and F4.

- **The F4 timings are not ordering evidence.** The reader takes its end stamp
  after the copy returns, so a reader descheduled between the return and the
  stamp records a late end, and a cleanup that legally ran after the access had
  stopped then looks early. Codex reproduced exactly that false report on a
  different JDK (25.0.2) during review of this document. R13 therefore prints
  the timings and asserts nothing on them.
- **The ordering evidence is one-directional.** The cleanup sets a flag, and the
  reader reads that flag before it starts an access. An access that began with
  the flag already set and still returned a value would mean the release ran
  while the view was readable. F3 checks this per scalar read (0 of 7,711,808
  in the recorded run) and F4 per bulk copy (no copy that observed the flag set
  before starting completed normally). This shows only that no access which saw
  the release flag set before it began succeeded. An access whose flag check
  came just before the release, and whose read came just after, is not counted
  either way. It does not show the reverse, and it is not a proof of the JDK's
  internal rule.
- **The race is real on the pinned build too.** In the run recorded at commit
  `220d76d`, one F4 round's stamps put the cleanup before the copy's end stamp
  (the close returned after 0.6 ms). Under the old assertion that round would
  have been reported as a release during a read, although the copy had thrown and
  no access succeeded after the release. In the current recorded run no round
  shows it. Either way the stamps are not asserted.
- **Release state is a flag, not a timestamp.** `System.nanoTime()` may be
  negative, so F4 records the cleanup in a boolean and uses the timestamp only
  for the printed timings. A round whose reader does not terminate, or whose
  copy has no single outcome (completed or threw), fails and stops F4.
- **Overlap is not established.** The reader reads the release flag just before it
  enters the copy, but nothing marks the moment the copy is inside its access. A
  round can close before the copy has started or after it has finished. So "the
  in-flight copy was aborted" is one possible reading of the ten
  `IllegalStateException`s, not a demonstrated one, and R13 does not require it.
  In particular R13 does not establish that close waits for an in-flight access,
  or that the release cannot run while one is still reading.
- **A bulk copy that throws may leave a partial destination.**

What R13 did **not** test is listed in §5.3 as prerequisites.

---

## 3. The contract

Words "must" and "never" below are the contract. §4 is how a later change may
meet it.

### 3.1 Native ownership and the lease count

- Every resource that can lend a view (pattern, row store, mask) is owned
  natively, by its registry entry.
- Each such entry carries a **lease count**: the number of live, unreleased
  leases on it. The count is native state. Java holds no authority over it.
- A lease count above zero pins the entry. While pinned, the entry's memory is
  not reclaimed, whatever Java does or fails to do.
- A lease pins the entry of the handle it was borrowed from. A lease on a mask
  pins the mask's own words (which the mask owns, `registry.rs:54-58`). It does
  not pin the mask's parent.
- Native operations that are already in flight keep the memory alive through
  their own `Arc` clone (`registry.rs:340-343`). That is independent of the
  lease and stays.

### 3.2 Lease handles

- A lease is named by a `u64` handle with the same layout and the same
  generation rule as every other handle (`abi.md:316-336`).
- A stale, released, fabricated or wrong-kind lease handle is refused with a
  status before any state changes. It never reaches memory.
- `lgj_close` on a lease handle returns `WRONG_RESOURCE_KIND`. Release goes
  through its own call only (§4).

### 3.3 The three-state machine

An owner entry is in one of three states.

| State | Meaning |
|---|---|
| open | Operations and new leases are accepted. |
| closing | A close was requested while the count was above zero. Memory is retained. No new operation and no new lease is accepted. Existing leases stay valid. |
| reclaimed | The memory is released and the slot generation is advanced. Every older handle is stale. |

Transitions, each a single critical section (§3.7):

| From | Event | To | Condition |
|---|---|---|---|
| open | close request | reclaimed | lease count is 0 |
| open | close request | closing | lease count is above 0 |
| open | borrow | open | count increases by 1 |
| open | release of a lease | open | count decreases by 1 |
| closing | release of a lease | closing | count stays above 0 after the decrement |
| closing | release of the last lease | reclaimed | count reaches 0. This happens exactly once. |
| closing | close request | closing | refused as a double close, no state change |
| reclaimed | any | reclaimed | none. The state has no exit. |

Status each operation returns, by state. Existing codes are from `abi.md:229-249`.

| Operation | open | closing | reclaimed |
|---|---|---|---|
| `lgj_close(owner)` | `OK` | `INVALID_HANDLE` (double close, as today) | `INVALID_HANDLE` |
| borrow (§4) | `OK` | `PARENT_CLOSED` | `INVALID_HANDLE` |
| `lgj_lane_describe`, `lgj_mask_describe`, every bulk operation, `lgj_mask_create` over this owner, `lgj_resource_info` | as today | `PARENT_CLOSED` | `INVALID_HANDLE` |
| a child mask's operation, owner is its parent | as today | `PARENT_CLOSED` | `PARENT_CLOSED` (`registry.rs:309`) |
| release of a lease on this owner | `OK` | `OK`; the last one reclaims | not reachable: reclaim needs count 0 |
| lease count / state read (§4) | `OK` | `OK`, reports closing | `INVALID_HANDLE` |
| release of a lease handle already released | `INVALID_HANDLE` | `INVALID_HANDLE` | `INVALID_HANDLE` |

Notes on the table.

- **The two tables above describe Choice B1** (§4.4). Under the recommended B3,
  `closing` is not a slot state. Close is exactly today's `registry::close`: the
  owner handle is `INVALID_HANDLE` from the close request on, and the memory is
  pinned by the leases' `Arc` clones until the last lease is released. The
  "closing" columns and rows then do not occur, `PARENT_CLOSED` is not used for an
  owner's own handle, and a closed owner's count cannot be read per owner (§4.4).
  The "reclaimed" generation advance happens at the close request. The
  one-reclaim-exactly-once rule holds by `Arc` drop.
- `PARENT_CLOSED` in `closing` is the design's choice (`:527`). Its documented
  meaning is "child (mask) outlived its parent resource" (`abi.md:239`), so on an
  owner's own handle it is a stretch. Java also maps it to `ClosedResourceException`
  with the message "... was called on a mask whose parent resource is closed (ABI
  status PARENT_CLOSED = -7)" (`Status.java:128-131`), which is misleading for an
  owner's own handle. Whether to keep it or add a status is open question Q1; B3
  dissolves it.
- With no outstanding lease the machine is today's behaviour: close goes straight
  to reclaimed, and `closing` is never entered. No existing caller holds a lease,
  so no existing behaviour changes.
- A mask whose parent is `closing` returns `PARENT_CLOSED` from every operation
  that resolves it with its parent. That needs the parent lookup at
  `registry.rs:307-312` to test the closing state, not only the generation.

### 3.4 No early free, and a leak is visible

- Reclamation happens only on the transition in §3.3. Nothing reclaims a leased
  view by force: not a transaction close, not a dataset close, not a process
  policy inside lgj.
- A lease whose release never runs (an Arena that is never closed, a cleanup
  that never executes) stays pinned. That is a **pinned leak**. It is visible in
  the lease count and in the owner's `closing` state. It is never an early free.
- **Measurable invariant.** After every borrowed view's Arena has been closed
  and every owner closed, the process-wide lease count is zero, and every owner
  handle is `INVALID_HANDLE`. The design's measurement table asks for the same
  counter (`LANCE_ENGINE_INTEGRATION_DESIGN.md:2326`).

### 3.5 Release ordering

The order for one borrowed view is fixed:

1. The result object closes its shared `Arena`.
2. The Arena's cleanup runs, and the cleanup is what releases the lease.
3. Only then can the owner reach `reclaimed`.

How the view is built (proposal for the Java side):

```java
Arena arena = Arena.ofShared();
long lease = <from borrow>;
MemorySegment view = MemorySegment.ofAddress(addr)
        .reinterpret(byteLen, arena, s -> Engine.leaseRelease(lease));
```

`reinterpret(size, arena, cleanup)` is the call R13 exercised (`R13_...java:67-68`).

Rules:

- The lease handle exists only as a captured local of the cleanup lambda. It is
  never returned, stored in a field or logged as a value to act on.
  `Engine.leaseRelease` has exactly one call site. A source fence in the style of
  `DoctrineFenceTest` checks this (§5, T3).
- The ABI cannot enforce this. The release symbol is callable by any code in the
  process. The design says "There is no release call that Java can make while the
  arena is still alive" (`:538-539`). That is true by Java construction and by the
  fence, not by the ABI. The contract states it that way.
- The result object's `close()` is the Arena close. It is not a separate native
  call (`:540-541`).
- Owner close and view close are independent and may come in either order or
  concurrently. All four orders must end with the count at zero:

| Order | Result |
|---|---|
| view Arena closes, then owner closes | release makes count 0 while open; the owner close then reclaims directly |
| owner closes, then view Arena closes | owner is `closing`; the release reclaims |
| both at once | serialized by §3.7; whichever runs second performs the reclaim, exactly once |
| view Arena never closes | owner stays `closing`; a pinned leak (§3.4) |

**What R13 observed on this JDK, and what it did not establish.**

- Observed: in every round the close returned normally (it never threw), after
  about 24-36 ms in F4, and every reader's access ended with
  `IllegalStateException` (F3, F4).
- Observed: no access that saw the release flag set before it began returned a
  value, in F3 or F4. An access whose flag check came just before the release is
  not covered by this check (§2.4).
- **Not established by R13:** that any F4 close overlapped a copy that was inside
  its access. A copy that threw may have started after the close. R14 closes
  this gap (below).

So R13 alone neither confirms nor refutes the design's rule 4(a). R13 could not
tell whether a close overlapped an access. R14 (below) adds a per-round overlap
signature, and the HotSpot source settles the mechanism on this build.

**What R14 observed** (`valhalla-lab/reproducers/R14_CloseWaitsForInFlightAccess.java`,
output `valhalla-lab/reproducers/R14-observed.txt`, README section "## R14"; three
runs, all green, same JDK `28-beta+16-ea`).

- *Overlap signature.* A copy that threw `IllegalStateException` **and** left every
  source byte in the destination passed the liveness check before the close and was
  still inside its access when the handshake reached it: that round overlapped.
  This inference uses the JDK source structure (`Unsafe.copyMemory` is one
  `copyMemory0` call, `Unsafe.java:1000-1010` in `src.zip`). It is not a black-box
  fact.
- *Ordering oracle (black-box).* The cleanup, standing in for `lgj_lease_release`,
  first poisons the last 1 MiB of the source through a never-freed alias and then
  sets a flag. The copy reads forward. A release during the copy would put poison in
  the destination.
- *G1.* 32, 64, 128 and 256 MiB, 20 rounds each, three runs: 240 of 240 rounds
  overlapped, 0 carried poison, every close returned normally and ran the cleanup
  once.
- *G2.* In overlap rounds the median close duration grows with the size (run 1: 2.6,
  6.9, 13.8, 27.6 ms against standalone copy medians of 2.9, 5.7, 12.0, 27.4 ms;
  `R14-observed.txt`). The close waits for the in-flight access. Close latency is
  bounded by the longest single in-flight access.
- *G3 (red arm, sensitivity).* A "release" from another thread with no close put
  poison in the destination in 10 of 10 rounds. The oracle can fail.
- *G4.* A view passed as a segment to a downcall (libc `memcpy`, 256 MiB): a close
  during the downcall threw `IllegalStateException` 5 of 5 times and the cleanup did
  not run during it. A close after the downcall succeeded and ran the cleanup once.
- Every overlapping copy wrote its full destination and still threw. This confirms
  the reader rule below: a thrown copy's destination may be complete and is still
  invalid.

Limits: one JDK build, one 4 vCPU machine, a bulk-copy access only (a scalar access
is not a distinct case in the source reading, but R14 did not run one).

**What the property rests on: the JDK's close mechanism, read in source (Q9).**
Two readings, both of the pinned build `28-beta+16-ea`:

Java side, `lib/src.zip` (not tested by itself):

- `MemorySessionImpl.close()` is `justClose(); resourceList.cleanup();`. The
  cleanup actions, which include the one passed to
  `MemorySegment.reinterpret(size, arena, cleanup)` and so the lease release, run
  only after `justClose()` has returned.
- `SharedSession.justClose()` marks the session closed and then calls
  `ScopedMemoryAccess.closeScope(...)`.
- The `ScopedMemoryAccess` class comment describes that call: it "initiates
  thread-local handshakes with all the other VM threads", relying on "the idea
  that accessing memory is atomic with respect to checking the validity of the
  session", so that "a thread that wants to perform memory access will be
  suspended either *before* a liveness check or *after* the memory access".
- `Arena.close()`'s specification: if it completes normally, the segments "can no
  longer be accessed"; it throws `IllegalStateException` "if a segment associated
  with this arena is being accessed concurrently, e.g. by a downcall method
  handle".

HotSpot side, `src/hotspot/share/prims/scopedMemoryAccess.cpp` at openjdk tag
`jdk-28+16` (commit `f0747596dea3e6a1c852dc227a0eb10ddbf5b015`).
`ScopedMemoryAccess_closeScope` is at lines 265-284, its closures at 140-258:

- `Handshake::execute` runs a closure against every thread. A thread with no Java
  frames, or already about to throw an async exception, is skipped.
- A thread found inside a `@Scoped` method frame whose locals hold the session gets
  an **asynchronous** handshake installed. It throws `ScopedAccessError`, which the
  `@Scoped` method turns into `IllegalStateException`, the next time the thread
  stops at a safepoint poll. The comment (lines 133-139) says it is "only run when
  the target thread is itself stopped at a safepoint poll, and not when the thread
  is actively executing code, such as a memory access".
- A thread stopped in compiled code outside a `@Scoped` frame, whose nmethod has
  scoped access, is deoptimized so it redoes the liveness check.
- `closeScope0` then **waits**: `while (async_exceptions.load_acquire() > 0)
  spin_yield.wait();`. The comment says it waits "because we will free the memory
  after this. This guarantees the target thread does not continue to access the
  memory." It returns `void` and has **no failure path**.
- `SharedSession.justClose()` (`src.zip`, lines 83-93) does throw `alreadyAcquired`
  when the acquire count is above 0. That is the downcall case (a segment passed to
  a downcall acquires the session) and matches `Arena.close`'s specification
  sentence about downcalls. R14 G4 observed it.
- `Unsafe.copyMemory` is one non-chunked `copyMemory0` call. A bulk copy is one
  non-interruptible VM call, so the handshake reaches that thread only after the
  call returns. That is why close latency follows the copy time (G2).

Together these say: the release runs after a handshake that waits until every
thread inside a scoped access through the arena's segments has been made to throw.
That is the ordering the contract needs. It is a JDK guarantee, not an lgj one.

Corroboration that this HotSpot file belongs to the pinned build. The pinned build
is Temurin `28-beta+16-ea`. Its `src.zip` copies of `SharedSession.java` and
`MemorySessionImpl.java` are byte-identical to the tag's. Its `libjvm.so` contains
the class names `CloseScopedMemoryHandshakeClosure` and
`ScopedAsyncExceptionHandshakeClosure` and the string "must process to avoid hang".
The exact HotSpot commit of the build could not be fetched (Temurin's SOURCE field
is a short SHA that GitHub would not resolve). So "the HotSpot file is the build's"
is strongly corroborated, **not proven**.

The earlier weakness, now resolved: the `ScopedMemoryAccess` class comment says
that if a thread "is found accessing" the session "the handshake fails, and the
session will not be closed" (`ScopedMemoryAccess.java:63` in `src.zip`). On this
build that comment is **stale**. `scopedMemoryAccess.cpp:265-284` has no failure
path; it waits. R13 and R14 never saw a close throw against a Java access.

One weakness stays, recorded and not smoothed over: the guarantee covers accesses
made through the JDK's scoped access paths. It says nothing about native code that
was handed a raw address (§3.6), which is why §3.6 forbids that. The overlap
inference in R14 also depends on the JDK source structure and would need to be
re-read on another build.

Q9 (§6) is answered on `28-beta+16-ea`: close does not fail against a scoped Java
access, it waits for it, and the release runs after. Close throws only when a
downcall holds the view. Re-open it on any JDK change (§9).

**Scope of the ordering guarantee.** The ordering above is established for
`28-beta+16-ea` only. The repository accepts other JDK 28 builds, and on those
it is **not established** until the same two checks pass there: R14 runs green,
and `scopedMemoryAccess.cpp` at that build's tag is re-read and still has no
failure path and still waits. Until then the borrow symbol must not be relied on
for that build. Q8 is the list of builds to check. Proposal (not decided): the
Java borrow wrapper refuses to lend a view on a `Runtime.version()` that is not
on a checked list, and the list is extended only with a recorded R14 run.

**Reader rule.** A reader must treat `IllegalStateException` from any access
through a borrowed view, a bulk copy included, as "result invalid; destination
partially written". It must not use a destination that a copy left behind after
such an exception. Proposal: the Java facade catches it at the access boundary and
rethrows `ClosedResourceException` with that sentence in the message.

**What protects what.** Two lines, neither enough alone:

- The native pin protects the native side: no reclamation with a lease count above
  zero, whatever Java does.
- Arena liveness protects the Java side: no read through a segment after its Arena
  closed.

### 3.6 Native code and raw addresses

A Java segment bound to an Arena protects only reads made through that segment in
Java. It does not protect native code that was handed the address. R13 did not test
a segment passed to a downcall, and neither R13 nor R14 tested a raw address passed
as a `long`.

R14 G4 observed one half. A view passed **as a segment** to a downcall is protected
by the session's acquire count: a close during the downcall threw
`IllegalStateException` 5 of 5 times, the cleanup did not run during it, and a close
after the downcall succeeded and ran the cleanup once. A raw `long` is not
protected, because nothing acquires the session. Consequence for the facade: the
result object's `close()` can throw `IllegalStateException` if someone violated the
rule and a segment is still in a downcall. That exception must propagate. It must
not be swallowed, because a swallowed one hides a view whose release has not run.

Therefore:

- **A borrowed view's address must never be passed to native code as a raw
  integer.** Not as a `long`, not as an `ADDRESS` taken from the view.
- Native operations name the resource by handle. They resolve it natively, and the
  native pin protects them.
- Proposal: a fence leg asserts that the lane address field is read in exactly one
  place (the borrow wrapper) and that no downcall signature in `Downcalls`
  receives a value derived from it.

### 3.7 Atomicity and idempotent release

- The check-and-increment in borrow, the decrement-and-reclaim in release, and the
  check-and-transition in close are each one critical section. Today `close`
  already takes the registry write lock (`registry.rs:323-339`) and `insert`
  takes it (`:250`). The three new paths use the same lock. This is what makes
  "reclaim exactly once" and "no reclaim under a new lease" hold. A lock-free
  version with one atomic word for (state, count) is allowed if it keeps the same
  guarantees and passes T1 and the Rust stress test in §5.4.
- Cost: borrow and release serialize globally as open and close do. That has not
  been measured. The contract makes no cost claim. One borrow and one release
  happen per view, never per row.
- Release is idempotent in effect. A second release of one lease returns
  `INVALID_HANDLE` and does not change any count. This holds after the lease slot
  is reused: the old handle carries the old generation, so it cannot decrement the
  new lease's owner.

---

## 4. Proposed ABI shape — proposal, not added

No symbol below exists. This section is how a later change could meet §3.

### 4.1 Minimum surface

Three symbols and no new status. All follow `abi.md` §3: `i32` return, `out_*`
written only on `OK`.

| Proposed symbol | Does |
|---|---|
| `i32 lgj_lane_borrow(u64 handle, u32 lane_id, LgjLaneDesc* out_desc, u64* out_lease)` | In one critical section: resolve `handle`, require `open`, fill the lane descriptor exactly as `lgj_lane_describe` / `lgj_mask_describe` do, increment the count, create the lease. A mask handle returns its word lane when `lane_id` is 0, else `INVALID_LANE`. Statuses as the §3.3 table, plus `NULL_ARGUMENT`, `INVALID_LANE`, `ALLOCATION_FAILED` (`-12`) if a lease cannot be created or the count would overflow. |
| `i32 lgj_lease_release(u64 lease)` | Resolve the lease (kind check), remove it, decrement the owner's count, reclaim if the owner is `closing` and the count is 0. A stale handle returns `INVALID_HANDLE`. A non-lease handle returns `WRONG_RESOURCE_KIND`. |
| `i32 lgj_lease_count(u64 resource, u64* out_live, u32* out_state)` (**B1 form, the fallback**) | `resource == 0` reports the process-wide live lease count and state 0 (handle 0 never resolves, `registry.rs:17-18`, so 0 is free to mean "all"). A live owner reports its own count and its state, 0 open or 1 closing, and is the one call that resolves a `closing` owner. A reclaimed handle returns `INVALID_HANDLE`. |
| `i32 lgj_lease_count(u64 resource, u64* out_live)` (**changed under B3, the recommendation**) | `resource == 0` reports the process-wide live lease count. A live owner reports its own count. A closed owner returns `INVALID_HANDLE`. No `out_state`: there is no closing state. The count is a snapshot; assert on it only after quiescence. |

Which `lgj_lease_count` form applies depends on Choice B (§4.4). Q2 proposes it as a
regular production symbol under either form.

`LgjLaneDesc` is unchanged (56 bytes, `abi.md:407-420`). No manifest field is added.
`lgj_close` keeps its signature and its `OK` and `INVALID_HANDLE` meanings; only
the §3.3 behaviour on a leased owner is new.

### 4.2 `lgj_close` on a resource with leases

This describes B1. Under B3, `lgj_close` is unchanged in every case: it takes the
entry out and bumps the generation as today, and the leases' `Arc` clones keep the
memory alive.

- Count 0: as today, straight to reclaimed (`registry.rs:322-345`).
- Count above 0: return `OK`, set `closing`, retain the entry in its slot, do not
  bump the generation. The memory is freed by the release that reaches 0.
- Already `closing`: `INVALID_HANDLE`.

### 4.3 Version, manifest, holders, compatibility (per `abi.md` §2)

- **Minor bump 14 to 15** (`abi.rs:112`). Additive, so a minor-14 Java still loads
  and sees none of it (`abi.md:95-96`).
- **Manifest and docs.** `abi_minor` comes from the constant (`abi.rs:650`), so
  the manifest follows. Add the minor-history entry and §3 text to `abi.md`, and
  update the symbol count in `abi.md` §1 and §7.
- **Lazy holder.** A `Minor15` holder class in `Downcalls`, initialized on first
  access, like `Minor13` (`Downcalls.java:498-505`). Eager resolution would break
  loading against an older library (`abi.md:122-140`).
- **Gate.** `Abi.requireMinor(15)` (`Abi.java:168`) in each new `Engine` wrapper,
  before any downcall.
- **`OldAbiCompatTest` leg** (`OldAbiCompatTest.java:85-110`). A minor below 15
  must yield `AbiMismatchException` naming minor 15. A minor of 15 or more must
  borrow, release and read the count back to zero. Both directions are required,
  as the existing legs do.
- **Status.** No new status code is required under B3. Under B1, Q1 proposes
  `LGJ_ERR_RESOURCE_CLOSING = -19`.

### 4.4 Two real choices

The recommendations below are proposals for the owner, not decisions. The evidence
and the re-evaluation triggers are in §9.

An executable model of Choice B exists: `valhalla-lab/lease-model/`, a stand-alone
cargo crate (not lgj code, no ABI; `cargo test --manifest-path
valhalla-lab/lease-model/Cargo.toml`). It mirrors `registry.rs` (slots with
generation, entries in `Arc`, close takes the entry and bumps the generation) with
variants B1, B2 and B3. Caveat: it is a model with one mutex, not the real
`RwLock` registry. It tests the logic of each variant, not the implementation.

**Choice A — how a borrow is shaped.**

- A1 (recommended): `lgj_lane_borrow` fills the descriptor and takes the lease in
  one call, as above.
- A2: `lgj_lease_acquire(handle, &lease)` pins the owner, and the existing describe
  symbols fill the descriptor afterwards.

Recommendation: A1. The descriptor and the lease cannot diverge, there is one
crossing per view, and there is no state in which a lease exists and the describe
is refused. A2 adds one symbol of the same count but leaves a gap between acquire
and describe in which a close request makes describe return an error after the
lease was taken, and Java must then release. A2's one merit is that it reuses the
describe code unchanged. **Model result:** with a two-step borrow, a lease can be
live while describe is refused, under every B variant. So A2 always needs an
error-path release in Java. A1 has no such state.

**Choice B — what close does while a lease is live.**

- B1 (the design's table): closing state kept in the slot; the generation advances
  at `reclaimed`; `closing` retains the entry and answers `PARENT_CLOSED` (§3.3).
- B2: `lgj_close` bumps the generation at once and leaves the entry in the slot; the
  lease holds the entry alive, and the owner handle is `INVALID_HANDLE` from the
  close request. The earlier text of this section said "B2 changes only `close`".
  **That is false.** The model shows that after a close with a live lease, a handle
  forged with the slot's current generation resolves under B2 and only B2, because
  the slot's generation advanced while the entry stayed. B2 therefore also needs a
  closing check in `resolve`. B2 as written is rejected.
- **B3 (new): close is exactly today's `registry::close`; each lease holds an `Arc`
  clone of the owner's entry.** The memory lives until the last lease is released.
  There is no closing state in the slot. This reuses the mechanism that already
  protects in-flight native calls (`registry.rs:340-343`, "Other in-flight calls
  may still hold clones").

Model results (6 tests, all pass): all four close/release orders reclaim exactly
once with the count back to 0, and a never-closed view is a visible pinned leak (all
variants). A 2000-iteration concurrent close versus release reclaims exactly once
(all variants). No new lease and no operation on a closed owner (B1 returns
`PARENT_CLOSED`, B2 and B3 return `INVALID_HANDLE`). A double release is refused.

Code facts. Only five registry lock sites exist: `registry.rs:250` insert, `:278`
resolve, `:307` parent lookup, `:323` close, `:506` slot_count. Call sites in
`exports.rs` and `kernels.rs`: `resolve` 7, `resolve_kind` 13,
`resolve_mask_with_parent` 20 (the 40 of §2.1). `resolve_kind` and
`resolve_mask_with_parent` both go through `resolve`.

Recommendation: **B3, with B1 as the acceptable fallback; B2 rejected as written.**
Reasons for B3:

- It reuses an existing mechanism that lgj already relies on.
- It adds no slot state, and none of the 40 call sites change.
- Reclaim happens exactly once, by `Arc` drop.
- Forged handles are refused by today's generation rule.
- Q1 dissolves: a closed owner is `INVALID_HANDLE`, whose Java message is accurate.

Costs of B3, stated plainly:

- It departs from the approved design's table (`:527` wants `closing` observable as
  `PARENT_CLOSED`).
- A closed owner's lease count can no longer be queried per owner, only globally.
  Q4's debug tag would be the way to attribute a pinned leak.
- The slot is reused while the memory is still pinned, so slot-based accounting
  (`slot_count`, `registry.rs:506`) under-reports pinned memory.

B1 stays the choice if the Bardioc design needs per-owner `closing` visibility. B1's
cost is one check in `resolve` (`registry.rs:276`) and one in the parent lookup
(`registry.rs:307-312`). Those two cover all 40 call sites, provided the audit finds
no path that bypasses them.

---

## 5. Required tests

There are six, following the design's table (`:557-566`). The design's J1 row says
"five tests" (`:2226`) and omits release ordering. This contract keeps the table's
six (§7, departure 4).

### 5.1 Method: how every red arm is built

v3 C1's rule applies (`epoch-recheck-v3.md:97-133`): the red arm must remove the
guard and must **not** perform the unsafe read. Two layers:

- **Rust layer.** Tests inside the crate hold a `Weak<ResourceEntry>` taken before
  the close. `weak.upgrade().is_some()` says "memory still allocated";
  `is_none()` says "freed". No test dereferences. In this layer a red arm may free
  memory, because nothing reads it.
- **Java layer.** A segment is read, so the allocation must stay mapped in the red
  arm. The red arms use test-only **Cargo features** of the native crate, each
  built into its own library and selected the way `-Dlgj.oldlibrary` selects a
  library today (`OldAbiCompatTest.java:6-36`). The libraries are never shipped.
  The features:

| Feature | What it disables | What it keeps |
|---|---|---|
| `lease-pin-off` | borrow does not increment the count | borrow also leaks one `Arc` clone, so the allocation is never unmapped |
| `lease-release-unchecked` | release skips the generation check on the lease and decrements the owner's count again (floored at 0) | the owner stays open in the test, so reaching 0 reclaims nothing |
| `lease-no-reclaim` | the last release does not reclaim | the entry stays; this is a leak, not a free |
| `lease-no-generation-bump` | reclaim does not advance the slot generation | no memory is read; a new resource reuses the slot |

A feature adds no exported symbol. The alternative, a test-only export as v3 C1
used (`:118-123`), would be a new ABI citizen with its own minor and leg. It runs
against the owner's rule. Use it only if a feature-built library cannot be loaded
by the Java suite (open question Q7).

Every Java test reads **status and count first**, and compares content only in the
green arm. In a `lease-pin-off` run the memory is mapped, so a content comparison
would pass in the red arm too. The status and count assertions are the ones that
fail. This is stated per test so no one mistakes a content check for the guard.

Every test also guards against vacuity, as v3 does (`:72-83`): it asserts before
the close that the count is 1 (so a pin exists) and that the reader has completed
reads.

### 5.2 The six tests

**T1 — Concurrent read/close**

- *Shows.* One thread reads a borrowed view while another closes the owner. No
  fault. The owner is `closing` until the last lease is released.
- *Construction.* Open a row store with a known seed. Borrow a lane; the expected
  values are known from the deterministic generator (`abi.md:731-737`). Start a
  reader thread that loops reads and checks each against the expected value;
  latch until it has read. From the main thread call `Engine.close(owner)`. Sample
  `lgj_lease_count(owner)` and `lgj_resource_info(owner)`. Then close the view's
  Arena while the reader is still looping. Repeat for many rounds, as R13 F3 does
  (200 rounds).
- *Green.* After the owner close: state `closing`, count 1, `lgj_resource_info`
  returns `PARENT_CLOSED`, every read returns the expected value. After the Arena
  close: the reader ends with `IllegalStateException`; no read that began with the
  cleanup's flag already set returned a value (the flag is read before each
  read, as in R13 F3; no timestamps); count 0; owner handle `INVALID_HANDLE`.
- *Red.* Run on the `lease-pin-off` library. Borrow does not pin, so the owner
  close goes straight to reclaimed. The assertions "state is `closing`, count is
  1" fail with state `INVALID_HANDLE` and count 0. The reader reads leaked,
  still-mapped bytes, so no read touches freed memory. The reader's value checks
  still pass; they are not the guard.

**T2 — Double release**

- *Shows.* A second release of one lease returns its defined status and leaves the
  count unchanged.
- *Construction.* Borrow lease L1 on an owner that stays open. Call
  `Engine.leaseRelease(L1)` twice from a test (the test is in the same package as
  the facade, as v3's W5 route was, `:85-96`). Read the count before and after
  each call. Then borrow L2, which reuses L1's slot at a higher generation, and
  release the stale L1 once more.
- *Green.* First release `OK`, count 1 to 0. Second release `INVALID_HANDLE`, count
  still 0. After L2 is borrowed the count is 1; releasing stale L1 returns
  `INVALID_HANDLE` and the count is still 1. Also check the Java half: a second
  `arena.close()` throws `IllegalStateException` and the cleanup does not run again
  (R13 F2, `R13-observed.txt:9-10`).
- *Red.* Run on `lease-release-unchecked`. The stale release decrements the count
  again. The assertion "count is still 1 after releasing stale L1" fails (count 0).
  The owner is never closed in this test, so a count of 0 reclaims nothing and no
  memory is freed.

**T3 — Release ordering**

- *Shows.* The lgj side of the ordering: the lease is released only by the view's
  Arena cleanup, never before the Arena close, and the count reflects it. With
  part (c) it also shows, for a bulk copy, that the release did not run while a
  copy was still reading (R14's method, §3.5). That claim rests on the overlap
  signature, which uses the JDK source structure, plus the black-box poison oracle.
- *Construction.* Three parts.
  (a) A borrowed view, one reader thread looping accesses through it, the main
  thread closing the view's Arena. Ten rounds or more. The cleanup sets a flag
  and releases the lease; the reader reads that flag immediately before each
  access. Read the lease count immediately before `arena.close()` and
  immediately after it returns.
  (b) A source fence: `Engine.leaseRelease` has exactly one call site, inside the
  borrow wrapper's cleanup lambda, and the lease handle is not returned or stored.
  This is the `DoctrineFenceTest` pattern (`epoch-recheck-v3.md:260-268`).
  (c) The R14 bulk-copy case on a real borrowed view. One thread does one large
  `MemorySegment.copy` out of the view; the main thread closes the Arena about 1 ms
  after the copy's start signal. The cleanup (the real release, plus a test hook
  that poisons the last 1 MiB of the source through a never-freed alias) sets a flag
  after poisoning. Classify each round by R14's signature: copy threw
  `IllegalStateException` **and** the destination holds every source byte means
  overlap. Run at least 20 rounds per size. Add a G3-style red arm (below).
- *Green.* In every round the count is 1 before the close and 0 after. No access
  that saw the flag set before it began returned a value (this check cannot see an
  access whose flag check came just before the release; no timestamps are used).
  An access that threw `IllegalStateException` is treated as partial and its result
  is not used. The fence finds one call site. Part (c): overlap rounds exist, and in
  none of them does poison reach the destination.
- *Red.* A test-source variant of the borrow wrapper that releases **before**
  closing the Arena (build-time file swap, the pattern of `LaneProbe`,
  `RowStore.java:453-455`). The owner stays open, so a release to count 0 reclaims
  nothing. The assertion "count is 1 while the view is still readable" fails. The
  fence assertion also fails on a second call site. Part (c) red arm (G3-style): run
  the release from another thread during the copy with no Arena close. Poison
  reaches the destination, so the oracle is shown able to fail. The backing memory
  is never freed in this arm.
- *Not covered, stated.* Raw native access to the address (§3.6): the Arena protects
  nothing there. A scalar-access overlap is not separately tested. The overlap
  inference in (c) depends on the JDK source structure (one `copyMemory0` call, the
  handshake design), so on another JDK build it must be re-read, not only re-run.
  R14 on `28-beta+16-ea` is the evidence that this holds there (Q9).

**T4 — Stale handles**

- *Shows.* A handle from before reclamation is refused with a status after
  reclamation. No dereference.
- *Construction.* Borrow, keep the owner handle and the lease handle, close the
  Arena (release) and close the owner so it reaches `reclaimed`. Then use the old
  owner handle in describe, a bulk operation, `lgj_resource_info` and borrow; use
  the old lease handle in release and count. Then open a new resource so it reuses
  the slot, and repeat with the old handles.
- *Green.* Every call returns `INVALID_HANDLE` (a child mask returns
  `PARENT_CLOSED`, `registry.rs:309`). After slot reuse the old handles still
  return `INVALID_HANDLE` and the new resource is untouched (its count is
  unchanged).
- *Red.* Run on `lease-no-generation-bump`. After reuse the old owner handle
  resolves to the new resource and describe returns `OK`. The assertion "refused"
  fails. No freed memory is read: the old handle points at a live new resource.
  (The existing unit test `registry.rs:561` covers the same rule for plain close.)

**T5 — Eventual reclamation**

- *Shows.* After the last release the memory is reclaimed and the lease count is
  zero.
- *Construction.* Two layers.
  Rust: take `Weak<ResourceEntry>` from the registry slot before the close. Borrow
  twice. Close the owner. Check `upgrade().is_some()`. Release one lease; check
  `is_some()`. Release the last; check `is_none()`.
  Java: the same sequence through the facade, with `lgj_lease_count(0)` read at
  each step, ending at 0 after every view and owner is closed. Run a batch of N
  borrow/close cycles and check the process-wide count returns to its starting
  value. This is the invariant in §3.4.
- *Green.* `is_some` while any lease is out, `is_none` after the last release,
  process count back to baseline.
- *Red.* Run on `lease-no-reclaim`. The last release does not reclaim. The
  Rust assertion "`is_none` after the last release" fails, and the Java assertion
  "owner handle is `INVALID_HANDLE` and count 0" fails (state still `closing`). It
  is a leak, so nothing is freed or read.

**T6 — Transaction closure versus valid segments**

- *Shows.* Closing the owner cannot free memory that is still accessible through a
  valid Java segment. In this repository the closable owners are a pattern, a row
  store and a mask; the transaction and dataset owners of the Bardioc runtime use
  the same entry-level rule and add no new lease mechanism.
- *Construction.* Borrow a lane, read a checksum of the whole view, close the
  owner, read the checksum again, close the Arena. Variant: borrow the word lane of
  a mask, close the mask's parent (which has no lease and so reclaims at once),
  then read the mask view. The mask owns its words (`registry.rs:54-58`), so the
  view stays valid; a mask operation returns `PARENT_CLOSED` (`abi.md:352`).
  Then, in the mask variant, close the mask itself (it has a lease, so it goes to
  `closing`) and only then close the view's Arena.
- *Green.* The two checksums are equal after the owner close. The owner is
  `closing` and native operations on it are refused. In the mask variant: after
  the parent close the mask view still reads its words and a mask operation
  returns `PARENT_CLOSED`; after the mask's own close the mask is `closing` with
  count 1. After the Arena close, in both variants, the release brings the
  `closing` owner to `reclaimed` and the count to 0. (A release on an owner that
  is still `open` leaves it open, §3.3, so the mask must be closed before the
  Arena for the reclamation to be observable.)
- *Red.* Run on `lease-pin-off`. The checksum comparison cannot fail there, because
  the memory is leaked and mapped. The guard is again the status and count: the
  assertion "owner is `closing`, count 1 after close" fails. The Rust layer adds
  the direct statement with `Weak`: with the pin on, `is_some()` after the close;
  with the pin off, `is_none()` after the close. Neither dereferences.

### 5.3 Prerequisites: what R13 did not test

The implementation must establish each of these before it relies on it. Until then
they are unverified.

1. **A segment passed to a native downcall while the Arena closes.** Tested once by
   R14 G4 (libc `memcpy`, 256 MiB, 5 rounds): close throws, cleanup waits. Not
   tested with an lgj downcall. The contract still avoids the case (§3.6).
2. **A raw address passed to native as a `long`.** Not tested, and forbidden by the
   contract. The fence in §3.6 is the check.
3. **Memory owned by lgj's Rust allocator.** R13 used `Arena.global()`. The
   Rust-layer `Weak` tests and T5 cover the native lifetime. A test that borrows a
   real `Arc<[u8]>` lane and repeats R13 F1 to F4 on it is required.
4. **Other JDK builds.** R13 ran on `28-beta+16-ea` only. T1 and T3 must run on
   every JDK build the repository supports. The result may differ.
5. **A downcall inside the cleanup.** R13's cleanup only counted and set a flag
   (`R13_...java:68`, `:101`, `:156`). The real cleanup calls `lgj_lease_release`.
   Check that this works when the cleanup runs on the closing thread, and what
   happens to the Arena close if the release returns a non-`OK` status or if the
   cleanup throws. Proposal: the cleanup records a non-`OK` status in a counter
   and never throws.
6. **A bulk copy of the largest realistic view.** Now measured on one machine
   (R14 G2, 4 vCPU Xeon, `28-beta+16-ea`). A close blocks for up to the longest
   single in-flight access through the view. In overlap rounds the median close
   took 2.6 to 3.7 ms at 32 MiB, 6.8 to 7.0 ms at 64 MiB, 13.6 to 13.8 ms at
   128 MiB and 27.6 to 29.0 ms at 256 MiB (three runs; `R14-observed.txt`), close to
   the standalone copy times. The largest realistic view size for the Bardioc runtime
   is **still not chosen**, and no bound is claimed beyond 256 MiB. Proposal
   (reasoning, not measured): readers copy large views in bounded chunks (for
   example 8 MiB or less), so close latency stays bounded, because each chunk is a
   separate scoped access.

### 5.4 One more native test, not Java

A Rust stress test runs borrow, release and close from several threads on the same
owner in all four orders of §3.5 and checks: the entry is reclaimed exactly once;
no lease is granted on a `closing` or `reclaimed` owner; the final count is zero.
Its red arm is `lease-no-reclaim` (never reclaims) and a variant that reclaims
twice, both observed through `Weak` and the count, with no dereference.

---

## 6. Open questions

Status words below are proposals for the owner, not decisions. Reasoning, evidence
and re-evaluation triggers: §9.

| # | Question | Who decides |
|---|---|---|
| Q1 | Keep `PARENT_CLOSED` for operations on a `closing` owner (the design's text, but defined for children, `abi.md:239`), or add a status such as `RESOURCE_CLOSING`? **Status: proposal.** Under B3 there is no closing state, so no new status. If B1 is chosen: add `LGJ_ERR_RESOURCE_CLOSING = -19` (existing codes run to -18, plus -99; -19 is free) with its own Java mapping, because `PARENT_CLOSED`'s Java message says "on a mask" (`Status.java:128-131`). Compatible: an older Java maps an unknown code to `NativeCallException` "UNKNOWN_STATUS" (`Status.java:117-120`), and a minor-14 Java cannot borrow. | Repository owner (ABI statuses) |
| Q2 | Is `lgj_lease_count` a regular exported symbol or a test-only counter (the design says "exposed to tests", `:2326`)? **Status: proposal: regular production symbol** (a pinned leak must be visible in production; read-only, no lifetime risk; cost: permanent ABI surface). Under B3 the signature is `i32 lgj_lease_count(u64 resource, u64* out_live)` (§4.1). Caveat: the count is a snapshot. | Owner of the Bardioc design, with the ABI owner |
| Q3 | Choice A and Choice B in §4.4. **Status: proposal: A1 and B3**, B1 as acceptable fallback, B2 rejected as written. | Repository owner, at the implementation review |
| Q4 | Should a lease carry a debug owner tag (which result, which thread) so a pinned leak can be traced? It adds state. **Status: open, owner.** Under B3 it is the only way to attribute a pinned leak to an owner. | Implementation author, reviewed by the ABI owner |
| Q5 | Do `RowStore.lanes[]` and `Mask.words()` move to leased views, or stay unleased with the v3 probe? This decides when `ISS-LGJ-CACHED-DESCRIPTOR-CROSS-THREAD-WINDOW` can close (§1.3). **Status: open, owner.** | Repository owner |
| Q6 | The design forbids reclaiming a leased view by force (§3.4). Does the Bardioc runtime need a force path at shutdown, and if so, where does it live (outside lgj)? **Status: open, owner.** | Owner of the Bardioc design |
| Q7 | Disable arms as feature-built libraries (this proposal) or as a test-only export (v3 C1 precedent)? **Status: open, owner.** | Repository maintainers |
| Q8 | Which JDK builds must T1, T3 and R14 pass on (§5.3 item 4)? **Status: open, owner.** R14 and the source reading cover `28-beta+16-ea` only, so the ordering guarantee is scoped to that build (§3.5, "Scope of the ordering guarantee") until another build is checked. | Repository owner |
| Q9 | Does `closeScope0` on the pinned build wait for an in-flight scoped access (release after), or can the handshake "fail" (§3.5)? **Status: answered on `28-beta+16-ea`.** It waits and has no failure path (`scopedMemoryAccess.cpp:265-284` at `jdk-28+16`; R14 G1-G3). The class comment saying it can fail is stale. Close throws only when a downcall holds the view (G4). Re-open on any JDK bump: re-run R14, re-read the file. The HotSpot file is strongly corroborated as the build's, not proven (§3.5). | Implementer; re-checked on every JDK change |

---

## 7. Audit note: departures from the integration design's §6.5

The design is `LANCE_ENGINE_INTEGRATION_DESIGN.md`. This list is for correcting
it.

1. **Rule 4(a), the mechanism (`:534-537`).** The design says "a close that races
   an in-flight access fails instead of completing (to be confirmed by test,
   below)". On `28-beta+16-ea` this is wrong. Corrected mechanism text the design
   should carry, scoped to this build: *a close that races an in-flight scoped
   access waits for that access, which is made to throw `IllegalStateException`,
   and then completes; the cleanup, and so the lease release, runs after.* Close
   throws only when a downcall holds the view (the session's acquire count).
   Evidence: `scopedMemoryAccess.cpp:265-284` at `jdk-28+16` (the close waits and
   has no failure path) and R14 G1-G4. State the requirement too: the release never
   runs while an access through the view is in progress. Re-check on any JDK change
   (§9).
2. **Release-ordering test row (`:563`).** "an arena close that races an in-flight
   read does not complete, and the lease is not released by it". The first half is
   wrong on this build: the close completes, after the read has been made to throw.
   Correct the row to: the close waits for the in-flight access and the lease is
   released only after it. T3 part (c) now checks this for a bulk copy with R14's
   method; its overlap inference rests on the JDK source structure (§3.5).
3. **The untested paragraph (`:568-571`).** It says the shared-arena close
   behaviour "was not tested for this document (not established)". R13 now covers
   the single-thread behaviour (F1, F2) and the absence of successful reads after
   the release flag (F3, F4) for one JDK build. R14 now covers close against an
   in-flight bulk copy and a downcall on the same build. Update the paragraph and
   cite R13 and R14 with the one-build limit.
4. **Five versus six tests.** The J1 row (`:2226`) lists "the five tests of 6.5".
   The §6.5 table lists six (`:557-566`). This contract keeps six.
5. **Raw addresses (added).** The design is silent on native code that receives a
   raw address. §3.6 adds a prohibition and names what the Arena does not protect.
6. **"No release call that Java can make while the arena is still alive"
   (`:538-539`).** True by Java construction and a source fence, not by the ABI.
   The release symbol is callable by any code in the process. §3.5 says so.
7. **Operations on `closing` (`:527`).** The design names `PARENT_CLOSED` only for
   "new operations". §3.3 extends it to describe, borrow, `lgj_resource_info` and
   mask creation, and fixes `lgj_close` on a `closing` owner to `INVALID_HANDLE`
   (double close). The design says nothing on those. `PARENT_CLOSED` is defined for
   children (`abi.md:239`); Q1 asks whether to keep it.
8. **Lease count exposure (`:2326`, "exposed to tests").** §4.1 proposes a regular
   symbol so a leak is visible outside tests. Q2.
9. **The owner's phrase.** The owner's decision 1 reads "Do NOT rely
   exclusively on the Arena cleanup callback for native lifetime safety." The
   design paraphrases it at `:505-506` and `:510-511` and does not quote it.
   The design could quote it verbatim.
10. **Disable arms and "native pin" tests.** The design lists tests by what they
    must show. It does not say how a red arm avoids reading freed memory. §5.1 adds
    that rule, from v3 C1.

---

## 8. What this document did not verify

- Whether `lgj_lane_borrow` taking the registry write lock costs anything under
  contention. Nothing here is measured.
- The JDK's internal rule behind F3 and F4. It is now read in source
  (`scopedMemoryAccess.cpp` at `jdk-28+16`, and `src.zip` of the pinned build, §3.5)
  and observed by R14 on `28-beta+16-ea`. The HotSpot file is strongly corroborated
  as the build's, not proven. The exact HotSpot commit of the build was not
  fetched.
- That B3 works in the real `RwLock` registry. Only a one-mutex model was run
  (`valhalla-lab/lease-model/`).
- A scalar access overlapping a close. R14 covers a bulk copy and a downcall.
- Behaviour on a JDK build other than `28-beta+16-ea`.
- That a feature-built native library can be loaded by the Java test runner. The
  existing `-Dlgj.oldlibrary` route suggests it can; it was not exercised here.
- Line numbers in `epoch-recheck-v3.md` for `registry.rs` and `Engine.java`
  predate later edits. The citations above are to the files as read on this
  branch. For example v3 cites `registry.rs:331-338` for the generation bump,
  while `close` is now at `registry.rs:322-345`.

---

## 9. Decision record (2026-11-20)

For later sessions. Every recommendation here is a **proposal for the owner, not a
decision**. Each item gives the options, what was tested or read, the result, the
balanced recommendation and who must accept it, caveats, and when to re-evaluate.

### 9.1 Q9: does close fail or wait against an in-flight scoped access?

- *Options.* Close fails (the stale `ScopedMemoryAccess` class comment and the
  design's rule 4(a)); close waits and completes.
- *Done.* Read `scopedMemoryAccess.cpp` at `jdk-28+16` (lines 133-139, 140-258,
  265-284) and the pinned build's `src.zip` (`SharedSession.java:83-93`,
  `Unsafe.java:1000-1010`, `ScopedMemoryAccess.java:63`). Ran R14
  (`valhalla-lab/reproducers/R14_CloseWaitsForInFlightAccess.java`, output
  `R14-observed.txt`, three runs).
- *Result.* Close waits (G1: 240 of 240 overlap rounds, no poison, every close
  returned; G2: close duration tracks copy size; G3: the oracle can fail, 10 of 10
  red rounds). Close throws only when a downcall holds the view (G4, 5 of 5).
- *Recommendation.* Closed for `28-beta+16-ea`. Carry the corrected mechanism text
  of §7 item 1 into the design. Accepted by: the owner of the Bardioc design (text
  change); the implementer (reliance).
- *Caveats.* The HotSpot file is strongly corroborated as the build's, not proven
  (§3.5). The overlap signature uses JDK source structure. One machine, one build,
  bulk copy and one libc downcall only.
- *Re-evaluate when.* Any JDK bump; a change of vendor build; a view access path
  that is not `MemorySegment.copy` or a plain scoped read; JVMTI agents in
  production (with agents loaded the stack walk takes another path,
  `scopedMemoryAccess.cpp:58-69` and `:83-89`; not tested).

### 9.2 Choice B: what close does while a lease is live

- *Options.* B1 closing state in the slot; B2 bump at close, entry left in the
  slot; B3 close unchanged, leases hold `Arc` clones.
- *Done.* Executable model `valhalla-lab/lease-model/` (6 tests, all pass). Code
  facts in §4.4 (five lock sites; 7, 13 and 20 call sites).
- *Result.* All variants reclaim exactly once in all four orders and under a
  2000-iteration concurrent run, and make a never-closed view a visible pinned
  leak. B2 as written is wrong: a handle forged with the slot's current generation
  resolves after close with a live lease, under B2 only. B3 needs no slot state and
  no change at the 40 call sites.
- *Recommendation.* B3; B1 as fallback; B2 rejected as written (§4.4). Accepted by:
  the repository owner, at the implementation review, with the owner of the Bardioc
  design because B3 departs from the design's table (`:527`).
- *Caveats.* One-mutex model, not the real `RwLock` registry. B3 costs: no per-owner
  `closing` visibility; a closed owner's count is global only; slot-based accounting
  under-reports pinned memory.
- *Re-evaluate when.* Bardioc needs per-owner `closing` visibility (then B1); the
  registry moves off the `RwLock` or to a lock-free word (§3.7); a new registry
  lock site or resolve path appears; slot accounting becomes a memory-limit input.

### 9.3 Q1: the status for operations on a closing owner

- *Options.* Reuse `PARENT_CLOSED`; add `LGJ_ERR_RESOURCE_CLOSING = -19`; none.
- *Done.* Read `Status.java:117-131` and the status list in `abi.md` (codes to -18,
  plus -99).
- *Result.* `PARENT_CLOSED` maps to `ClosedResourceException` whose message says "on
  a mask whose parent resource is closed", which is misleading for an owner's own
  handle. An unknown code maps to `NativeCallException` "UNKNOWN_STATUS".
- *Recommendation.* Under B3 no new status (closed owner is `INVALID_HANDLE`). Under
  B1 add `-19` with its own Java mapping; an older Java sees UNKNOWN_STATUS, and a
  minor-14 Java cannot borrow. Accepted by: the repository owner (ABI statuses).
- *Re-evaluate when.* B1 is chosen; a second status is requested for the same
  state.

### 9.4 Q2: `lgj_lease_count` as a production symbol

- *Options.* Regular exported symbol; test-only counter.
- *Done.* Reasoning only, against `:2326` of the design.
- *Result.* Not measured. A pinned leak is only useful if production can see it.
- *Recommendation.* Regular symbol, read-only; under B3
  `i32 lgj_lease_count(u64 resource, u64* out_live)`: resource 0 is process-wide, a
  live owner its own count, a closed owner `INVALID_HANDLE`. Accepted by: the owner
  of the Bardioc design with the ABI owner.
- *Caveats.* Permanent ABI surface. The count is a snapshot; assert on it only after
  quiescence.
- *Re-evaluate when.* The ABI owner objects to the surface; B1 is chosen (then the
  `out_state` form of §4.1).

### 9.5 Choice A: one-step or two-step borrow

- *Options.* A1 `lgj_lane_borrow`; A2 acquire then describe.
- *Done.* The same model.
- *Result.* Under every B variant, a two-step borrow can leave a lease live while
  describe is refused, so Java needs an error-path release. A1 has no such state.
- *Recommendation.* Keep A1. Accepted by: the repository owner.
- *Re-evaluate when.* Reuse of the describe code unchanged becomes the priority.

### 9.6 Close latency

- *Options.* Accept unbounded close latency; bound it by chunked reads.
- *Done.* R14 G2.
- *Result.* A close blocks for up to the longest single in-flight access: about
  27 ms per 256 MiB copy on this machine.
- *Recommendation (reasoning, not measured).* Readers copy large views in chunks of
  8 MiB or less, so each chunk is a separate scoped access and close latency stays
  bounded. Accepted by: the owner of the Bardioc design (reader contract).
- *Caveats.* One machine. The chunk size is a proposal, not a measurement. The
  largest realistic view size is not chosen.
- *Re-evaluate when.* A view larger than 256 MiB is planned; a latency requirement
  on close is stated; the copy path changes (for example a vectorised native copy).

### 9.7 §3.6: raw addresses and downcalls

- *Options.* Handles only; allow segments in downcalls; allow raw `long`.
- *Done.* R14 G4.
- *Result.* A segment in a downcall is protected by the acquire count (close throws,
  cleanup waits). A raw `long` is not protected.
- *Recommendation.* Keep the handles-only rule. The result object's `close()` may
  throw `IllegalStateException` after a violation and must propagate it. Accepted
  by: the repository owner.
- *Re-evaluate when.* An lgj native function starts to accept an address.

### 9.8 Identity reading (cross-reference only)

The owner wrote "indexed recorder". Reading it as "indexed reorder" is corroborated
by Lance-graph's own seal design: `docs/lotus/rp-seal-v1/B1.md:234-243` ("completion
order is fixed to canonical order at WRITE time ... Any locality-driven physical
reordering must happen after the seal, as a separate compaction pass") and
`A3.md:199-201` ("binary copy is strictly file concatenation, not permutation").
"Recorder" has no matching concept in the code. Owner confirmation is still
requested; the risk is low. This is not a subject of this contract.

### 9.9 How to re-run the evidence

- R14, from `valhalla-lab/reproducers/` (commands from the file's header):
  `javac --release 28 --enable-preview -d out R14_CloseWaitsForInFlightAccess.java`
  then `java --enable-preview --enable-native-access=ALL-UNNAMED -cp out
  R14_CloseWaitsForInFlightAccess`. The recorded runs used `-Xmx2g`. Compare with
  `R14-observed.txt`.
- R13: its own header in `R13_SharedArenaCloseVersusRead.java`.
- The Choice B model: `cargo test --manifest-path valhalla-lab/lease-model/Cargo.toml`.
- The HotSpot source: `src/hotspot/share/prims/scopedMemoryAccess.cpp` at openjdk tag
  `jdk-28+16` (commit `f0747596dea3e6a1c852dc227a0eb10ddbf5b015`), lines 133-284. On
  a new JDK read the file at that JDK's tag, and compare the build's `src.zip` copies
  of `SharedSession.java` and `MemorySessionImpl.java` with the tag's.
