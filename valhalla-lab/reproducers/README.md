# Reproducers — Valhalla limitations hit while expressing the ideal API

Three limitations were hit. **None of them changed the API.** Where the ideal shape could not be
expressed, that fact is recorded here and the production types stayed as they are — distorting a
public API to fit a preview VM's current budget would bake a temporary constraint into a permanent
surface.

Each reproducer is a single self-contained file with its command line in the header comment, and a
`*-observed.txt` holding the exact output that file produced on this box.

| # | Limitation | Belongs to |
|---|---|---|
| [R1](#r1) | `@NullRestricted` field in an ordinary class fails at class load | **javac** |
| [R2](#r2) | Array flattening stops at an 8-byte payload | **HotSpot / Valhalla** |
| [R3](#r3) | The densest layout has no supported spelling, and generics discard it | **Valhalla (language + libraries)** |

Environment for every observation below: `openjdk 27-jep401ea3+1-1`, Linux x86-64,
Intel Xeon @ 2.10 GHz (4 vCPU, AVX-512).

---

## R1 — `@NullRestricted` on a field of an identity class {#r1}

**File:** `R1_NullRestrictedFieldInIdentityClass.java` · **Observed:** `R1-observed.txt`

**Desired semantics.** An ordinary class holds a value-typed field flat — the field is never
null, so no reference and no header should be needed:

```java
final class Descriptor {                 // ordinary identity class
    @NullRestricted final LaneId lane;   // want: 4 bytes inline
    Descriptor(int i) { this.lane = new LaneId(i); }
}
```

**Ordinary Java (JDK 26).** Compiles and runs; the field is a reference. The annotation does not
exist, so the question cannot even be asked.

**Valhalla (JDK 27 EA).** Compiles, then fails at class load:

```
java.lang.VerifyError: All strict final fields must be initialized before super():
  1 field(s), lane:LR1_...$LaneId; in R1_...$Descriptor
```

**Why.** A null-restricted field is a *strict* field: the VM requires it to be assigned before the
`super()` call. javac emits field initialisers *after* `super()`, and this build has no `@Strict`
annotation for javac to key on — `jdk.internal.vm.annotation` here contains `NullRestricted` and
`LooselyConsistentValue` but no `Strict`. There is no Java source form that expresses the required
order, so the combination is unreachable from source.

**Workaround, and its cost.** Make the container itself a `value class`; its fields are then
implicitly strict and it works. That is what `src/valhalla/.../Containers.java` does. The cost is
that the workaround is not always available: a container that legitimately has identity — anything
mutable, anything used as a lock, anything with lifecycle — cannot become a value class, and
therefore cannot hold a flat field at all on this build.

**Consequence for this project.** None yet, and that is luck rather than design: the descriptor
types (`Field` and friends) happen to be immutable. Had `NativePattern` — which is genuinely an
identity object, it owns a native resource and closes it — wanted a flat `LaneId` field, there
would be no way to write it.

---

## R2 — array flattening stops at an 8-byte payload {#r2}

**File:** `R2_FlatteningCliff.java` · **Observed:** `R2-observed.txt`

**Desired semantics.** An array of value objects is a dense block of their payloads, whatever the
payload is — that is the entire promise that makes "values are just data" attractive.

**Observed** (`ValueClass.isFlatArray`, the VM answering about its own array):

```
type   payload  NR-nonAtomic     NR-atomic        nullable-atomic
P4         4 B  true             true             true
P8i        8 B  true             true             false
P8l        8 B  true             true             false
P12       12 B  false            false            false
P16       16 B  false            false            false
P16l      16 B  false            false            false
```

The cliff is at 8 bytes and it is total: past it, **no** array flavour flattens. Confirmed
independently by `-XX:+PrintFlatArrayLayout`, which logs a layout only for the shapes above the
line (`element size 4`, `element size 8`) and nothing for the others.

**Why.** Flattening past a machine word needs either an atomic wide store or a decision to give
atomicity up; the current implementation declines both above 8 bytes. `FlatArrayElementMaxOops`
exists as a knob for reference-bearing payloads; there is no product knob that lifts the
primitive-payload ceiling on this build.

**Consequence for this project — and it is the interesting one.** The line the VM draws is exactly
the line the thesis draws:

| Type | Payload | Flat? | Which side of the thesis |
|---|---|---|---|
| `LaneId`, `Ordinal` | 4 B | **yes** | tiny descriptor vocabulary — Valhalla helps |
| `MaskId` | 8 B | **yes** | tiny descriptor vocabulary — Valhalla helps |
| `RowRange` | 16 B | no | descriptor, but already too wide |
| `Row` (id + class + value) | 16 B | no | per-entity materialisation — Valhalla does not help |

So "Valhalla helps the descriptors, not the entities" is not a hand-wave about object headers. On
this build it is a hard cutoff in the VM, and a realistic entity is on the wrong side of it by
construction: an id plus one field already exceeds the budget.

`RowRange` landing on the wrong side is worth stating plainly, because it is the one place the
expectation was too optimistic — it is a descriptor, it was expected to flatten, and it does not.

---

## R3 — the densest layout has no supported spelling {#r3}

**File:** `R3_NoSupportedFlatSurface.java` · **Observed:** `R3-observed.txt`,
`R3-bang-syntax-observed.txt`

**Desired semantics.** `LaneId![] lanes = new LaneId![n];` — an array of non-null values, densely
packed, spelled in ordinary Java.

**Observed:**

```
(1) new LaneId[8]                     flat=true  accepts null=true  (nullable-flat: pays for a null marker)
(2) LaneId![]                         DOES NOT PARSE — no null-restricted type syntax
(3) ValueClass.newNullRestricted...   flat=true  accepts null=false  (jdk.internal, needs --add-exports)
(4) List<LaneId>.toArray()            flat=false — generics erase to Object[]
```

and the syntax probe:

```
error: not a statement
    L![] x = new L![2];
    ^
```

Three separate gaps, and the first is the one most likely to be misread:

1. **Supported source already flattens — partially.** `new LaneId[8]` *is* flat for a 4-byte
   payload. It is *nullable*-flat, so it carries a null marker and is not the densest encoding, and
   by R2 it stops being flat at all past 8 bytes. Reporting "plain arrays are not flat" would have
   been wrong; the measured claim is narrower and more useful.
2. **The densest form is `jdk.internal`.** `ValueClass.newNullRestrictedNonAtomicArray` needs
   `--add-exports java.base/jdk.internal.value=ALL-UNNAMED` and its own javadoc says it "should
   only be used by internal JDK classes for experimental purposes". A library cannot ship it.
3. **Generics erase, so the boundary undoes it.** `List<LaneId>` is `Object[]` underneath and the
   array is not flat. Any collection, stream, or generic cache reverts everything the previous two
   points achieved. Specialised generics are the missing piece and are not in this build.

**Consequence for this project.** The production API keeps `LaneId` and friends as plain `record`s
and does **not** adopt any of this. The migration path stays a one-word source change (`record` →
`value record`) precisely because nothing was bent to accommodate the current preview: no
`jdk.internal` dependency, no `--add-exports` in the shipped build, no API that hands out arrays of
descriptors.

It also removes a temptation worth naming: if `List<LaneId>` had flattened, "just hand the caller a
`List<Row>`" would look like a viable alternative to the native lane. It does not flatten, so the
bulk path is not competing with a hypothetical fast object path — it is competing with the same
boxed one Java has always had.
