# dd_backend_fit

_Prepare reggie for dd backend fit_

- status: done
- repo: datadog--java-reggie / branch: feat-dd-backend-check
- last_commit: 2fc44ccaa86a30ee2695695c2dbd0e26116536f7

## INDEX

# Investigation Index

## Findings (confirmed)
- find-replaceability-coverage | Reggie trunk covers 93-100% of literal patterns in both dd backends (1150-pattern harness) | [replaceability, coverage] 
- find-semver-exponential-compile | P0: {n,m} bounded quantifiers → exponential BitState compile (semver pattern hangs; DoS vector) | [p0, bitstate, dos]
- find-nul-pattern-truncation | P0: NUL char truncates pattern → matcher matches everything | [p0, nul, truncation]
- find-brace-literal-rejected | P1: JDK-literal '}' rejected outright, even under fallback (13 patterns incl. mustache) | [p1, compat, parser]
- find-groupcount-charclass-parens | P1: groupCount counts ( ) inside character classes | [p1, bug, groupcount]
- find-unicode-property-gaps | P1: \p{Alnum}/\p{Alpha}/\p{ASCII}/\p{Cntrl}/\p{Lower}/\p{IsAlphabetic} unsupported (7 lb sites) | [p1, unicode, posix-classes]
- find-api-surface-sufficient | API surface no longer a blocker; grok SPI + shadow-ratio infra is the insertion seam | [api, grok, spi]
- find-case-insensitive-viable | Inline (?i) viable for CASE_INSENSITIVE migration (15/15 patterns, 0 divergence) | [flags, case-insensitive]
- find-divergence-budget-verified | Only 7/1150 patterns diverge vs JDK; 1 within known 28-item budget, 1 flag artifact | [correctness, differential]
- find-re2j-dynamic-safety-gap | re2j replacement blocked: all re2j sites dynamic/untrusted; reggie lacks bounded compile | [re2j, untrusted, dos, blocker]

## Hypotheses
- hyp-bitstate-blowup-root | CONFIRMED: not BitState — flattenClosure-in-loop + unmemoized canReachGroupExit + Thompson unrolling (details in node) | [root-cause, dfa, determinization] | CONFIRMED

## Dead ends
- dead-env-gotchas | Stale git locks, logs-backend 'green' refspec, use git ls-files, ASM not bundled in reggie jar | [environment, git, classpath] | RESOLVED

## Evidence
- ev-harness-results | 1150-pattern corpus + results (strict/fallback/differential); artifact paths | [harness, corpus]
- ev-scale-timings | BitState compile scaling: 32→2.3s, 64→69s, 256→infinite | [timing, exponential]
- ev-nul-verify | NUL truncation probe outputs (pattern-side wrong, input-side spot-OK) | [nul, verification]

## Questions
- q-input-side-nul | Is input-side NUL fully correct across all strategies? | [nul, input, audit] | OPEN
- q-fallback-syntax-gap | Why doesn't compileAllowingFallback rescue syntax-level rejections? | [fallback, design, parser] | OPEN

## Findings (confirmed) — added 2026-09-16 fix sequence
- find-fixes-complete | All 6 release-checklist items fixed (commits 99a9461..dbd51e3); final coverage 97-100% | [fixes, coverage]
- q-total-compile-deadline | Should Reggie enforce a TOTAL-compile deadline (per-determinization only bounds passes×10s; 12x bomb still 10-15s)? | [design, deadline, untrusted] | OPEN
- q-huge-charset-codegen | Can 1000+-range charsets avoid the 64KB generated-method limit (bitmap codegen for \p{IsAlphabetic} shapes)? | [codegen, 64kb-limit] | OPEN

## Evidence — added 2026-09-16 fix sequence
- ev-final-corpus-results | Final corpus after 6-fix sequence: 97-100% coverage, 2369/2419 sites, divergences 7→2 | [corpus, coverage, final]

## Findings (confirmed) — added 2026-09-16 residuals pass
- find-remaining-incompatibilities-resolved | All 5 residual incompatibilities fixed (span leaks, UNICODE_CHARACTER_CLASS, NUL audit, compile deadline, huge-charset codegen); corpus divergences 0 | [residuals, divergences-zero]

## Question resolutions 2026-09-16
- q-total-compile-deadline | RESOLVED (b0c5615) | [design, deadline]
- q-huge-charset-codegen | RESOLVED (2fc44cc) | [codegen]
- q-input-side-nul | RESOLVED (542a76c, audit clean) | [nul, input]

## STATE

# Current State

