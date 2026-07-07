# Finding: greedy dotall-sink fast-forward never fires

Status: fixed. Found during a Codecov coverage-gap sweep on PR #94.

## Summary

`perf: greedy dotall-sink fast-forward for (?s).* tails` (commit `4499480`) added
`PikeVMMatcher.computeSinkGroups` plus a fast-forward check in `findMatchResultFrom`
(~L965-971) intended to jump straight to `regionEnd` when the sole surviving thread is
parked on a `(?s).*` tail, instead of stepping every remaining character. JaCoCo shows the
success branch of `computeSinkGroups` (L1626-1645) has zero coverage — including under its
own dedicated test, `GreedyDotSinkFastForwardTest`. All 7 of that file's tests pass, but only
because the normal per-char PikeVM stepping produces the same correct result; the fast path
itself never executes.

## Root cause

`computeSinkGroups` builds, for each candidate seed state, the pure epsilon-closure `C` of
that seed, then checks whether the seed's own consuming transition target already lies in
`C`. That test assumes the loop-back is reachable via epsilon edges alone from the seed.

But `ThompsonBuilder.visitQuantifier`'s `X*` construction (L184-217) wires the loop the other
way: `entry --[charset]--> exit`, then `exit --ε--> entry` (loop-back epsilon is on the *exit*
state, added after the consuming step — see also `visitLiteral`/`visitCharClass`, which give
`entry` no epsilon out-edges of its own). The only valid seed candidate (`entry`, the sole
state with a consuming transition — `getTransitions().isEmpty()` filters out `exit`) has an
epsilon closure of just `{entry}`, so `exit` is never in `C` and the `ok` check always fails.

## Fix sketch (not implemented)

The detection needs to take one consuming step before computing the epsilon closure: from
seed, follow its consuming transition to a target state, compute *that* state's epsilon
closure, and check whether the seed is back in it. That matches the actual
consume-then-loop-via-epsilon shape the builder produces.

## Fix

Rewrote the closure computation as a single fixed-point worklist that crosses both epsilon
edges and validated dotall-consuming edges together, rather than computing a pure
epsilon-closure and checking membership afterward. This matches the actual
consume-then-loop-via-epsilon shape the Thompson builder produces. Verified via:
`GreedyDotSinkFastForwardTest` (7/7), the full `:reggie-runtime:test` suite, JaCoCo (the
previously-`ci=0` success-path lines now show `ci>0`), `StrategyCorrectnessMetaTest` with
`-Dreggie.metatest.enforce=true`, and `AlgorithmicFuzzTest`'s divergence gate (unchanged
budget of 28). A JMH benchmark on `(?s)x(.*)` over a ~300k-char tail shows Reggie now at
~140k ops/ms vs JDK's ~45k ops/ms (~3.1x), confirming the fast-forward is firing.
