---
id: ev-word-boundary-find-fix
type: evidence
status: confirmed
depends_on: [ev-real-input-parity]
supersedes: []
related: [q-hybrid-anchored-admission, find-refusal-set-parity]
tags: [word-boundary, find, pre-existing-bug, parity-gate, 10b1a43]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# \b find() divergences fixed (pre-existing) + permanent find-parity gate (10b1a43)

## Found by the NEW find-span parity battery (first run)
Full-match parity was blind to find()-only failures. RealFindParityProbe (513 corpus x 527
harvested real lines, find + span + groups vs JDK): 1 divergence — `\bname:(\S+)` on
"@peer.hostname:127.0.0.1": JDK no-match (t|n no boundary), reggie [10,24) "name:127.0.0.1"
under SPECIALIZED_MULTI_GROUP_GREEDY. WbMatrix then isolated the offender set:
- MULTI_GROUP_GREEDY generator: no \b modeling at all (silent no-op)
- RECURSIVE_DESCENT generator: `\b(.*)end` on "appendend" returned NO-MATCH (jdk [0,9))
- SOUND (measured): DETERMINISTIC_CHAIN, FIXED_SEQUENCE, BITSTATE, PINNED_BACKREFERENCE,
  lookahead fusers, ONEPASS (has explicit \b check), subset-DFA paths (PIKEVM re-route)

## Fix
PatternAnalyzer declines MULTI_GROUP_GREEDY + the requiresBacktrackingForGroups RD path for
\b/\B patterns (wordBoundaryAnchor guard); they fall through to boundary-aware routes.
A blanket "all \b -> PikeVM" was tried first and REVERTED: it broke backref routing
((\w+)\b\1 needs PINNED_BACKREFERENCE — PikeVM has no backrefs) and several specialized
lanes that DO model \b. Targeted declines only.

## Gates
266,662 find pairs / 26,318 finds / 0 divergences (drops to 0 after fix; battery committed as
RealFindParityTest, ~2s, with allowJdkFallback drop-in contract). 270k full-match parity, fuzz
48879 (0 findings), 131071 (only the 3 known pre-existing caret-midpattern findings). Suite +
integration green. Corpus \b patterns: 5 (negligible perf surface).

## \b hybrid exclusion (kept in RuntimeCompiler)
Subset construction drops WORD_BOUNDARY context (SubsetConstructor.isPositionAnchor excludes
it) WITHOUT setting anchorConditionDiluted — so a hybrid DFA-half would over-accept across
boundaries; explicit hasWordBoundaryAnchor skip added (defense-in-depth; with the blanket
anchor skip restored it is redundant but documents the failure mode).