## Active investigation
Feasibility check: can reggie (trunk) replace JDK regex + re2j in profiling-backend and logs-backend.
**Assessment COMPLETE** — verdict delivered; report at
`~/dd/java-reggie/doc/temp/2026-09-16-replaceability-assessment-profiling-logs-backends.md`
(reggie trunk fd362c2; pb e105e153d8, lb d6c8aed5347c). Investigation is now in
"fix findings in reggie" phase (this branch: feat-dd-backend-check).

## Current hypothesis
(none — all hypotheses resolved; hyp-bitstate-blowup-root CONFIRMED, see node)

## What I'm doing now
ALL residual incompatibilities RESOLVED (commits 60fda73..2fc44cc; see
find-remaining-incompatibilities-resolved): optional-group span leaks (chain
capture hygiene + B17 TDFA routing), UNICODE_CHARACTER_CLASS ((?U), BMP-differential
verified), input-side NUL audit (2152 checks clean), total-compile deadline (10s
default, NFA pass-through), huge-charset codegen (transition merge + lookup tables).
CORPUS: 1110/1150 strict-native, DIVERGENCES: 0, full suite green. Only
q-fallback-syntax-gap remains open (general design question, not a blocker).

## Open questions
- q-fallback-syntax-gap — general design question (should compileAllowingFallback defer to
  JDK parsing on reggie syntax errors?) — the concrete brace case was fixed via parser
  leniency in 0727796; not a migration blocker

## Confirmed findings (don't re-derive)
- find-fixes-complete — all 6 checklist items fixed, commits 99a9461..dbd51e3,
  final corpus 97-100%, divergences 7→2 (see node for residuals)
- find-remaining-incompatibilities-resolved — all 5 residuals fixed (60fda73..2fc44cc);
  corpus 1110/1150 strict-native, divergences 0
- find-replaceability-coverage — 93-100% literal coverage (pb 97/100%, lb 93/98%)
- find-semver-exponential-compile — P0 hang/DoS: bound 32→2.3s, 64→69s, 256→∞
- find-nul-pattern-truncation — P0: "\0" pattern matches everything
- find-brace-literal-rejected — P1: 13 patterns incl. all mustache templates
- find-groupcount-charclass-parens — P1: `[()\s]` → groupCount 1
- find-unicode-property-gaps — P1: 7 lb sites need POSIX-ish classes
- find-api-surface-sufficient — rich API OK; grok SPI+shadow = rollout seam
- find-case-insensitive-viable — inline (?i): 15/15, 0 divergence
- find-divergence-budget-verified — 7/1150 divergences, causes identified
- find-re2j-dynamic-safety-gap — re2j sites all untrusted → blocked on budget

## Ruled out (don't re-investigate)
- dead-env-gotchas — stale index.lock, 'green' refspec, git ls-files for lb,
  ASM classpath, incremental harness output (all resolved, use workarounds)
- String-method false positives (28 glob/path "patterns") — not regex, no action
- Scala/Kotlin regex in pb — none exists (checked all 13 files)

## Nodes

---
id: dead-env-gotchas
type: deadend
status: refuted
depends_on: []
supersedes: []
related: []
tags: [environment, git, gradle, classpath]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# Environment gotchas hit (resolved — don't re-debug)

- Both consumer repos had stale `.git/index.lock` from crashed git processes →
  pull failed with "an editor opened by git commit" message; fix: `rm .git/index.lock`.
- logs-backend remote.origin.fetch includes deleted `green` refspec → plain
  fetch/pull dies with "couldn't find remote ref refs/heads/green"; workaround:
  `git fetch origin prod && git merge --ff-only FETCH_HEAD`.
- logs-backend filesystem scans over 29k java files time out; ALWAYS use
  `git ls-files '*.java' | xargs grep …` instead of find/grep over the tree.
- reggie-runtime jar does NOT bundle ASM (declared `implementation`): any
  standalone harness needs asm/asm-commons/asm-util 9.10.1 on the classpath
  (from ~/.gradle/caches), else every compile fails with
  NoClassDefFoundError MethodTooLargeException (misleading).
- Harness must emit results incrementally + per-entry timing; buffered output
  + one hung pattern (semver) loses 15 min of work.

