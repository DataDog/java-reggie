---
id: q-z-anchor-span-bug
type: question
status: resolved-fixed
depends_on: []
supersedes: []
related: [ev-r1-prefilter-landed]
tags: [anchor, Z-anchor, span, greedy, pre-existing, fuzz]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# PRE-EXISTING span divergence: $ + \Z with newline-consuming greedy star — FIXED (222c8e6)

## Reasoning chain
Surfaced while landing R1: pattern `[^_-ab-c]*$\Z` on input "ccc0_1\n00a\n" — JDK
first-match span [10,11) (greedy star consumes the final \n, then $/\Z at end) vs
reggie [10,10) (empty match at position 10). Reproduces on the PRE-CHANGE build
(verified via git stash) and standalone via RegexFuzzOracle.check — NOT an R1
regression. Hidden in the seeded fuzz sweep before because the oracle's
instanceof-JavaRegexFallbackMatcher skip normally masks it; R1's wrapper broke that
skip (fixed via isJdkFallback()), which shifted the input RNG stream onto the
diverging input. Engine-side fix (greedy consumption order across $\Z boundary) is a
separate work item.

## Resolution (2026-09-17, 222c8e6)
Diagnosis: NOT an anchor-semantics bug — reggie's $/\Z were correct everywhere (verified:
\Z and $ on "\n" -> [0,0) matching jdk). The bug: the DFA_UNROLLED greedy walk's
pattern-level hasStringEndAnchor special check recorded the before-final-terminator
acceptance and RETURNED, truncating any greedy run whose charset can consume the final
line terminator. Trigger = TWO consecutive end anchors ($\Z, \Z\z) or a terminator-
consuming run nested behind other elements; single trailing anchor patterns escape to
SPECIALIZED_SUFFIX_SEQUENCE (correct). Fix: the special block was redundant-or-wrong —
the accepting-state recording already evaluates the state's FULL anchor conditions at
every position (emitSingleAnchorCheck: pos==len, len-1 terminator, len-2 CRLF) and falls
through to consuming transitions — deleted it. Corollary fixed free: [^a]*\Z\z on "x\n"
(the check ignored \z in the state's conditions). GreedyEndAnchorSpanTest: 28-case JDK
span matrix + route assertions, validated test-first (4 failures pre-fix).
Perf: box re-run flat (no-match 952us vs 983 R2b, matched 339 vs 338, rust/jdk controls
flat). matches() unaffected (full-match requires the entire input consumed; verified
matrix). Latent observation kept open elsewhere: DFASwitchBytecodeGenerator.matches()
has the same-shaped pattern-level early-TRUE at before-terminator positions — no current
routing reaches it with a diverging shape (BITSTATE takes the probes); revisit if
routing changes.
