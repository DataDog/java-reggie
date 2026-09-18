---
id: q-prod-readiness
type: question
status: answered
depends_on: [find-deadline-coverage-gaps, find-openj9-deadline-rejection, find-no-overflow-trigger-today, ev-validation-green, ev-bench-ab-temurin, ev-deadline-fix-verification]
supersedes: []
related: []
tags: [readiness, production, adoption, jdk-regex, re2j, backend-services, verdict]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# "Are we ready to replace JDK regex and RE2J in backend services?"

## ANSWER (2026-09-17, REVISED after user corrections): NOT YET, but closer than the first
## pass said — 3 axes now stand confirmed (incl. code-verified refuse-by-default), 2 census
## items remain (fleet JVM flavors, service-corpus refuse-rate/routing). The perf verdict is
## attested and the full 3-engine harness is in-repo; a current recorded run is the only
## evidence-formality left on that axis.

## Axis-by-axis verdict from this investigation's records (all on feat/dd_backend_check @ a5ef0fb)

### CONFIRMED READY
1. **Compile-time DoS-boundedness** (the backend P0): every compile route is now bounded —
   10s total deadline with both verified holes closed (find-deadline-coverage-gaps RESOLVED
   by a5ef0fb: emission budget 65,535 insns/method + charged bypass BFS + compile-scope OOM
   catch). Numbers: lookahead×1000 6g-OOM/4.2s → 286ms graceful; calt×6000 22.3s → 2.4s
   native BitState (×12000 → 3.6s, scale-independent).
2. **Correctness/parity infrastructure**: 3,152 runtime tests + integration corpus built from
   the RE2 and PCRE test suites (capture positions byte-exact) + fuzz oracle with
   **java.util.regex as the semantic oracle** — i.e. parity target = JDK semantics, tested
   against industry suites (ev-validation-green). All green on the final tree.
3. **Degradation semantics**: no input kills the process; too-large/deadline/OOM surfaces as
   JDK fallback (with allowJdkFallback) or explicit UnsupportedPatternException (service can
   route). No real pattern family overflows methods today (find-no-overflow-trigger-today);
   compile cost on realistic templates ~1-14ms (ev-bench-ab-temurin probe), +0% vs baseline
   after the L2 drop.

### CORRECTED 2026-09-17 (user-supplied facts, then code/README-verified — supersedes the
### original items 4 and 5, which contained two factual errors — see bottom)
4. **Performance vs JDK & RE2J** — the original "harness exists but never ran" was
   understated and "no RE2J benchmark" was WRONG: 9 benchmark classes run the full
   three-engine comparison (Match/Find/GroupExtraction/NamedGroupExtraction/Split/
   MultilineDFA/StateExplosion/IastRegexp/IastTokenizerDrain — reggie vs java.util.regex vs
   com.google.re2j:1.8), wired via `./gradlew :reggie-benchmark:benchmarkAndReport` (JMH +
   HTML report + baseline compare). A FULL RUN EXISTS (user: "we already ran full bench
   against jdk and re2j; reggie is beating both by large margin"); README.md:136 asserts the
   same. Remaining formality: README's own caveat — "the only benchmark run on file predates
   the most recent performance work (6841723, 5db1866) and is not committed" — so for the
   cairn record, re-run benchmarkAndReport on Temurin and file the numbers as an evidence
   node. Attested-verdict stands in the meantime.
5. **RE2J safety** — the original "zero evidence base + silent backtracking regression" was
   WRONG twice over: (a) the evidence base exists (item 4); (b) degradation is NOT
   backtracking-by-default — fallback to JDK is OPT-IN; default refuses
   (UnsupportedPatternException; code-verified, see find-fallback-optin-refuse). An
   RE2-compatible pattern reggie declines is an explicit error the service must route, never
   a silent exponential matcher. Residual, honest gap = REFUSE rate: for each fleet RE2J
   pattern, does reggie compile it natively? (Census item 1. PikeVM terminal fallback remains
   the optional hardening that shrinks refuse-rate without weakening the linear guarantee.)
   Backref patterns: reggie backtracks natively for them — outside RE2J's feature set
   anyway (no parity loss, JDK-class exposure only).

### Methodology lesson recorded (do not repeat)
Both original errors came from sloppy verification: (i) `grep … | head -3` TRUNCATED the
re2j hit list to the 3 HTMLReporter lines → false "no RE2J benchmark in repo"; (ii) the
opt-in default was assumed from the fallback's existence, not read. User corrections
2026-09-17: bench was run (reggie wins large); fallback is opt-in / refuse-by-default.
6. **Deployment constraint (cairn-confirmed)**: find-openj9-deadline-rejection — on
   J9-derived JVMs, patterns that compile fine on HotSpot blow the 10s deadline and silently
   fall back. Fleet JVM-flavor census required before rollout; if J9-derivatives are in the
   fleet, either raise their deadline or exclude them from phase 1.
7. **Service-corpus validation**: all DoS/safety verification is synthetic-shape +
   8-template-families. No recorded run of the actual backend-service pattern set (compile
   success rate, strategy routing census, fallback rate, metaspace churn under PATTERN_CACHE
   turnover).

## Recommended sequence — EXECUTION 2026-09-17 (updated same day)
0. RESOLVED — fleet JVM flavor: OpenJ9 EXCLUDED from backend replacement targets (user
   decision 2026-09-17) — the OpenJ9 cliff is out of scope for this rollout.
0b. DONE — real backend-corpus survey + census (ev-backend-usage-survey): 563 unique
   service patterns, 98.0% compile natively, 11 triageable refusals, worst compile 2.1s
   (4.7x deadline headroom); RE2J sites identified in both services with existing
   engine-pluggable insertion seams (find-logs-backend-re2j-migration).
## Recommended sequence — EXECUTION STARTED 2026-09-17
1. DONE — census (ev-corpus-refuse-census): industry corpora 310/361 native (refusals =
   RE2J-inexpressible features + honest divergence guards); IAST service set 13/16, all 3
   refusals = pure (?P<NAME> syntax flavor with a mechanical cure; SQL/obfuscator
   heavyweights all native; max compile 78ms = 128x deadline headroom.
2. DONE — 3-engine JMH verdict (ev-3engine-bench-current): reggie faster in 64/64 vs JDK
   (median 5.9x, max 1000x) and 62/62 vs RE2J (median 46.5x, max 1000x), incl. JDK drain
   137ms/op LDAP backtracking vs bounded reggie. CAVEAT carve-out: bounded-quantifier
   unrolling family (semver, 1/563 real patterns) is a BOTH-axes regression vs JDK —
   find-bounded-quantifier-regression; keep on audited JDK until counter-based codegen
   exists (routing refuted empirically: PikeVM 125-402x worse).
3. ANSWERED — degradation policy (q-2pct-fallback-policy): current services refuse/drop
   on compile failure at every site; reggie default maps 1:1; static-literal refusals =
   explicit per-pattern decisions (rewrite / audited JDK site / file gap); dynamic patterns
   never get JDK fallback. Scope expanded per user: JDK regex replacement in scope too.
3. Decide RE2J-fallback policy: measure fallback rate; if >0 in corpus, PikeVM-as-terminal-
   fallback change — answers 5.
4. RESOLVED — fleet JVM flavor: OpenJ9 not needed for backend replacement (user, 2026-09-17).
Remaining before YES: confirm grok production engine wiring with logs-backend owners.
(Resolved since: bench evidence filed — ev-3engine-bench-current; 11 refusals triaged —
ev-refused-triage; semver family FIXED — 8e4750c counted-loop lowering, no longer a
carve-out.)
