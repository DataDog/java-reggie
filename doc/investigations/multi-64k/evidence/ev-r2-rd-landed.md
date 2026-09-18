# ev-r2-rd-landed: R2 first half — linear find() for .*-prefix recursive-descent patterns (b5cf63e)

USER DIRECTIVE: "checkpoint and move to R2" (+ BytecodeDebugger fix detour 6e9b8eb:
debugger now reports ACTUAL pipeline routing via RuntimeCompiler.describeRouting /
ROUTING_NOTE — the isolated analyzer recommendation was misleading routing triage).

DIAGNOSIS CHAIN (all measured, no guessing): LengthProbe complexity fingerprint isolated
two quadratic lanes (restart-per-position): (1) the ^/$-anchored .*-prefix family routes
RECURSIVE_DESCENT via requiresBacktrackingForGroups (star must give back chars for correct
spans) — quadratic in the GREEDY GIVE-BACK, not the position loop: the backtrack loop
re-matches the body k times from scratch per level (O(n^2); ~300us @ n=1024); (2) the
HybridMatcher lane (unanchored .*-prefix; still open).

LANDED (b5cf63e, full suite + fuzz + span-parity battery green):
1. Anchor-start: fully ^/\A-anchored (non-multiline) via conservative leftmost-mandatory-
   spine AST walk -> findFrom(start>0) = -1; position loop makes ONE attempt.
2. Fast give-back: capture-free quantifier body => deterministic => record iteration end
   positions (int[] iterEnd at the level's localBase slot, threaded past nested-backtracking
   slot ranges which start at localBase+1, +6 per nesting level — a slot-16 collision with
   generateNestedBacktracking caused a real corruption, caught by the span battery and fixed)
   => give-back becomes an array lookup. POSIX last-iteration slots set from the array (only
   read by the tryMatchCount==1 inner-shrink retry — emulated exactly). Capturing bodies keep
   the original path.

MEASURED (workspace-jb, RealCorpusScanBenchmark -wi 3 -i 5 -f 2, rust control flat):
- reggieSweepNoMatch 3,727 -> 2,657us (R1+R2 total: 33,896 -> 2,657 = 12.7x; reggie now
  1.19x FASTER than rust's 3,176us on no-match).
- reggieSweepMatched 586 -> 508us (RD match-mode patterns benefit from fast give-back).
- Blended whole-sweep: reggie 3,165us vs rust 3,598 vs jdk ~50K — reggie is the fastest
  engine on the real corpus overall (~16x jdk).
- Per-pattern fingerprint: ^(.*)-([0-9]{1,4})$ 306us -> 3.0us @ n=1024 (100x, linear).

REMAINING R2b: HybridMatcher lane (unanchored .*-prefix family, ~1.1ms of the 2.66ms
residual, now ~40% of the sweep): needs reverse-DFA or .*?-prefixed single-pass DFA for
its boolean find — separate work item. Also unpicked: 1-char high-selectivity facts (~, _)
as R1 last resort.
