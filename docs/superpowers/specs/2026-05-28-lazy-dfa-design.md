# Lazy DFA (R1 + R2) — Design Spec

**Date:** 2026-05-28  
**Branch:** fix/anchor-semantics (spike off this; implementation goes on a new branch)  
**Source:** `doc/plans/glob-perf-nfa-improvements.md` recommendations R1 and R2

---

## Problem

`OPTIMIZED_NFA` patterns recompute `closure(stateSet, c)` for every input character.
For patterns with ≥300 NFA states where eager subset construction is too expensive,
matching cost scales with NFA state count on every character, every call.

R1 + R2 add a lazily-materialized DFA cache over the NFA execution:

- **R1** — Intern NFA state-sets to DFA state IDs on first encounter; cap at 4 096 states; freeze and fall back to plain NFA stepping when full.
- **R2** — Each cached DFA state gets a `int[128]` ASCII transition table indexed by `c & 0x7F`. Warm-path cost is one array read per character.

---

## Scope

Patterns that qualify for `LAZY_DFA` strategy:

- NFA state count ≥ 300 (current `OPTIMIZED_NFA` fallback threshold)
- No lookaround assertions (`(?=...)`, `(?!...)`, `(?<=...)`, `(?<!...)`)
- No backreferences (`\1`, `\k<name>`)

All other patterns remain on their current strategy unchanged.

---

## Architecture

```
compile-time                            runtime
──────────────────────────────────      ───────────────────────────────────────
PatternAnalyzer                         LazyDFACache  (reggie-runtime)
  NFA≥300, no lookaround/backref   →    ├─ state-set interning map
  routes to LAZY_DFA strategy           │    ConcurrentHashMap<StateSetKey, Integer>
                                        ├─ per-DFA-state ASCII tables
LazyDFABytecodeGenerator                │    Object[]  asciiTables
  emits:                                │    asciiTables[id] = int[128] or null
  ① static final LazyDFACache CACHE    ├─ cap = 4096; volatile boolean frozen
  ② int[] nfaStep(int[] states, int c) └─ int[][] nfaStateSets (backing NFA state-sets)
  ③ boolean matches(String input)
       → CACHE.matches(input, this::nfaStep, ACCEPT_STATE_IDS)
```

The generated class owns NFA mechanics only (steps and accept-set checking).
`LazyDFACache` owns caching policy only (interning, cap, ASCII tables).
Neither layer knows the other's internals beyond the `NfaStep` functional interface.

Note on state-set representation: ≥300-state patterns use `SparseSet`/`int[]` internally
(not a single `long`), so the interface uses `int[]` snapshots (sorted arrays of active NFA
state IDs) rather than bitset longs. The `nfaStep` method allocates a new `int[]` per call,
but this only occurs on cache misses — the warm path never calls it.

---

## Components

### `LazyDFACache` — new class, `reggie-runtime`

```
Functional interface (reggie-runtime)
  @FunctionalInterface interface NfaStep {
    int[] apply(int[] currentStates, int c);
  }

Fields
  ConcurrentHashMap<StateSetKey, Integer>  stateIndex
  Object[]    asciiTables         // asciiTables[dfaStateId] = int[128]; null until first write
  int[][]     nfaStateSets        // nfaStateSets[dfaStateId] = sorted int[] of active NFA state IDs
  boolean[]   accepting           // accepting[dfaStateId] = true iff this DFA state is a final state
  AtomicInteger nextId            // next DFA state ID to assign
  volatile boolean frozen         // set true when nextId >= CAP
  int[]       acceptStateIds      // NFA state IDs that are accept states (from generated class)

Constants
  int CAP      = 4096
  int UNCACHED = -1
  int DEAD     = -2
  int FALLBACK = -3   // frozen and no cached state: switch to NFA mode for remainder

Key method
  boolean matches(String input, NfaStep nfaStep)
    dfaState = 0
    for pos = 0 to input.length()-1:
      char c = input.charAt(pos)
      asciiTable = asciiTables[dfaState]
      int next = (asciiTable != null && c < 128) ? asciiTable[c] : UNCACHED
      if next == UNCACHED:
        next = lookupOrCompute(dfaState, c, nfaStep)
      if next == DEAD:     return false
      if next == FALLBACK: return nfaFallbackMatch(input, pos, nfaStateSets[dfaState], nfaStep)
      dfaState = next
    return accepting[dfaState]

  private int lookupOrCompute(int state, int c, NfaStep nfaStep)
    int[] nextNFASet = nfaStep.apply(nfaStateSets[state], c)
    if nextNFASet.length == 0: return DEAD
    key = new StateSetKey(nextNFASet)
    Integer id = stateIndex.get(key)
    if id == null && !frozen:
      id = stateIndex.computeIfAbsent(key, k -> nextId.getAndIncrement())
      if id >= CAP: frozen = true
      else:
        nfaStateSets[id] = nextNFASet
        accepting[id]    = containsAny(nextNFASet, acceptStateIds)
    if id == null: return FALLBACK
    // populate ASCII table entry (idempotent — same key always maps to same id)
    int[] table = (int[]) asciiTables[state]
    if table == null:
      table = new int[128]
      Arrays.fill(table, UNCACHED)
      asciiTables[state] = table   // plain write; idempotent race is safe
    if c < 128: table[c] = id
    return id

  private boolean nfaFallbackMatch(String input, int fromPos, int[] nfaStateSet, NfaStep nfaStep)
    // Called once the cache is frozen and a new transition was encountered.
    // Runs pure NFA stepping (allocating per step) for the remainder of the input.
    int[] states = nfaStep.apply(nfaStateSet, input.charAt(fromPos))
    for pos = fromPos + 1 to input.length()-1:
      if states.length == 0: return false
      states = nfaStep.apply(states, input.charAt(pos))
    return containsAny(states, acceptStateIds)
```

