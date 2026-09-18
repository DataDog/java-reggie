---
id: q-generated-nfa-findfrom-broken
type: question
status: open
depends_on: [ev-hybrid-context-rematch]
supersedes: []
related: []
tags: [optimized-nfa, findfrom, generated-code, latent-bug]
generator: cairn
created: 2026-09-18
updated: 2026-09-18
---

# OPEN, LATENT: generated OPTIMIZED_NFA findFrom mishandles quantified-group shapes

Proven while building ev-hybrid-context-rematch (direct calls on the hybrid nfa-half, which is
the same generated class a standalone compile would produce):
- (.c)+ on "-cc": findFrom misses the leftmost [0,2) match and returns start 1 ([1,3)); match()
  and matchBounded(1,7) are correct.
- (.0){3,} on "1b0c010bc-a": findFrom returns -1 at every start while matchBounded(1,7)=[1,7).

Why nobody noticed: every such pattern has captures -> routes hybrid, and the hybrid's
find()/findFrom() served by the DFA half; the generated half's own search was never called.
Standalone exposure risk: a captureless give-back shape routing standalone OPTIMIZED_NFA with
find() would diverge — none in the 513 corpus or current fuzz window, but the generator bug is
real. Fix options: (a) root-cause NFABytecodeGenerator.generateFindFromMethod's start-scan for
quantified groups, or (b) route captureless give-back shapes away from standalone
OPTIMIZED_NFA. Entry point: NFABytecodeGenerator.findFrom generation (line ~4181, the
sketch shows epsilonClosure(currentStates, input, startPos) then per-char stepping — suspect
the closure/accept bookkeeping across the {n,} unrolled structure). Repro: NfaHalfProbe in
/tmp/prefilter (unwrap via EngineRouting, reflect nfaMatcher field).
