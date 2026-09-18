# ev-correctness-cleanup-a: two soundness fixes landed (222c8e6)

USER DIRECTIVE: "let's take them in order" — item A (correctness cleanup) of the
backend-ready next-steps list.

## Fix 1 — greedy span truncation before end anchors (q-z-anchor-span-bug)
Diagnosis: NOT anchor semantics — reggie's $/\Z were correct everywhere (\Z and $ on
"\n" -> [0,0) matching jdk). The DFA_UNROLLED greedy walk's pattern-level
hasStringEndAnchor special check recorded the before-final-terminator acceptance
position and RETURNED, truncating any greedy run whose charset can consume the final
line terminator: [^a]*$\Z on "x\n" -> [0,1) instead of [0,2); [^a]*\Z\z -> [0,1) instead
of [0,2) (the check also ignored the state's actual anchor conditions — \z fails before
the terminator). Trigger: two consecutive end anchors ($\Z, \Z\z) or a terminator-
consuming run nested behind other elements ([a-z\s]+\d*$\Z, (?s:.)*$\Z, [a-z]*\s*$\Z);
single trailing anchors escape to SPECIALIZED_SUFFIX_SEQUENCE (correct — that's why the
corpus battery never saw it). Fix: the special block was redundant-or-wrong — the
accepting-state recording already evaluates the state's FULL anchor conditions at every
position (emitSingleAnchorCheck: pos==len, len-1 terminator incl. NEL/LS/PS/CRLF guard,
len-2 CRLF) and falls through to the consuming transitions — deleted it. Validated
test-first: GreedyEndAnchorSpanTest (28-case jdk span matrix + route assertions),
4 failures pre-fix, 0 after; full suite + integration + span battery + fuzz oracle green.
matches() verified unaffected (full-match requires the entire input consumed).

## Fix 2 — LazyDFA findFrom leftmost skip (q-lazydfa-findfrom-leftmost)
Blast radius larger than "latent": LazyDFACache.findFrom is used INTERNALLY by
PikeVMMatcher (findStep/rejectStep) and BitStateMatcher (rejectStep) with SELF-ANCHORING
closures (start re-injected every position — pos+1 restart sound, union covers the dead
span). The PLAIN step (generated LAZY_DFA matchers, HybridMatcher's dfaMatcher) skipped
viable starts inside dead spans: x(?:a+b+|b+a+){75} on "xax"+"ba"*75 reported 3 instead
of leftmost 2. Fix: findFrom = plain (restart matchStart+1 — now consistent with
nfaFallbackFindFrom, its own frozen-cache fallback, which ALWAYS restarted at
scanFrom+1; the two paths previously disagreed), findFromUnion = self-anchoring
(restart pos+1); PikeVM (3 sites) + BitState (1 site) converted. Corpus hybrid lane
death-immune (.*-prefix) — zero perf impact; box re-run flat (no-match 952us vs 983
R2b, matched 339 vs 338, rust/jdk controls flat). LazyDfaLeftmostTest, 3 failures
pre-fix, 0 after.

## Side findings
- q-caret-midpattern-anchor (OPEN, PRE-EXISTING): fresh fuzz seed 131071 found
  (?:[^a-caa]|c)^|.\z\z divergences (jdk [9,10) vs reggie [0,1); findAll 1 vs 7) —
  reproduces identically on the pre-fix tree. Mid-pattern ^ in alternation family.
- ev-lazydfa-nfa-delegate-limit: LAZY_DFA generation unreachable for NFA >= ~6800
  states (NFA-delegate span methods exceed the 64KB method limit -> JDK fallback);
  analyzer recommends it, RuntimeCompiler can't generate it. 0 corpus impact.

## Lessons
- A minimal leftmost trap needs no alternation — bounded runs alone (x{2}y on "xxxy").
- FuzzProbe now takes the seed as argv[0] (/tmp/prefilter) — multi-seed oracle runs are
  the honest way to claim "0 findings".
