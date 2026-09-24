---
id: find-fixes-complete
type: finding
status: confirmed
depends_on: [find-semver-exponential-compile, find-nul-pattern-truncation, find-brace-literal-rejected, find-groupcount-charclass-parens, find-unicode-property-gaps, find-case-insensitive-viable, hyp-bitstate-blowup-root]
supersedes: []
related: [hyp-bitstate-blowup-root, q-input-side-nul]
tags: [fixes, release-checklist, coverage]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# All 6 release-checklist items fixed on feat/dd_backend_check (2026-09-16)

Commits (worktree ~/go/src/github.com/DataDog/.worktrees/java-reggie-dd-backend-check,
branch feat/dd_backend_check, based on origin/main fd362c2):

1. `99a9461` fix: bound compile work for bounded quantifiers (semver hang, OOM bombs)
   - flattenClosure hoisted out of per-transition loop (semver: never-finishes -> ~850ms)
   - canReachGroupExit: memoized reverse BFS from group EXIT markers (was per-transition closure crawl)
   - work budget 200M units (-Dreggie.dfa.workBudget) + wall-clock deadline 10s
     (-Dreggie.dfa.deadlineMs) + OOM->StateExplosionException guard in SubsetConstructor
   - NFA state cap 1M (-Dreggie.nfa.maxStates) in ThompsonBuilder.createState: 20x{0,8}
     bomb OOM/never -> 222ms graceful UnsupportedPatternException
   - root cause of hyp-bitstate-blowup-root: NOT bitstate itself; DFA analysis determinization
     (PatternAnalyzer -> SubsetConstructor.buildDFA) dominated; semver builds ~0.3-0.6M NFA
     states legitimately (cap 100K was too low; 1M works, verified under -Xmx512m)
2. `cbb6ca5` fix: NUL literal dropped (EpsilonNode refactor)
   - LiteralNode((char)0) epsilon sentinel collided with real NUL literals; new ast.EpsilonNode
     extends LiteralNode; ~15 epsilon-check sites converted to instanceof EpsilonNode
   - raw NUL/\x00/\0 now match NUL like JDK; replaceAll("\u0000","")/split("\0") semantics verified
3. `0727796` fix: bare '}' is a literal (parseAtom branch; 19 consumer patterns unblocked;
   invalid quantifier specs still error with JDK parity)
4. `fdd6df2` fix: countGroups textual scan now skips [...] classes (groupCount parity)
5. `f52461a` feat: POSIX property classes (Alnum/Alpha/Digit/Lower/Upper/Blank/XDigit/ASCII/
   Cntrl/Space/Graph/Print/Punct ASCII-only + IsAlphabetic/IsLetter/IsDigit Unicode-aware;
   \p{IsAlphabetic} standalone strict-rejects on 64KB method limit, fallback OK)
6. `dbd51e3` feat: JdkPatternCompatibility.toReggieFlags(pattern, flags) maps JDK
   CASE_INSENSITIVE for pure-ASCII patterns (empirically safe: input-side non-ASCII never
   diverges — İ/ı/K/ſ probes; only non-ASCII pattern letters diverge)

## Final corpus numbers (1150 patterns, both repos, strict Reggie.compile)
- profiling-backend: explicit 47/47 (100%), string-method 31/31 (100%)
- logs-backend: explicit 535/549 (97%), string-method 496/523 (94% — of JDK-valid: 98%;
  28 are textual-scan false positives, not regex)
- call sites with native-compiling pattern: 2369/2419 (97%)
- divergences 7 -> 2, zero new: (a) UNICODE_CHARACTER_CLASS flag artifact on [^\w:\-\.\/],
  (b) optional-group span on ^(?:(\d+):)?... (documented 28-item fuzz budget family)
- full gradle test suite green after every fix (incl. 5 new regression test classes)

## Residuals / not done
- 12x (a|b){0,256} still ~10-15s to compile (bounded, correct; multiple analysis passes each
  get a fresh deadline — a total-compile deadline would tighten this; API design decision)
- \p{IsAlphabetic} standalone + .*[...] shapes exceed 64KB generated-method limit (graceful;
  needs bitmap-based charset codegen to go native)
- q-input-side-nul: input-side NUL only spot-checked (see node; audit SWAR paths)
- UNICODE_CHARACTER_CLASS unsupported (1 lb site)
- re2j dynamic sites still need the compile-budget guarantee (now in place via 99a9461) +
  consumer-side policy (pattern length caps exist in UserDefinedRegex)
