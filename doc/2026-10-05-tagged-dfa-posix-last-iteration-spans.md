# Tagged DFA: last-iteration spans for quantified capturing groups

Status: **to investigate**. Nothing has been implemented.

## Problem

`PatternAnalyzer` sets `usePosixLastMatch` for patterns with capturing groups inside repeating
quantifiers (`hasGroupsInRepeatingQuantifiers`), e.g. `(.c)+`. The DFA strategies do not report
the span of the group's last iteration for these patterns, so neither compile path uses a DFA for
captures:

- **Runtime:** `RuntimeCompiler.shouldUseHybrid` returns true when `usePosixLastMatch` is set, and
  `compileHybrid` builds a `HybridMatcher`. A captureless DFA serves the boolean methods, and an
  `OPTIMIZED_NFA` half built with `usePosixLastMatch` serves `match`/`findMatch`.
- **Annotation processor:** `ReggieMatcherBytecodeGenerator.resolveRealization` returns
  `Realization.HYBRID`. `generateHybrid` emits two classes, `<Matcher>$Dfa` and `<Matcher>$Nfa`,
  and the generated `$Impl` caches a `HybridMatcher` whose NFA half is a `PooledMatcher`, because
  generated NFA matchers write instance fields (e.g. `matchInto` reads `groupStarts` and
  `epsilonWorklist`). Concurrent capture calls each need their own NFA-half instance.

The hybrid costs two generated classes per pattern, an NFA run on every capture call, and on the
annotation-processor path, an NFA-half allocation whenever capture calls overlap.

## Goal

Make the tagged DFA (`DFAUnrolledBytecodeGenerator` with `useTaggedDFA`) record last-iteration spans,
so these patterns can stay on a single DFA strategy on both paths and the hybrid becomes unnecessary
for them.

## Starting points

- The tag operations live on DFA transitions (`tagOps`). They are consumed by
  `generateFindMatchFromMethodTaggedImpl` (the public `findMatchFrom` and the private
  `findMatchFromForMatch` no-cut variant used by `match()`).
- `(.c)+` analyzes to `DFA_UNROLLED_WITH_GROUPS` with both `useTaggedDFA` and
  `usePosixLastMatch` set.

## Oracle

Use `java.util.regex`. `LaurikariDfaMatcher` (via `LaurikariDfaSupport.tryCreate`) is **not** a
usable oracle: `LaurikariEligibility.isEligible` returns false whenever `usePosixLastMatch` is set,
because that engine implements leftmost-first priority, not last-iteration semantics.

## Known divergences on the current hybrid NFA half

On both compile paths, `((a+)+)` on `"aaa"` reports group 2 as `[2,3)`; `java.util.regex` reports
`[0,3)`. The `NESTED_QUANTIFIED_GROUPS` generator, which the annotation processor used for this
pattern before the HYBRID realization, reported the same `[2,3)`. Any replacement must be checked
against these shapes, not just against single-level `(x)+` patterns.
