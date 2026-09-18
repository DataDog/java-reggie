---
id: q-lazydfa-findfrom-leftmost
type: question
status: resolved-fixed
depends_on: []
supersedes: []
related: [ev-r2b-landed, find-refusal-set-parity]
tags: [lazydfa, leftmost, soundness, fixed]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# LazyDFACache.findFrom leftmost restart — FIXED (222c8e6)

## Original question (from the R2b arc)
findFrom restarts a dead attempt at death+1 without re-walking — a later viable start
can begin inside a dead attempt's span. Considered latent because no corpus pattern
routes LAZY_DFA.

## Resolution (2026-09-17, 222c8e6)
CONFIRMED and FIXED. Blast radius was larger than "latent": LazyDFACache.findFrom is
also used INTERNALLY by PikeVMMatcher (findStep/rejectStep) and BitStateMatcher
(rejectStep) — but with SELF-ANCHORING closures (findStepClosure/rejectStepClosure
re-inject the start state at every position), for which the pos+1 restart is SOUND (the
union covers every start in the dead span). The PLAIN step (generated LAZY_DFA matchers,
HybridMatcher's dfaMatcher delegate) needed matchStart+1. Fix:
- findFrom = plain: restart at matchStart+1, matching nfaFallbackFindFrom's existing
  semantics — the DFA path previously DISAGREED with its own frozen-cache fallback.
- findFromUnion = self-anchoring: restart at pos+1 (unchanged behavior); PikeVM (3 sites)
  and BitState (1 site) converted.
Corpus hybrid lane perf-unaffected (.*-prefixed DFA is death-immune, no restarts); box
re-run flat. LazyDfaLeftmostTest (trap: x(?:a+b+|b+a+){75} on "xax"+"ba"*75 -> leftmost 2
not 3), validated test-first (3 failures pre-fix incl. the xx-control).

## Lessons (don't re-derive)
1. A minimal leftmost trap does NOT need alternation — bounded runs alone do (x{2}y on
   "xxxy": start 1 lies inside the dead span [0,2)).
2. Reaching LAZY_DFA from the public API is a narrow window (see ev-lazydfa-nfa-delegate-limit):
   DFA_TABLE grabs everything until stateSlots x classCount x 4B > 1MB, and above that
   the NFA-delegate span methods blow the 64KB method limit at ~6800 NFA states. The
   known-good route is a subset-construction explosion (e.g. x(?:a+b+|b+a+){75}).
