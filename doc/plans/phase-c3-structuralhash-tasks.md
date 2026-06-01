# Phase C3 — StructuralHash HARD RULE

**Goal:** Discharge the HARD RULE attached to every change that touches capture emission:
*if C2 introduced a new bytecode-affecting signal into the emitted DFA, that signal MUST be folded
into `StructuralHash.computeDFATopologyHash`, and a `StructuralHashTest` case must lock it in.*

C3 is **mandatory analysis + conditional change**. The analysis below was performed against the
current tree (commit `f926402`) and is evidence-backed. Its conclusion drives the task list.

---

## 0. Mandatory analysis — what did C2 change in the *emitted* DFA?

C2 changed two construction-time methods in `SubsetConstructor`:

- `computeGroupActions(Set, Set)` — produces each DFA state's `groupActions`.
- `computeTagOperations(...)` / `trackEpsilonPathTags(...)` — produces each transition's `tagOps`.

Each emitted entry is a `DFA.GroupAction(groupId, type, priority)` or
`DFA.TagOperation(tagId, groupId, type, priority)` (`DFA.java:113-206`). The fields it can change:

| Field on emitted entry | Did C2 change it? | How |
|---|---|---|
| `groupId` / `tagId` | no | fixed by the dedup key |
| `type` (ENTER/EXIT, START/END) | no | fixed by the dedup key |
| **list membership** | **yes** | C2.4 filter drops entries from threads that lost the acceptance race |
| **list order** | **yes** | result now sorted by ε-priority *rank* (was: sorted by `priority` = NFA-state id — `7a9d5ba` lines 526, 805) |
| `priority` field | yes (value only) | tiebreak winner changed from lowest-NFA-id to highest-priority thread |

### 0.1 Is `priority` bytecode-affecting? — NO (evidence)

`grep -rn "\.priority"` over `reggie-codegen/src/main/java` returns **zero reads** outside
`DFA.java`'s own field declaration. Both code generators read only `type`, `groupId`, `tagId`:

- `DFAUnrolledBytecodeGenerator.java:1422-1431` (start-state group actions),
  `:1604-1610` (transition tag ops), `:3144-3155` (per-state group actions).
- `DFASwitchBytecodeGenerator.java:2499-2509` (per-state group actions).

`priority` participates in `GroupAction`/`TagOperation` `equals`/`hashCode` (`DFA.java:129-141`,
`178-192`), but nothing in main builds a `Set`/`Map` of these objects nor runs DFA minimization /
list-equality merging (no `minimiz*`, no `tagOps.equals`, no `Set<TagOperation>` in main). So the
`priority` field is **inert in the emitted DFA** — it is a construction-time tiebreak artifact only.

→ `priority` is correctly **excluded** from the hash. Adding it would only fragment the cache
(structurally-equivalent DFAs that differ in a dead int would miss the cache). **No change to the
hash for `priority`.**

### 0.2 Are membership and order already hashed? — YES

`computeDFATopologyHash` (`StructuralHash.java:138-168`) folds, **in list order**:

- per state: `for (ga : state.groupActions) { hash … ga.groupId; hash … ga.type.ordinal(); }`
- per transition: `for (tagOp : tx.tagOps) { hash … tagOp.tagId; hash … tagOp.type.ordinal(); }`

Because the fold is sequential into a running `31*h + x` accumulator, it is sensitive to **both**:
- **membership** — an added/removed entry changes the term count and value (covers C2.4).
- **order** — reordering `(tagId,type)` pairs changes the result (covers the rank-sort).

→ Both bytecode-affecting effects of C2 are **already captured** by the existing hash.

### 0.3 Conclusion

**The conditional change is NOT triggered.** No field is added to `DFA.java`, and
`StructuralHash.java` is left unchanged. The remaining HARD-RULE obligation is the **regression
guard**: land `StructuralHashTest` cases that fail if the membership/order sensitivity were ever
removed (and one that pins `priority` as deliberately excluded).

> If review disputes 0.1/0.2, the fallback is to fold a normalized signal into
> `computeDFATopologyHash` — but the evidence above says do not.

---

## 1. Test harness decision

`StructuralHashTest` lives in `com.datadoghq.reggie.codegen.analysis`. Two ways to exercise the
topology hash:

- **A — black-box via real patterns** (`hashFor(pattern)`): rejected. Cannot isolate the tagOp/
  groupAction list from state count, charsets, NFA `contentHashCode`, etc. across two real patterns.