Lessons from the 2026-09-16 fix sequence (don't repeat):
- STALE-JAR TRAP (struck TWICE — also for :reggie-runtime:jar alone): requesting only the
  runtime jar after editing reggie-codegen sources can be an up-to-date NO-OP (648ms BUILD
  SUCCESSFUL) — the fat runtime jar embeds the codegen classes and did not repackage. After
  codegen changes run :reggie-codegen:compileJava explicitly (or touch the codegen source),
  and sanity-check via a changed generated-class hash. Original form: `./gradlew jar ... | grep BUILD` in an && chain does NOT
  stop execution on BUILD FAILED — grep exits 0 on match, and the following
  test/verify runs against the STALE jar, producing misleading results (bit
  twice during the fix sequence). Check exit codes explicitly; never
  pipe-build-then-run in one chain.
- Gradle test tasks are cached: `./gradlew test` returning BUILD SUCCESSFUL
  in <1s means UP-TO-DATE, not re-run. For real verification use
  `./gradlew cleanTest test`.
- git commit signing via ~/.ssh/datadog_git_commit_signing can fail
  ("agent refused operation") until the key passphrase is cached; retry after
  unlocking (user unlocked on demand 2026-09-16).
- Writing regression tests: do NOT hand-derive expectations (three separate
  test-assertion bugs from misread semantics); write JDK-differential tests
  (compute expected values from java.util.regex at runtime).
- JDK Matcher is STATEFUL: matcher.matches() then matcher.find() continues
  from the end of the previous match — use a fresh Matcher per operation in
  differential tests (ReggieMatcher is stateless per call).
- jstack sampling root-causes compile hangs; run the victim via nohup+disown
  in one tool call, sample in the next (the tool kills the process group
  when the command ends).

---

---
id: find-api-surface-sufficient
type: finding
status: confirmed
depends_on: []
supersedes: []
related: [find-replaceability-coverage]
tags: [api, compatibility, grok, spi, shadow]
applies_to: [reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/ReggieMatcher.java, reggie-runtime/src/main/java/com/datadoghq/reggie/ReggieMatcher.java]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# API surface no longer a blocker; grok SPI + shadow infra is the insertion seam

Trunk `com.datadoghq.reggie.runtime.ReggieMatcher` covers every JDK Matcher op
observed in both repos: matches/find/findFrom, match/findMatch→MatchResult
(indexed+named groups, spans), matchInto/findMatchInto (alloc-free),
replaceFirst/replaceAll (literal + Function<MatchResult,String> — covers
appendReplacement loops and results() streams), split(input[,limit]), findAll,
cursor() with appendReplacement/appendTail.

Caveat: top-level `com.datadoghq.reggie.ReggieMatcher` facade exposes ONLY
matches/find — consumers must type against runtime.ReggieMatcher.

re2j maps 1:1 (`Pattern.matcher(input).find()/matches()`, groupCount, named
groups). logs-backend grok pipeline: production runs `JdkRegexPatternSupplier`
(re2j supplier is non-production); `GrokPatternBundle` supports a SHADOW
supplier with configurable shadow_ratio + `RegexMatcherShadowActor` — A/B
validation infra already built for a `ReggieRegexPatternSupplier`. The re2j
supplier's JDK→RE2 syntax-conversion hack (`(?P<` rewrite) becomes unnecessary.
Remaining JDK-adjacent needs: Pattern.quote (keep on JDK), asPredicate adapter,
Jackson Pattern deserialization (pb YAMLInsightProvider), Map<FrameField,
Pattern> adapter type.

---

---
id: find-brace-literal-rejected
type: finding
status: confirmed
depends_on: [ev-harness-results]
supersedes: []
related: [q-fallback-syntax-gap]
tags: [p1, compat, parser, brace, mustache, syntax]
applies_to: [reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/parsing/**]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# P1: JDK-literal '}' rejected outright — even under compileAllowingFallback

JDK accepts a bare `}` (not part of a valid quantifier) as a literal; reggie
trunk throws `PatternSyntaxException: Unexpected metacharacter '}'`. Verified:
`compileAllowingFallback` does NOT rescue these — the syntax-level rejection
precedes the fallback decision (see q-fallback-syntax-gap).

13 patterns affected: profiling-backend 1 (`\{([\w.]+)}`), logs-backend 12 —
notably EVERY `{{ … }}` mustache-template parsing pattern (urlencode, is_match,
is_exact_match, #if/eval, local_time…) and `~<lambda>|~\{closure}|…`. Also bare
`}` alone.

Fix: treat `}` (and un-quantifier `{`) as literal when not a valid quantifier,
in both strict and fallback paths. No pattern-rewrite workaround exists for
consumers.

---

---
id: find-case-insensitive-viable
type: finding
status: confirmed
depends_on: [ev-harness-results]
supersedes: []
related: []
tags: [flags, case-insensitive, migration]
applies_to: [reggie-runtime/src/main/java/com/datadoghq/reggie/compat/JdkPatternCompatibility.java]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# Inline (?i) is a viable CASE_INSENSITIVE migration; JDK flag int is rejected

`JdkPatternCompatibility.toReggieFlags` maps MULTILINE/DOTALL/LITERAL but THROWS
on JDK CASE_INSENSITIVE (Unicode vs ASCII folding). All 15 unique
case-insensitive literal patterns across both repos (incl. pb
BillingMetricsSender VALID_COMMIT_SHA / REGEX_NON_EMPTY_HOST_TAG) compiled
natively via inline `(?i)` prefix with ZERO divergences on the probe set,
including non-ASCII inputs (ÄÖÜ äöü, ΣΙΣΥΦΟΣ, K U+212A, İ). One lb site uses
`IGNORE_CASE_AND_MULTILINE` (custom constant = CI|MULTILINE, Mongo query path).
UPDATE 2026-09-16 (post-fix): folding equivalence now established.
Input-side non-ASCII NEVER diverges (İ U+0130, ı U+0131, Kelvin K U+212A,
long-s ſ U+017F all verified against literals AND char classes — neither
engine folds non-ASCII input). The ONLY divergence is non-ASCII pattern
letters: Reggie folds é->[éÉ], JDK without UNICODE_CASE does not.
Consequence: JDK CASE_INSENSITIVE is safe to map for pure-ASCII patterns —
implemented in commit dbd51e3 as
JdkPatternCompatibility.toReggieFlags(pattern, flags).

---

---
id: find-divergence-budget-verified
type: finding
status: confirmed
depends_on: [ev-harness-results]
supersedes: []
related: []
tags: [correctness, differential, divergence]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# Only 7/1150 literal patterns diverge from JDK on synthetic inputs

Differential (matches/find booleans, match span, group spans+values, ~19
inputs/pattern incl. non-ASCII): 7 divergences total.
- 3 = groupCount charclass bug (find-groupcount-charclass-parens)
- 2 = NUL bug (find-nul-pattern-truncation)
- 1 = optional-group span divergence `^(?:(\d+):)?([0-9A-Za-z.~^_]+)…` — fits
  the documented 28-item fuzz divergence budget family (span-only, boolean OK)
- 1 = flag artifact (UNICODE_CHARACTER_CLASS passed to JDK but not reggie —
  not an engine divergence)

Graceful fallback worked as designed for the ~17 fallback-eligible rejects
(JavaRegexFallbackMatcher engaged). Engine correctness on production-shaped
patterns is otherwise clean.

---

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

---

---
id: find-groupcount-charclass-parens
type: finding
status: confirmed
depends_on: [ev-harness-results]
supersedes: []
related: []
tags: [p1, bug, groupcount, character-class, parser]
applies_to: [reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/**, reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/parsing/**]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# P1: groupCount counts parens inside character classes

Verified with findMatch: `[()\s]` → reggie groupCount=1 (JDK 0);
`([\[\]{}()*+?.\\^$|])` → reggie 2 (JDK 1); `[{}()\[\].+*?^$\\|]` → reggie 1
(JDK 0). Matching booleans/spans appeared correct in these cases — group
numbering/extraction is what's wrong (likely a naive paren count feeding the
capture-slot layout).

3 patterns in the consumers hit this (2 logs-backend, both String-method
split/match patterns over punctuation classes).

---

---
id: find-nul-pattern-truncation
type: finding
status: confirmed
depends_on: [ev-nul-verify]
supersedes: []
related: [q-input-side-nul]
tags: [p0, bug, nul, c-string, truncation, pattern]
applies_to: [reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/parsing/**]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# P0: NUL character truncates the pattern → matcher matches everything

`Reggie.compile("\0")` (single NUL char as pattern) yields a matcher whose
find()/matches() return TRUE on every input (pattern-side C-string truncation
semantics). `\x00` hex escape behaves the same. `\x41`/`\x{48}` are correct.

Impact sites found in consumers:
- logs-backend production: `domains/event-platform/libs/processing/processing-common/
  src/main/java/com/dd/ciapp/processors/Utils.java:9` —
  `str.replaceAll("\u0000", "")` would strip ENTIRE strings if migrated.
- profiling-backend test: `TraceProcessorIntegrationTest.java:83,112`
  `split("\0")` on proto string-cell tables.

Input-side NUL looked correct in spot checks (pattern=b over a<NUL>c=false,
a<NUL>b=true) — only pattern-side truncation confirmed; see q-input-side-nul.

---

---
id: find-re2j-dynamic-safety-gap
type: finding
status: confirmed
depends_on: [find-semver-exponential-compile, ev-harness-results]
supersedes: []
related: [find-api-surface-sufficient]
tags: [re2j, untrusted, dos, dynamic-patterns, blocker]
applies_to: [reggie-runtime/src/main/java/com/datadoghq/reggie/Reggie.java, reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/ReggieNativeCompileBudget.java, reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/ReggieCompiledPatternCompiler.java]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# re2j replacement blocked: all re2j sites are dynamic/untrusted; reggie lacks bounded compile

Every re2j call site in both repos compiles RUNTIME-SUPPLIED patterns:
- pb: `prof-viz-java/.../UserDefinedRegex.java` — request-supplied, 512-char
  cap, re2j chosen for ReDoS immunity, surfaces 400 on syntax error.
- lb (6 files): quantization rules (anchored `\A(?:…)\z`), service-resolution
  remapping, Stringer event filters, intake attachments, grok
  Re2jRegexPatternSupplier (DOTALL, non-production).

API port is trivial (wrappers/SPI), and reggie accepts a superset of RE2 syntax
(\A/\z supported on trunk, DOTALL flag supported). BUT re2j guarantees bounded
compile via program-size caps; reggie trunk has exponential-compile patterns
(find-semver-exponential-compile) → CPU-DoS vector on request-supplied input.
Today re2j is strictly safer for untrusted patterns. Reggie needs a
state/work-count compile budget with graceful rejection, default-on in
Reggie.compile(), before taking any dynamic site.

---

---
id: find-remaining-incompatibilities-resolved
type: finding
status: confirmed
depends_on: [find-fixes-complete]
supersedes: []
related: [find-divergence-budget-verified, find-unicode-property-gaps, hyp-bitstate-blowup-root, ev-final-corpus-results]
tags: [residuals, span-divergence, unicode-class, nul-audit, deadline, huge-charset]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# All 5 residual incompatibilities from find-fixes-complete RESOLVED (commits 60fda73..2fc44cc)

## 1. Optional-group span divergence — 60fda73
TWO bugs in the same family (backtrack must leave an unwound capture unmatched):
- DETERMINISTIC_CHAIN_BYTECODE: emitCapture writes capStart unconditionally at entry, capEnd
  at completion; the OPT skip path and ALT_CHAIN downstreamFail restored pos but not capture
  slots. Fix: emitSaveCaptures/emitRestoreCaptures snapshot every group slot before the
  with-path, restore at every unwind point (LIT_ALT/LOOP_ALT safe by structure).
- Tagged TDFA (DFA_UNROLLED_WITH_GROUPS): B17 routing guard (PatternAnalyzer
  hasNfaBypassCharsetOverlap) diverts to PikeVM when a bypass-able group's body charset
  overlaps the bypass path (even disjoint-body sequential optionals!) OR the enter marker is
  reachable via a consuming path (dfa.isCaptureAmbiguous() only samples the start closure +
  accepting targets, so head shapes like x(?:(a):)?b were reported unambiguous while
  state-entry group actions recorded stale starts). Alternation bypasses (b|(b)) stay on the
  TDFA (C2.4/C2.4B handles them — pinned by routing tests).
Verification: 14,256-check differential fuzz (divergences 102→0); corpus 2→1 divergences.
KEY DEBUG LESSON: -Dreggie.debug.trace=<pattern> dumps generated bytecode (only when the
compile succeeds; too-large patterns fail before the trace stage).

## 2. UNICODE_CHARACTER_CLASS — d6e49b9
(?U) inline + ReggieFlags.UNICODE_CHARACTER_CLASS switch \w/\d/\s (+complements, in classes)
and POSIX \p{...} to their JDK Unicode definitions. Set membership DERIVED EMPIRICALLY over
the whole BMP (probe: candidate predicate vs Pattern.compile(pat, U) over 65536 code points):
- \d == Nd; \w == isAlphabetic ∪ Nd ∪ M(Mn+Mc+Me) ∪ Pc ∪ Join_Control(200C/200D) (UTS#18)
- \s == isSpaceChar ∪ [\t-\r] ∪ {NEL} — the JDK set EXCLUDES U+001C-001F (unlike the
  Unicode White_Space property)
- \p{Alpha}=isAlphabetic, Alnum=Alpha∪Nd, Lower/Upper=isLower/UpperCase, Punct=P*,
  Blank=Zs∪{\t}, Cntrl=Cc, Space=\s-set; ASCII stays ASCII
Loud rejects under (?U): \b/\B (Unicode word boundary needs every engine evaluator
mode-aware — out of scope) and \p{Graph}/\p{Print}/\p{XDigit} (JDK sets not reproduced).
(?u) UNICODE_CASE accepted as no-op (reggie folding is unconditionally Unicode).
Drive-by: pre-existing default-\s missed \x0B (vertical tab) — fixed.
Corpus: divergences 1→0 (the [^\w:\-\.\/] lb site). JdkPatternCompatibility maps the flag.

## 3. Input-side NUL audit — 542a76c
2,152 differential checks (38 patterns × 30 inputs, matches/find+span+groups/findFrom/
findAll + matchesBounded over String/StringBuilder/StringBuffer/CharBuffer all regions),
zero divergences. SWAR findFirstByte/findFirstHexDigit are XOR/range-mask based — NUL is
never a sentinel; both String coders covered (UTF-16 inputs force non-byte paths). Closed.

## 4. Total-compile deadline — b0c5615
-Dreggie.compile.totalDeadlineMs (default 10s, 0 disables; read per-compile). ThreadLocal
deadline clamped into every determinization (SubsetConstructor) so tight knobs bind; checked
between phases → fallbackOrThrow. CRITICAL DESIGN NUANCE discovered empirically: when the
deadline expires but analysis selected an NFA-backed strategy (PikeVM/BitState — add
PIKEVM_CAPTURE to isNfaBacked's gate!), the compile FINISHES: those patterns are exactly the
ones java.util.regex CANNOT match in bounded time (verified: JDK matcher hangs on
((a|b){0,256}){12} input — catastrophic backtracking), so a JDK fallback would move the DoS
from compile time to match time. 12x bomb: 15.2s → 10.03s default / 2.05s at 2s knob.

## 5. Huge-charset codegen — 2fc44cc
Root cause NOT the charset size per se: subset construction emits one DFA transition map
entry per PARTITION PIECE of a large class (~420 for \p{IsAlphabetic}); every DFA-unrolled
emitter unrolled a full range cascade PER PIECE → 68KB methods. Fix: (a) merge transitions
sharing target (+equal guard/tagOps) into one check (pieces are disjoint → merge-safe),
(b) merged sets ≥100 ranges match via static boolean[65536] built in <clinit> from a compact
encoded-ranges string constant (faster too). Emitter by emitter: matches (generateStateCode),
matchesAtStart, matchesBounded (bounded CharSequence guards), greedy/group-action paths,
tagOps path. Corpus: the .*[\p{IsAlphabetic}].* lb site strict-native (1110/1150, +1, zero
regressions, zero divergences).

## Final corpus state (results_after_incompatibilities.jsonl)
profiling-backend 100%/100%, logs-backend 97% explicit / 94% string-method, 1110/1150
strict-native (was 1109), DIVERGENCES: 0. Full gradle suite green after every commit.

---

---
id: find-replaceability-coverage
type: finding
status: confirmed
depends_on: [ev-harness-results]
supersedes: []
related: [find-api-surface-sufficient, find-re2j-dynamic-safety-gap]
tags: [replaceability, profiling-backend, logs-backend, coverage]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# Reggie trunk replaces 93-100% of literal regex patterns in both dd backends

Measured 2026-09-16 against reggie origin/main @ fd362c2, with both consumer
repos freshly updated (profiling-backend e3288d5956→e105e153d8, logs-backend
737f65554f85→d6c8aed5347c, both origin/prod).

Strict `Reggie.compile()` acceptance of unique literal patterns (JDK-valid):

| Repo / cohort | patterns | strict-native | site coverage |
|---|---|---|---|
| profiling-backend / Pattern API | 47 | 46 (97%) | 97% |
| profiling-backend / String methods | 31 | 31 (100%) | 100% |
| logs-backend / Pattern API | 549 | 512 (93%) | 95% |
| logs-backend / String methods | 495 valid | 492 (98%) | 97% |

28 logs-backend String-method "patterns" are textual-scan false positives
(glob matchers `*SA*`, Windows paths, `$1{_}`) — not valid JDK regex, no action.

Rejections split: ~13 brace-literal `}`, ~7 Unicode property classes, ~17
graceful fallback-eligible (alternation-priority 6, anchor-dilution 4,
nullable-capture 4, lazy 1, anchor-in-quantifier 1, compound lookaround 1,
method-too-large). Plus 1 semver hang (see find-semver-exponential-compile).

## Verdict
profiling-backend feasible now (post P0/P1 fixes); logs-backend feasible with
`\p{…}` class support added. Dynamic sites (8 pb + 176 lb explicit) blocked on
compile-budget guarantee.

---

---
id: find-semver-exponential-compile
type: finding
status: confirmed
depends_on: [ev-scale-timings]
supersedes: []
related: [hyp-bitstate-blowup-root, find-re2j-dynamic-safety-gap]
tags: [p0, bug, bitstate, quantifier, compile, dos, semver]
applies_to: [reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/**, reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/ReggieNativeCompileBudget.java, reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/ReggieCompiledPatternCompiler.java]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# P0: bounded quantifiers {n,m} cause exponential BitState compile (hang)

The canonical semver pattern (logs-backend
`domains/rum/libs/rum-commons-domain/src/main/java/com/dd/rum/utils/SemVerParser.java`,
~300 chars, three `{0,256}`-style quantifiers + named groups) NEVER finishes
compiling under `Reggie.compile()` — killed after 10+ min; JDK compiles instantly.

Measured scaling (BitStateMatcher, synthetic semver variants, Scale.java):
bound 2→121ms, 4→19ms, 8→51ms, 16→215ms, 32→2.3s, 64→68.6s, 128/256 effectively
infinite. Exponential in the {n,m} bound.

Security angle: request-supplied patterns can nest `{n,m}` to CPU-DoS the
compiler. re2j protects via program-size caps; reggie's
`ReggieNativeCompileBudget` caps only source *length* (default 16,384 chars in
`ReggieCompiledPatternCompiler`), and plain `RuntimeCompiler.compile` applies
no budget at all.

Blocks: semver parsing site, ALL untrusted/dynamic adoption (UserDefinedRegex,
grok customer patterns) until a state/work budget with graceful
`UnsupportedPatternException` exists, enforced by default in `Reggie.compile()`.

---

---
id: find-unicode-property-gaps
type: finding
status: confirmed
depends_on: [ev-harness-results]
supersedes: []
related: []
tags: [p1, compat, unicode, posix-classes]
applies_to: [reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/parsing/**]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# P1: general Unicode property classes unsupported (only \p{L}/\p{N})

Rejected on trunk: `\p{Alnum}`, `\p{Alpha}`, `\p{ASCII}`, `\p{Cntrl}`,
`\p{Lower}`, `\p{IsAlphabetic}` (and negations, e.g. `[^\p{ASCII}]`,
`[^\p{Alnum}.]`). README documents only `\p{L}`, `\p{N}` + negations.

7 sites, all logs-backend. Ordinary backlog (README says script/name properties
not yet implemented) — these POSIX-ish aliases are needed for full
logs-backend literal coverage. Note `UNICODE_CHARACTER_CLASS` flag (1 lb site)
also unsupported (JdkPatternCompatibility throws).

---

---
id: hyp-bitstate-blowup-root
type: hypothesis
status: confirmed
depends_on: [ev-scale-timings]
supersedes: []
related: [find-semver-exponential-compile, find-fixes-complete]
tags: [root-cause, bitstate, dfa, determinization]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# Root cause: NOT BitState itself — analysis determinization dominated

CONFIRMED via jstack sampling during hang + fix-by-fix measurement. The
"exponential BitState compile" was actually four stacked problems, none of them
the BitState codegen itself:

1. SubsetConstructor.buildDFA called flattenClosure(anchoredClosures) inside the
   per-(DFA-state, charset) transition loop — O(NFA states x closure size) work
   rebuilt every transition. Hoisting it: semver never-finishes -> ~850ms.
   (jstack leaf frame: SubsetConstructor.flattenClosure under
   PatternAnalyzer.doAnalyze -> buildDFA.)
2. canReachGroupExit (called from isGroupActuallyEntered <- computeTagOperations)
   ran an unmemoized closure-crawling recursion PER TRANSITION, iterating
   O(|closure| x |trans| x |closure|) per invocation. Memoized reverse BFS from
   group EXIT markers: 165s -> ~26s on the 12x (a|b){0,256} bomb.
3. ThompsonBuilder.buildCountedQuantifier unrolls nested {n,m} copies
   exponentially (8^20 states for 20x{0,8}) — OOM before any budget sees it.
   Fixed with the NFA state cap (100K was too low — semver legitimately builds
   ~0.3-0.6M states; 1M default verified under -Xmx512m).
4. Residual: per-state work is still quadratic up to the 10K DFA-state cap;
   the work budget + wall-clock deadline bound it (12x bomb ~10-15s, correct).

User's local WIP branch (fix/bitstate-findfrom-ws-run-skip, modified
BitStateBytecodeGenerator) does NOT overlap these findings — they are all in
SubsetConstructor/ThompsonBuilder/parser, untouched by that branch.

---

---
id: q-fallback-syntax-gap
type: question
status: open
depends_on: [find-brace-literal-rejected]
supersedes: []
related: []
tags: [fallback, design, syntax, parser]
applies_to: [reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/parsing/**, reggie-runtime/src/main/java/com/datadoghq/reggie/Reggie.java]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# Why doesn't compileAllowingFallback rescue syntax-level rejections?

`compileAllowingFallback` rescues UnsupportedPatternException (semantic
rejections) but NOT reggie's own PatternSyntaxException — yet JDK accepts those
same patterns (literal `}`). Two open design questions for reggie:
(1) should the fallback path defer to JDK parsing when reggie's parser rejects
syntax JDK accepts? (2) or is the right fix parser leniency (literal `}`)?
Either unblocks the 13 brace patterns without consumer rewrites; today there
is NO migration path for them.

---

---
id: q-huge-charset-codegen
type: question
status: resolved
depends_on: [find-fixes-complete]
supersedes: []
related: [find-unicode-property-gaps]
tags: [codegen, 64kb-limit, charset, isalphabetic]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# Can huge charsets (1000+ ranges) avoid the 64KB generated-method limit?

**RESOLVED 2026-09-16**: RESOLVED by commit 2fc44cc: transition merging (partition pieces are per-transition map entries, ~420 for IsAlphabetic) + boolean[65536] lookup tables for >=100-range merged sets. The .*[\p{IsAlphabetic}].* site is strict-native.


\p{IsAlphabetic} (~1300 ranges) compiles standalone via strict
Reggie.compile (the earlier 'too large' note was for the .*[...] shape) —
but patterns like `.*[\p{IsAlphabetic}].*` strict-reject with
"generated method too large" (68KB > 64KB JVM method cap); fallback
covers them. A bitmap/lookup-table-based charset representation in the
generated bytecode (instead of expanded switch/range comparisons) would
make huge-class patterns compile natively and match faster. Ordinary
backlog; not blocking either backend's migration.

---

---
id: q-input-side-nul
type: question
status: resolved
depends_on: [find-nul-pattern-truncation]
supersedes: []
related: []
tags: [nul, input, correctness, audit]
applies_to: [reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/swar/**, reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/**]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# Is input-side NUL fully correct in reggie (StringView/SWAR paths)?

**RESOLVED 2026-09-16**: RESOLVED (audited, no engine change needed): 2,152 differential checks zero divergences — SWAR XOR/range-mask never treats NUL as sentinel; both String coders; bounded regions over 4 CharSequence types. Pinned by InputSideNulAuditTest.


Pattern-side NUL truncation is confirmed. Input-side NUL was only spot-checked
(pattern b over "a\0b"/"a\0c" correct). reggie's SWAR/StringView code may treat
NUL as a sentinel in bulk-scan fast paths → matching past a NUL in the input
could be wrong for some strategies. Needs a focused differential (inputs with
embedded NULs × all strategies) before declaring NUL handling fixed; the fix
for the pattern side must not stop at the parser.

---

---
id: q-total-compile-deadline
type: question
status: resolved
depends_on: [find-fixes-complete]
supersedes: []
related: [find-semver-exponential-compile]
tags: [design, deadline, dos, untrusted]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# Should Reggie enforce a TOTAL-compile deadline (not per-determinization)?

**RESOLVED 2026-09-16**: RESOLVED by commit b0c5615: -Dreggie.compile.totalDeadlineMs (default 10s, 0 disables), ThreadLocal clamp into every determinization, between-phase checks; NFA-pass-through design nuance (PikeVM/BitState complete anyway — JDK fallback would move DoS to match time; JDK hangs on the 12x bomb at match time).


The 10s determinization deadline is per buildDFA call. PatternAnalyzer runs
several determinizations per compile (analysis + strategy routing + lookahead
sub-NFAs), each on a fresh SubsetConstructor with a fresh deadline — so total
compile time is bounded only by passes x 10s. The 12x (a|b){0,256} bomb still
takes ~10-15s (correct, bounded, but too slow for request-driven compile of
untrusted patterns like profiling-backend's UserDefinedRegex).

Design question: a compile-level wall-clock budget enforced in
RuntimeCompiler.compileInternal (throwing UnsupportedPatternException /
fallback on expiry) — probably as a ReggieOptions knob with a sensible
default, since consumers of untrusted patterns want ~1-2s, not 10-15s.
Follow-up API design decision, not blocking the current release.

---