`StateSetKey` wraps a sorted `int[]` of active NFA state IDs and implements `equals`/`hashCode`
based on array contents. An alternative encoding (glob_perf's String-packing trick: each state
ID packed as a `char` in a `String`) may be chosen during implementation for a faster hash.

### `LazyDFABytecodeGenerator` — new class, `reggie-codegen`

Parallels `NFABytecodeGenerator`. Emits three artifacts into the generated class:

1. `private static final LazyDFACache CACHE` — one instance per compiled pattern class,
   initialised with: the ε-closure of the NFA start state (as a sorted int[]), and
   the accept state IDs (as a sorted int[]).

2. `int[] nfaStep(int[] states, int c)` — given a sorted int[] of active NFA state IDs and
   a character, returns a new sorted int[] of the next NFA state IDs (including ε-closure).
   Same transition logic the NFA generator currently inlines into `matches()`, extracted as
   a package-private method callable by `LazyDFACache` on cache misses.
   Allocates on each call; this is acceptable because it is only called on cache misses.

3. `public boolean matches(String input)` — delegates:
   ```java
   return CACHE.matches(input, this::nfaStep);
   ```

### `PatternAnalyzer` — addendum to routing logic

After the existing DFA/NFA routing decision:

```java
if (strategy == Strategy.OPTIMIZED_NFA
    && nfa.getStateCount() >= 300
    && !nfa.hasLookaround()
    && !nfa.hasBackreferences()) {
    strategy = Strategy.LAZY_DFA;
}
```

This check is placed **after** all existing DFA threshold and anchor-dilution checks, so
those patterns are unaffected.

---

## Data Flow

### Cold path (first encounter of a state × char pair)

```
matches("abc...")
  dfaState = 0              // start state; nfaStateSets[0] = ε-closure of NFA start (int[])
  c = 'a'
  asciiTables[0] == null    // not yet allocated
  → lookupOrCompute(0, 'a', nfaStep)
      nextNFASet = nfaStep.apply(nfaStateSets[0], 'a')    // int[] allocation on miss
      stateIndex.computeIfAbsent(key, ...) → id = 1
      nfaStateSets[1] = nextNFASet
      accepting[1] = containsAny(nextNFASet, acceptStateIds)
      asciiTables[0] = new int[128]; asciiTables[0]['a'] = 1
  dfaState = 1
  c = 'b'
  asciiTables[1] == null    // new state, no table yet
  → lookupOrCompute(1, 'b', nfaStep)  → id = 2
  ...
```

### Warm path (all transitions cached)

```
  c = 'a'
  asciiTables[dfaState]['a'] → 3     // single int[128] read, zero allocation
  dfaState = 3
```

### Frozen path (cap reached, FALLBACK sentinel triggers NFA mode)

```
  dfaState = 2048, c = '?'
  asciiTables[2048] == null, frozen == true
  next = lookupOrCompute(2048, '?', nfaStep)
    nextNFASet = nfaStep.apply(nfaStateSets[2048], '?')
    stateIndex.get(key) → null, frozen → return FALLBACK
  next == FALLBACK
  → nfaFallbackMatch(input, pos, nfaStateSets[2048], nfaStep)
       runs pure NFA stepping for remainder of input (correct but allocating)
```

---

## Thread-Safety

- `stateIndex`: `ConcurrentHashMap` — safe concurrent interning via `computeIfAbsent`.
- `asciiTables[id][c]`: written once per slot; the same (state, char) always produces the
  same target, so a lost-write race produces a correct result. No lock needed. A plain
  `Object[]` write is sufficient (JMM guarantees eventual visibility; stale reads just
  trigger a redundant recompute that writes the same value).
