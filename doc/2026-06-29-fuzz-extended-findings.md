# Fuzz Extended Findings — 50k Patterns, Depth 3

**Date:** 2026-06-29
**Sweep:** `BASE_SEED`, 50k patterns × 16 inputs × depth 3
**Baseline (25k):** 0 divergences
**Extended (25k–50k):** 43 raw findings → 22 unique minimal repros
**Gate method:** `divergenceGate_extended` (skip=25000, budget=43)

---

## Summary

The standard `divergenceGate` sweep (25k patterns) reaches 0 after the B3a–B6 fixes. Doubling
to 50k surfaces 43 findings in 22 unique patterns. None are regressions — they are pre-existing
bugs in native strategies that the 25k seed did not happen to exercise.

Findings cluster into six root-cause classes (E1–E6).

---

## E1 — Find-path group span overextension (6 patterns, 8 findings)

A capturing group that contains a greedy quantifier is followed by a suffix. On the `find()`
/ `findAll()` path the group-end tag is placed after the suffix is consumed rather than before
it, so the reported group span is wider than the JDK's.

Same root-cause class as the A1/A2 work; the earlier fixes addressed `matches()` and the first
`find()` call. These patterns exercise subsequent `findAll()` iterations and the `match()`
entry point with a non-trivial prefix.

| Pattern | Input | Symptom |
|---------|-------|---------|
| `(_.*)- ` | `_--` | `findAll() match 0 group 1 span differs` |
| `([10][-b]+)[^0]` | `1-c0bb` | `findAll() match 1 group 1 span differs` |
| `.+([0].)` | `b00-` | `findAll() match 0 group 1 span differs` |
| `(cb?b*)[a-c]` | `cbc` | `findAll() match 0 group 1 span differs` |
| `(cb?b*)[a-c]` | `cba` | `findAll() match 0 group 1 span differs` |
| `([c][^-]{3,}){1}[0-c]` | `c0bc_c` | `findAll() match 0 group 1 span differs` |
| `.{1,2}(10)_?` | `-10` | `match() group 1 span differs` |
| `.{1,2}(10)_?` | `-10` | `findAll() match 0 group 1 span differs` |

---

## E2 — Anchor at unusual position (6 patterns, 10 findings)

Patterns where an anchor (`^`, `$`, `\A`, `\Z`) appears inside a group body, after a quantified
group, or combined with alternation and backreferences. The routing logic does not recognise all
these forms and assigns a strategy that does not handle the anchor correctly.

Four sub-forms:

### E2a — `\A` + repeated capturing group
`\A` combined with `{n,}` on a capturing group. The strategy treats `\A` as handled but the
repeated-group bookkeeping conflicts with the start-anchor assertion.

| Pattern | Input | Symptom |
|---------|-------|---------|
| `\A(c){1,}` | `0c` | `find() boolean differs` |
| `\A(c){1,}` | `ac` | `findAll() count differs` |
| `\A(c){1,}` | `` | `find() boolean differs` |

### E2b — Quantified charset + `\Z` (no capturing group)
The outer match span (group 0) is wrong — not a capture-tracking issue but a match-boundary
error. The standard `hasStringEndAnchorInAlternation` guard does not apply here (no alternation).

| Pattern | Input | Symptom |
|---------|-------|---------|
| `[^-]{3,}\Z` | `c` | `first-match span differs` |
| `[^-]{3,}\Z` | `c` | `findAll() match 0 group 0 span differs` |
| `[^c]*\Z` | `` | `first-match span differs` |
| `[^c]*\Z` | `` | `findAll() count differs` |

### E2c — `^` inside or after a group body
`^` used as a "must-be-at-start" assertion appearing inside or immediately after a capturing
group. The compiler does not route these to a strategy that handles mid-group anchors.

| Pattern | Input | Symptom |
|---------|-------|---------|
| `(0)+^` | `0` | `find() boolean differs` |
| `(0)+^` | `0` | `findAll() count differs` |
| `(a+.{3,6}^)` | `a0-0` | `match() boolean differs` |

### E2d — Anchor inside alternation combined with backreference
The routing logic handles `\A`-in-alternation but not when a backreference is also present.

| Pattern | Input | Symptom |
|---------|-------|---------|
| `(c|a?){3}\A\1?` | `a` | `find() boolean differs` |
| `(c|a?){3}\A\1?` | `a` | `findAll() count differs` |

