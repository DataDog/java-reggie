---
id: q-generated-nfa-findfrom-broken
type: question
status: resolved-fixed
depends_on: [ev-hybrid-context-rematch]
supersedes: []
related: []
tags: [optimized-nfa, findfrom, latent-bug, required-literal, indexOf]
generator: cairn
created: 2026-09-18
updated: 2026-09-18
---

# FIXED (1283fec): generated OPTIMIZED_NFA findFrom indexOf scan-start jump gated on verified match prefix

IMPLEMENTED (committed 1283fec, pushed; all gates green, box flat): NFABytecodeGenerator.everyMatchStartsWith() +
collectContextAwareEpsilonClosure() verify the literal is a deterministic NFA prefix (bail on
assertion/backref/conditional/counted-loop states and on accepts before the literal completes);
the scan-start jump AND the retry jump emit only for verified prefixes; the sound indexOf==-1
rejection stays for all required literals (no-match fast path unchanged); extractLongestRequiredLiteral's
startState-eps>1 guard subsumed. findMatchFrom/findBoundsFrom delegate to findFrom — one fix covers
the family; processor delegates to the same generator (dual-path covered). NfaFindFromRegressionTest
(half via EngineRouting, HybridMatcher.nfaMatcher reflection, standalone fallback) asserts findMatchFrom
parity incl. group spans on the give-back family. Corpus extended 513->528 (+15 logs,synthetic guards:
IPv4 quantified-group, @domain$ anchor-in-branch, lookbehind kv, MAC OnePass, dotted-suffix give-back,
4 new hybrids -> 106) + 9 inputs; batteries 274,567/283,008 pairs 0-div, refused=7; fuzz 15/0-reg;
box matched 180.7±5.4 / no-match 636.4±3.2 (controls flat) = new baseline.

## Historical analysis (pre-fix, kept for context)


## ROOT CAUSE (proven 2026-09-18)
NFABytecodeGenerator.generateFindFromMethod (~line 4560) inits the candidate-scan loop with
`tryPos = input.indexOf(requiredLiteral, start)` (multi-char run >= 3 via
extractLongestRequiredLiteral, or single char from requiredLiterals), and the retry jump
(~line 4848) advances `tryPos = indexOf(requiredChar, tryPos + 1)`. Both assume the required
literal appears AT THE MATCH START (position 0 of the match). The actual requiredLiterals
semantic (PatternAnalyzer.RequiredLiteralsExtractor, ~11763) is only "must appear SOMEWHERE in
every match". For (.c)+ the required char 'c' sits at offset 1 of the match (any-char first):
on "-cc" findFrom returns start 1 instead of the leftmost 0; (.0){3,} on "1b0c010bc-a" returns
-1 while matchBounded(1,7)=[1,7) exists. The multi-char branch has the same flaw for suffix runs
(a(x|y)cdefg-style) and branch-local runs (extractLiteralFromState takes the longest run
anywhere in the machine, guarded only by startState-epsilon>1). match()/matchBounded() are
correct — only the findFrom scan machinery is wrong. Comments in the generator and at
PatternAnalyzer:1163 show the authors knew the position-0 assumption; the guard list
(anchored/backref-to-lookahead/skipLiteralOptimization/hybridInfo) is incomplete for
variable-length prefixes (quantified groups, alternation mid-pattern).

## REACHABILITY CENSUS (2026-09-18, build ff34f90)
- Corpus: 0 of 513 logs-backend patterns route standalone OPTIMIZED_NFA (full routing dist
  captured: DETERMINISTIC_CHAIN 98, DFA_UNROLLED 137, HYBRID_DFA 102, ...).
- Hybrid nfa-halves (generated OPTIMIZED_NFA): findFrom no longer called by HybridMatcher since
  the ff34f90 type-gate (PikeVM/BitState halves search; generated halves substring-re-match).
- Fuzz: all 15 remaining findings' patterns route SPECIALIZED_FIXED_SEQUENCE — not this bug.
- Constructed public-API batteries (captureless give-back, alternation, lookahead/lookbehind,
  backref): 0 divergences — captureless give-back routes DFA_UNROLLED, lookarounds take
  separated-execution or sound cases.
=> LIVE-UNREACHABLE today; becomes reachable if a future lane routes captureless patterns
standalone OPTIMIZED_NFA (e.g. priority-correct lazy-DFA find, B3b-style routing guard changes).

## FIX SHAPE (when a lane needs it)
Sound only if the literal is at offset 0 of every match: compute the NFA first-set (chars that
can begin a match); emit the scan-start jump only when first-set == {literal[0]} and, for
multi-char, the literal is a deterministic prefix run; keep the indexOf==-1 REJECTION
unconditionally (sound for any-position required literals — preserves the no-match fast path);
gate the retry jump on the same condition. Dual-path rule applies (processor twin passes
requiredLiterals into the same generator). Probes: /tmp/prefilter NfaHalfProbe (direct half
findFrom), LiteralJumpProbe/CapturelessProbe/LookaheadJumpProbe (public-API parity batteries),
CorpusOptNfa (routing census).
