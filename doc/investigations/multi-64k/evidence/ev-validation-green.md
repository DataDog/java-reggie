---
id: ev-validation-green
type: evidence
status: confirmed
depends_on: []
supersedes: []
related: [find-l2-splitter-shipped, find-no-overflow-trigger-today]
tags: [tests, regression, probe-results]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# Final validation state (all green) + strategy probe outputs

## Unit — SplittingClassVisitorTest (6/6, reggie-codegen)
1. `fitsReplaysVerbatim` — small method → exactly one method, correct result.
2. `splitsOversizedStraightLineMethod` — ~72KB accumulator split into chunks (live int slot as
   extra param on every chunk entry), loads (verifier validates recomputed frames), exact sum.
3. `splitsWithLiveReferenceLocal` — String local live across every cut (exercises copy-through
   typing; originally the ASTORE-unmask path, now free via DV).
4. `splitsWithForwardCrossingBranches` — 6,001 if/else iterations, exact result.
5. `tryRegionStaysWholeAndBothPathsBehave` — cuts only before/after region; normal and
   exceptional paths exact (pre/post bulks ~66KB total).
6. `declinesToSplitLoopBodyAndPreservesTodayBehavior` — oversized loop body, back edge crosses
   every cut → declined → verbatim → `MethodTooLargeException` propagates (today's behavior).

## Regression
- reggie-runtime: **3,152 tests, 0 failures** through the new pipeline (cleanTest-forced).
- reggie-processor + reggie-integration-tests: BUILD SUCCESSFUL (cleanTest-forced).
- spotlessApply clean; full `./gradlew build` SUCCESS (one earlier transient failure from a
  stale hung test worker, not reproducible).

## Strategy probe (SplitterProbeTest, deleted after use)
```
SHAPE n=2000 len=3: compiled in 8546ms, class=BitStateMatcher, chunkMethods=[]
SHAPE n=4000 len=3: OutOfMemoryError: Java heap space
SHAPE n=2000 len=5: compiled in 13461ms, class=BitStateMatcher, chunkMethods=[]
```