- **B — white-box via hand-built DFAs through the public API**: **chosen.**
  `PatternAnalyzer.MatchingStrategyResult(MatchingStrategy, DFA)` is a public constructor
  (`PatternAnalyzer.java:2257`) and `StructuralHash.compute(result, nfa, false)` is public. Build
  **one** throwaway NFA (e.g. `ThompsonBuilder().build(parse("(a)b"), 1)`) and reuse it for both
  variants so every NFA-derived term cancels; swap only `result.dfa`. The sole varying term is
  `computeDFATopologyHash(dfa)`.

→ **No production-code change. No visibility change.** Only `StructuralHashTest.java` is touched.

DFA build primitives (all public, `DFA.java`): `new DFA.DFAState(id, nfaStates, accepting)`,
`state.addTransition(charSet, target, List<TagOperation>)`, `new DFA(start, acceptSet, allStates)`.
Use an empty `nfaStates` set (`Collections.emptySet()`) — the hash never reads it.

---

## 2. Tasks

### C3.1 — Record the analysis (this document)
Already done above. Acceptance: doc committed; reviewer can follow the evidence to 0.3.

### C3.2 — RED: write the guard tests in `StructuralHashTest.java` (TDD)
Add a new section `// ── TagOperation membership / order (Phase C2 guard) ──`. New cases:

1. `tagOpMembership_changesHash` — two DFAs identical except one transition's `tagOps` is
   `[START(tagId=2)]` vs `[START(2), END(3)]`. Assert **distinct** hashes. *(Guards C2.4 membership.)*
2. `tagOpOrder_changesHash` — same membership `{START(2), END(3)}` but reversed list order.
   Assert **distinct** hashes. *(Guards the rank-sort ordering signal.)*
3. `tagOpPriorityOnly_sameHash` — two DFAs whose transition `tagOps` differ **only** in the
   `priority` field of an otherwise-identical `(tagId,type)` entry. Assert **equal** hashes.
   *(Pins the deliberate exclusion from 0.1; documents that "priority selection" reaches the hash
   only via membership/order, never the raw int.)*
4. *(optional, symmetric)* `groupActionMembership_changesHash` — same idea on `state.groupActions`.

**TDD discipline (watch-it-fail for guard tests):** these tests PASS against the current correct
hash, so prove they bite by temporarily mutating `computeDFATopologyHash` and confirming each goes
RED, then revert the mutation before committing:
- test 1: comment out the `tagOp.tagId`/`tagOp.type` fold → must fail.
- test 2: replace the ordered fold with an order-insensitive accumulator (e.g. `hash += …`) → must fail.
- test 3: add `hash … tagOp.priority` to the fold → must fail (proves it currently ignores priority).

Acceptance: each test fails under its targeted mutation for the expected reason; reverts cleanly.

### C3.3 — GREEN + suite
Run `StructuralHashTest` (all green, including the 9 existing cases) and the full
`reggie-codegen` test suite. Acceptance: `BUILD SUCCESSFUL`, 0 failures.

### C3.4 — spotless + close
`./gradlew spotlessApply` (mandatory pre-commit/pre-push). Update task #39 → completed. **Stop and
wait for input** (do not roll into C4).

---

## 3. Files in scope (strict)

- **edit:** `reggie-codegen/src/test/java/com/datadoghq/reggie/codegen/analysis/StructuralHashTest.java`
- **edit:** `doc/plans/phase-c3-structuralhash-tasks.md` (this file)
- **no change:** `StructuralHash.java`, `DFA.java`, `SubsetConstructor.java` (per 0.3)

If C3.2 surfaces evidence contradicting 0.1/0.2 (a real read of `priority`, or a membership/order
collision under the current hash), **stop and propose** the hash change before editing production
code — do not silently widen scope.

---

## 4. Risks

- **False "no-op" conclusion.** Mitigated by C3.2's mutation step: if the hash did *not* already
  cover membership/order, the mutation tests would not flip RED→GREEN as expected, exposing a gap.
- **Hand-built DFA drifts from real construction.** Acceptable — these are hash-property guards, not
  end-to-end span tests; PikeVM/JDK oracle parity is covered by C6 gates.
- **Reviewer wants a real-pattern distinctness case too.** Optional add-on; black-box pairs cannot
  isolate the list, so keep white-box as the authoritative guard.
