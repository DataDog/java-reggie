---
id: dead-bitstate-lazy-fastconsume
title: BitState lazy char-class loop fast-consume measured flat on the real corpus — rolled back; lazy-loop consumption is not the lazy families' cost center
kind: dead-end
tags: [bitstate, lazy-loop, perf, measured-negative, rollback]
applies_to: [reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/BitStateMatcher.java]
source: backend-ready/dead-bitstate-lazy-fastconsume
status: active
recorded: 2026-09-28
valid_at: 2026-09-28
---


# BitState lazy char-class loop fast-consume measured flat on the real corpus — rolled back

## What was tried
A lazy mirror of the existing greedy fast-consume path in BitStateMatcher: a lazy
single-char-class loop (`c*?`) whose continuation closure contains no anchor, no accepting
state, and at least one consuming transition gets a tight first pass — consume the whole
`c`-run in a scan, mark the same visited cells, push exit jobs only at positions where the
continuation's first-char set can match input[p] (descending push = ascending pop = Perl
lazy priority). Fully implemented and gate-verified (fuzz seeds, real-corpus parity probes
0 divergences, full suite).

## Why it was rolled back
- Box JMH matched sweep: flat to slightly negative vs the pre-change build; controls flat.
- Per-pattern C2-converged profiling showed the path barely engages on the corpus lazy
  families: with an all-optional tail (`\s*((?<function>[^@]*)@)?(?<file>.*?)(:?\d+)?...`)
  the lazy loop exits EMPTY almost immediately (the zero-width guard correctly disables the
  fast path) — its cost is unanchored-seed DFS overhead + greedy give-back, not loop
  consumption. Where it does engage (narrow continuation first-set), loop consumption is a
  small share of the pattern's total cost.
- Local single-JVM probe "wins" were noise. Rule reinforced: **local single-JVM probe
  deltas are not perf evidence — box JMH + per-pattern C2-converged profiling only.**

## What this rules out / redirects
- Lazy-loop stack round-trips are NOT the lazy families' cost center.
- The real lever: a priority-correct (lazy leftmost-first) AND fast captureless DFA find in
  the LazyDFA/RD lane — the chain lane proves leftmost-first lazy is achievable for simple
  shapes; the fast-consume design above is the template if revisited (greedy fast-consume
  fields + ctor shape detection in BitStateMatcher).