---

## E3 — Backreference divergence (5 patterns, 8 findings)

The backreference (`\1`) resolves to a wrong value or the match boolean is wrong. Distinct from
E1 (group span error): here the entire match succeeds or fails where the JDK says otherwise.

| Pattern | Input | Symptom |
|---------|-------|---------|
| `(.b{0})?\1` | `--` | `find() boolean differs` |
| `b(-)\1{1}` | `--` | `find() boolean differs` |
| `b(-)\1{1}` | `--` | `findAll() count differs` |
| `${1}[^a]` | `` | `find() boolean differs` |
| `${1}[^a]` | `` | `findAll() count differs` |
| `(b{1,}){1}\1+\|(1)` | `bb` | `find() boolean differs` |
| `(b{1,}){1}\1+\|(])` | `bb` | `findAll() count differs` |
| `^(.{1}\|.{0}){4}\1{3}` | `-1_a` | `find() boolean differs` |
| `^(.{1}\|.{0}){4}\1{3}` | `-1_a` | `findAll() count differs` |
| `^(.{1}\|.{0}){4}\1{3}` | `_1cc` | `find() boolean differs` |

---

## E4 — Repeated group last-iteration span (2 patterns, 3 findings)

After a group is matched multiple times via `*` or `{n,}`, the final captured span is wrong on
a subsequent `findAll()` call. The per-iteration span-reset logic does not fire correctly for
the last iteration before the quantifier exits.

| Pattern | Input | Symptom |
|---------|-------|---------|
| `(-+)*` | `` | `findAll() match 4 group 1 span differs` |
| `(c{2}){1,}` | `c` | `find() boolean differs` |
| `(c{2}){1,}` | `c` | `findAll() count differs` |

---

## E5 — Alternation with mixed-width branches (2 patterns, 2 findings)

Complex alternation where branches have different widths and the outer match span (group 0) is
computed incorrectly. Distinct from B5 (variable-length body inside a capturing group) — here
the outer span is wrong, not an inner group's span.

| Pattern | Input | Symptom |
|---------|-------|---------|
| `(-{0}0[]\|[0]*b{1})(]|1*).{2}` | `b0a` | `first-match span differs` |
| `(-{0}[]{1})(]\|1*).{2}` | `0a` | `findAll() count differs` |

---

## E6 — Simple group routing (1 pattern, 3 findings)

A straightforward pattern routes to a strategy that produces wrong `find()` boolean results.
Likely a gap in a routing guard rather than an execution bug.

| Pattern | Input | Symptom |
|---------|-------|---------|
| `[1]([^b]{2})` | `1a-` | `find() boolean differs` |
| `[1]([^b]{2})` | `1a-` | `findAll() count differs` |
| `[1]([^b]{2})` | `10_` | `find() boolean differs` |

---

## Reproducibility

All findings are reproducible with:

```
./gradlew :reggie-integration-tests:test \
  --tests "*.AlgorithmicFuzzTest.divergenceGate_extended" \
  --no-daemon
```

The `divergenceGate_extended` test uses `BASE_SEED`, skip=25 000, count=25 000, depth=3 —
identical to re-running `divergenceGate` starting from pattern 25 001.

For one-off exploration beyond 50k:

```
./gradlew :reggie-integration-tests:test \
  --tests "*.AlgorithmicFuzzTest.divergenceGate_extended" \
  -Dreggie.fuzz.size=75000 \
  -Dreggie.fuzz.skip=50000 \
  -Dreggie.fuzz.maxFindings=9999 \
  --no-daemon
```

---

## Next steps (priority order)

1. **E2b** — quantified charset + `\Z` without alternation: the `hasStringEndAnchorInAlternation`
   guard needs a companion that covers `\Z` after a quantifier with no alternation present.
2. **E6** — `[1]([^b]{2})` routing gap: run `debugPattern` to identify the misrouted strategy.
3. **E1** — find-path group span: remaining cases after A1/A2; same root-cause, new trigger shapes.
4. **E2a/E2c** — `\A`/`^` + repeated group: new anchor-in-repetition routing class.
5. **E3** — backreference edge cases: optional-group backref, quantified backref in alternation.
6. **E4** — repeated group final-iteration span.
7. **E5** — mixed-width alternation outer span.