- `frozen`: `volatile boolean` — guarantees all threads see the freeze once set.
- `nextId`: `AtomicInteger` — safe increments under concurrent interning.

`ReggieMatcher` instances are single-threaded per instance (confirmed from code inspection);
`CACHE` is shared but the above invariants make it safe without instance-level locking.

---

## TDD Test Plan

### Layer 1 — `LazyDFACache` (write tests first)

File: `reggie-runtime/src/test/java/.../LazyDFACacheTest.java`

| Test | What it verifies |
|------|-----------------|
| `testCacheMissInternsNewState` | First call on (startState, char) allocates DFA state ID 1 |
| `testCacheHitUsesAsciiTable` | Second call to same (state, char) reads int[128], no map lookup |
| `testDeadStateEarlyExit` | Non-matching NFA step (returns 0L) maps to DEAD, match returns false |
| `testFreezeAtCap` | After 4096 interned states, `frozen=true`, `FALLBACK` returned for new transitions |
| `testFallbackMatchCorrect` | After freeze, `nfaFallbackMatch` produces same accept/reject decision as unfrozen NFA |
| `testConcurrentInterning` | Two threads racing on same cache key get consistent, deduplicated IDs |
| `testAcceptStateRecognition` | State whose NFA set intersects acceptMask is recognised as accepting |
| `testNonAsciiCharFallsBackToNfaStep` | `c ≥ 128` always takes the slow path (no int[128] read out of bounds) |

### Layer 2 — `LazyDFABytecodeGenerator` (write tests first)

File: `reggie-codegen/src/test/java/.../LazyDFABytecodeGeneratorTest.java`

| Test | What it verifies |
|------|-----------------|
| `testGeneratedClassMatchesNFAForSameInputs` | LAZY_DFA and OPTIMIZED_NFA produce identical results on 500 random strings |
| `testNfaStepMethodPresent` | Generated class has `nfaStep(long, int)` via reflection |
| `testCacheIsSharedAcrossInstances` | Two instances of same generated class share the same `CACHE` reference |
| `testCacheIsNotSharedAcrossPatterns` | Two different patterns have different `CACHE` objects |

### Layer 3 — `PatternAnalyzer` routing (write tests first)

File: `reggie-codegen/src/test/java/.../PatternAnalyzerLazyDFATest.java`

| Test | What it verifies |
|------|-----------------|
| `testRouteToLazyDFAWhenNFALarge` | Pattern with ≥300 NFA states, no lookaround → `LAZY_DFA` |
| `testDoNotRouteWithLookahead` | Same pattern + `(?=...)` → `OPTIMIZED_NFA` |
| `testDoNotRouteWithBackref` | Same pattern + `\1` → `OPTIMIZED_NFA` |
| `testDoNotRouteWhenNFASmall` | 250-state NFA → `OPTIMIZED_NFA` |

### Layer 4 — Benchmarks (JMH, `reggie-benchmark`)

New class: `LazyDFABenchmark.java`

| Benchmark | Input corpus | Measures |
|-----------|-------------|----------|
| `hitPath` | Repeated short inputs from a fixed set (all DFA transitions cached after first pass) | Warm-path throughput: `int[128]` read per char |
| `missPath` | Fresh random strings each iteration (DFA cache always cold) | Cold-path cost: `nfaStep` + interning overhead |
| `frozenPath` | Fill cache to 4096, then repeat large-corpus matching | Freeze+fallback performance |

All three variants report explicit `_hit` / `_miss` / `_frozen` suffixes (per R7 methodology).
Baseline: same patterns run through `NFAFallbackBenchmark` for apples-to-apples comparison.

---

## Watch-outs

- **Cap must be enforced.** glob_perf documents a 300× hit-path regression when the DFA
  cache is unbounded at 65k patterns. The 4096 cap is non-negotiable.
- **NFA state count for ≥300-state patterns.** These patterns use `SparseSet` / `int[]`
  state-set representation, not a single `long`. `StateSetKey` must handle both.
- **`nfaStep` returns a new `int[]` on every call.** This allocation is acceptable because
  it only occurs on cache misses (cold path). Once the cache is warm, `nfaStep` is never
  called. The `FALLBACK` path (post-freeze) is similarly allocation-heavy but is a
  degraded-mode last resort.
- **`this::nfaStep` lambda allocation on each `matches()` call.** If profiling shows this
  matters, store the `NfaStep` instance as a `final` field on the matcher instance.
- **Do not port trie/multi-pattern machinery from glob_perf.** reggie is single-pattern
  compile-to-bytecode; only the cache/table micro-optimizations apply.
