---
id: dead-bitstate-lazy-fastconsume
type: deadend
status: refuted
depends_on: [q-hybrid-anchored-admission]
supersedes: []
related: [ev-r2b-landed, q-lazydfa-findfrom-leftmost]
tags: [bitstate, lazy-loop, perf, measured-negative, rollback]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# BitState lazy char-class loop fast-consume — measured flat, ROLLED BACK

## What was built
The lazy mirror of the existing greedyLoopMid fast path in BitStateMatcher:
a lazy single-char-class loop (`c*?`) whose continuation closure contains no
anchor, no accepting state, and at least one consuming transition gets a
tight first-pass: consume the whole `c`-run in a scan, mark the same
(mid,p)/(leaf,p) visited cells, push exit jobs ONLY at positions where the
continuation's first-char set can match input[p] (descending push = ascending
pop = Perl lazy priority). All gates green: 3 fuzz seeds (777/48879/131071),
RealFindParityProbe (266,662 pairs / 0 div), RealInputParityProbe
(270,351 pairs / 0 div), full test suite.

## Why it was rolled back (box evidence)
- Box JMH matched sweep: 334.6±4.7us vs 320-327us for 6ad33a0 same-day
  (controls flat) — flat to slightly negative.
- Box per-pattern (C2-converged) showed the path NEVER ENGAGES on the corpus
  lazy families:
  - stack-frame `\s*((?<function>[^@]*)@)?(?<file>.*?)(:?\d+)?(:\d+)?`:
    49.9us/6 pairs, IDENTICAL to pre-change. Its `.*?` barely consumes —
    the all-optional tail lets the lazy loop exit EMPTY almost immediately
    (zero-width guard correctly disables the fast path). Its 8us/pair cost is
    unanchored-seed DFS overhead + greedy give-back, NOT loop consumption.
  - kind-message `^(?<kind>.+?): (?<message>.+?)( --->.+)?$`: 19.5us vs
    21.8us — the fast path engages (continuation first-set {':'} narrow) but
    kind-consumption is a small share of the pattern's total cost.
- Local probe "wins" (stack-frame 20->16us, kind-msg 10->7us, at-line 5->3us
  single JVM) were NOISE. RULE REINFORCED: local single-JVM probe deltas are
  not perf evidence — box JMH + box per-pattern only.

## What this rules out / redirects
- Lazy-loop stack round-trips are NOT the lazy families' cost center.
- The real lever (shared with the A-remainder start-anchored hybrid
  re-admission): a priority-correct (lazy leftmost-first) AND fast
  (~140ns/char -> ~30 target) captureless DFA find in the LazyDFA/RD lane.
  The chain lane already proves leftmost-first lazy is achievable for simple
  shapes (`a.*?b` -> [0,4) on JDK-parity probes).

## Re-entry points if revisited
- BitStateMatcher greedy fast-consume block is the template (greedyLoopMid
  fields, shape detection in the compiled matcher ctor); the lazy variant was
  fully written and gate-verified — see git stash / this node's design above.
- MatchSweepProbe3 (per-pattern C2-converged: warm 12k / measure 4k) is the
  per-family meter; RealCorpusScanBenchmark is the acceptance gate.
