# method-call-v1 — calling a method on a class across the membrane

**Status: PROPOSAL. Nothing is implemented. No ABI change is made by this
document. No D-id.**

Date: 2026-11-19. Read against `lance-graph-java` `a297c62`, `lance-graph`,
OGAR `main` and Bardioc-rs as checked out beside it. Follows
`schema-bridge-v1.md`.

## 0. The question

`schema-bridge-v1` found that a Java caller needs no schema for stored
records: storage and evaluation stay below the membrane. The consumer's
owner then narrowed the remaining need: the Java side needs a class id
only for what *acts* — the moving parts of the hosted stack — and acting
means calling a method on a class.

This plan asks what such a call is made of today, on the native side, and
what shape it could take here without breaking `abi.md` §1, §6 and §10.

## 1. What a method is, natively

### 1.1 One 32-bit address

A behaviour is addressed by a classid whose two halves carry the two
parts of the call:

- the **left** 16 bits are the class it applies on (ON);
- the **right** 16 bits, in `0xF001..=0xFFFF`, are the behaviour's **arm**
  (DO). `0xF000` is the class's polyfill lens and is never assigned.

Sources: `ogar-vocab/src/arm_ledger.rs` (module doc);
`ogar-action-handler/src/dt64.rs` ("A behaviour is a method addressed as
`class :: F###`"); `lance-graph-contract/src/class_view.rs`
(`is_behaviour_lens`: right half `>= 0xF000`).

In DT64 the arm becomes the method identity, `MethodId(u32::from(arm))`,
and the behaviour classid is `compose_classid(on, arm)`. Arms come from
an append-only ledger; "there is no computation". OGAR owns
`0xF001..=0xF7FF`, consumers `0xF800..=0xFFFF`.

**So the caller's side of a method call is one `u32`.** It fits a 4-byte
flattened value record, the same kind as `Ordinal` and `LaneId`. The
signature revision (`SigRev`, `u32`) is a second one.

### 1.2 DT64 admits; it does not run

`BindStore::method_ref(class, method)` issues a `MethodRef` (class,
`MethodId`, `SigRev`, guard witness, issuer `Stamp`).
`BindStore::admit(&MethodRef)` checks identity, revision, guard, binding
digest and issuer, and returns `&MethodBinding`.

Nothing in that crate executes. Its own words: "The store keeps it; it
evaluates nothing"; "no evaluator, no persistence path"; "the store never
resolves or runs the word". A `MethodBinding` carries ON × WHEN × DO as
data: an optional guard, the fields it reads, and an ordered list of
`Effect`s, each a target field position, a polyfill word (`AlgebraRef`)
and its arguments.

### 1.3 Two things execute today, and they are different kinds

| | R2IL reading | Capability executor |
|---|---|---|
| Where | `lance-graph-r2il/src/binding.rs` | `ogar-from-schema/src/action_ws.rs`, trait `CapabilityExecutor` |
| Call | `prepare(...) -> Prepared` once, cold; `run(&Prepared, ..., &ExecPermit, FieldLanes, ...) -> Receipt` hot | `execute(capability: &str, bound: &[(String, String)]) -> Result<Vec<(String, String)>, String>` |
| Works on | field lanes, through mask-risc folds | one request; SSH, REST or a native command |
| Scale | a population of rows | one invocation |
| Carries | lanes and masks | strings |
| Own status | "This is not the path every execution takes"; `ExecPermit` is "a lab permission, not a production authorization: who issues it is not decided" | in use by the HIRO-compatible handler |

They answer different questions. The first is "apply this method's
effects to these rows". The second is "run this named capability with
these parameters, somewhere outside".

## 2. Against the membrane's rules

- **§6, bulk-only.** A method applied to a mask of rows does work
  proportional to `n_rows`. A capability invocation does not: it is one
  request, and it is not lifecycle either.
- **§10, no strings, no upcalls.** The R2IL path needs neither. The
  capability executor's whole interface is strings.
- **Mutation.** `CLAUDE.md`: "Mutation crosses as verbs, not writable
  memory". A method's effects write field positions. A method run is a
  verb, so the rule admits it in principle; but every lane this repo
  serves today is read-only except a mask and the Import exception, and
  stores are generated from a seed. There is nothing to write to.
- **Dependencies.** Two different rules apply, and neither settles it.
  The G11 fence limits which `lance_graph_contract` *modules* `lgj-abi`
  may import (`canonical_node`, `class_view`, `facet`, `ontology`); it
  says nothing about other crates. The crate-level rule is `CLAUDE.md`
  ("the full lance-graph ENGINE is never a dependency — blast-radius
  containment is the point of the contract split"). Today `lgj-abi`
  depends on `ndarray`, `lance-graph-contract` and
  `lance-graph-mask-risc`, plus `ogar-class-view` behind an optional
  feature. `lance-graph-dt64` and `lance-graph-r2il` are not among them.
  Whether they count as "the engine" is the owner's reading, not this
  plan's. `lance-graph-r2il` also pulls `ogar-loco` and `ogar-r2il` from
  OGAR by git.

So only the first kind of execution has a membrane-shaped form. The
second kind is not a membrane concern at all.

## 3. Options

### A. Change nothing

A Java host that has moving parts keeps them as its own actors and
addresses each by its behaviour classid, held as a plain `int` on the
Java side of the API. Where an actor runs an external capability (the
HIRO handler case), that already exists as a Rust service
(`bardioc-action-ws` over OGAR's session and `CapabilityExecutor`) and
does not pass through this membrane.

- Fits every rule; no ABI change.
- Nothing native checks that the classid the host holds names a bound
  method.

### B. A vocabulary record only

Add `BehaviourId(int)` (and `SigRev(int)`) to the facade, with no call
that takes them.

- **Rejected**, by the reasoning of `schema-bridge-v1` §6 question 5: a
  record with no call is a word with no meaning. It would also be the
  first facade type whose validity nothing can test.

### C. Prepare once, run over a mask

Two symbols, in the lifecycle of §1.3's left column:

```
lgj_method_prepare(store, u32 behaviour_classid, u32 sig_rev,
                   u32 schema_rev, out *u64 handle)   // lifecycle
lgj_method_run(handle, u64 src_mask, out *LgjReceipt) // O(rows)
```

- `prepare` does the resolution, admission and opcode binding natively
  and returns a generation-checked handle (`abi.md` §4). The `MethodRef`,
  its `Stamp` and the prepared plan never leave native memory.
- `run` applies the method to the rows of `src_mask` in one crossing. No
  per-row call, no string, no upcall. A refusal is a status.
- Java spelling: `store.method(BehaviourId).prepare()` returning a
  closeable, then `.run(mask)`.

This is the only shape found that satisfies §1, §6 and §10 together. It
is **blocked on five things, none of them this repo's to decide**:

1. the dependency rule: dt64 and r2il are not dependencies of `lgj-abi`,
   and `CLAUDE.md` keeps the lance-graph engine out;
2. `lance-graph-r2il` describes itself as an optional reading, not the
   execution path;
3. who issues an `ExecPermit` "is not decided";
4. there is no writable field lane here for an effect to write;
5. `ExecRefusal`, `Refusal` and `GuardRefusal` add to the refusal
   vocabulary that `schema-bridge-v1` §3.D already could not fit into 19
   error codes.

Under the STOP rule each lands upstream first.

### D. Capability invocation through the membrane

`execute(capability, params)` as an export.

- **Rejected.** Strings in and out, one call per request, and the work
  happens outside the process. It is none of resource, lane, view, mask,
  operation, descriptor, status, epoch (§1).

## 4. Recommendation

**A now. C is the recorded shape for a method run, to be raised upstream
as a question. D stays out.**

The split this gives a consumer:

| The host wants to | It uses | Class id on the Java side |
|---|---|---|
| read, filter, traverse stored records | the existing surface | none for the host's own internals |
| apply a bound method to a set of rows | option C, when it exists | one behaviour classid per method |
| run an external capability (SSH, REST, command) | its own actor, or the Rust handler service | one behaviour classid per capability, as an address only |

In every row the Java side holds at most a 32-bit address. It never holds
a method body, a guard, a binding or a permit.

## 5. Open questions for the owners

1. (DT64 / R2IL) Is R2IL's `prepare`/`run` meant to become the way a
   foreign runtime runs a method, or does a consumer "execute its own
   prepared native plan" and R2IL stay the inspectable reading?
2. (DT64) Who issues an execution permit in production?
3. (this repo) Is a method run wanted behind this membrane at all, given
   the rule that the engine is never a dependency?
4. (this repo) If yes: what does an effect write to? A writable field
   lane is a larger change than the two symbols.
5. (OGAR) Does a hosted foreign stack take arms from the consumer range
   `0xF800..=0xFFFF` of the existing ledger, or from a ledger of its own?
6. (consumer) Which parts of the JanusGraph stack are moving parts in
   this sense? None has been identified.

## 6. What this plan does not claim

- Nothing was measured.
- `prepare` and `run` were read to their signatures and module docs; the
  bodies were not traced.
- `lance-graph-mailbox-lanes/src/act.rs`, which composes ON, WHEN and DO
  for a mailbox publication, was read to its module doc only.
- Two different `ActionDef` types exist (`lance-graph-contract` and
  `ogar-vocab`); this plan does not say which one a membrane call would
  be defined against.
- OGAR's `docs/SHARED-DO-DESIGN.md` says Bardioc's ledger registration is
  "Not built"; the code in `bardioc-do-arm/src/arms.rs` has it. The doc
  looks stale; not confirmed with its owner.
