---
id: ev-backend-usage-survey
type: evidence
status: confirmed
depends_on: [find-fallback-optin-refuse, ev-corpus-refuse-census]
supersedes: []
related: [q-prod-readiness, find-logs-backend-re2j-migration]
tags: [backend-services, jdk-regex, re2j, usage-survey, census, profiling-backend, logs-backend, migration]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# JDK-regex + RE2J usage survey of the two target backend services (+ real-corpus census)

## Method
Full-tree recursive greps (build/target/bazel/test/benchmark excluded) over
~/dd/profiling-backend (1,419 java files) and ~/dd/logs-backend (29,170 java files);
literal Pattern.compile sites extracted with a Java-string-unescape parser; census via
CensusProbe (default Reggie.compile, NO fallback) against a5ef0fb jars. Extractor+census:
/tmp/census/{backend-pats.json,backend.tsv,backend.out}.

## RE2J usage — profiling-backend
ONE site: prof-viz-java UserDefinedRegex — user-supplied regex from HTTP requests, compiled
and executed by RE2J *specifically for ReDoS immunity* ("immune to catastrophic
backtracking"): 512-char cap, RE2-only syntax (no lookaround/backrefs), PatternSyntaxException
→ HTTP 400; find/matches on the pre2j Pattern. 9 files reference it. This is a 1:1 match for
reggie's design point — refuse-by-default + bounded compile + linear native engines — with
the bonus that reggie natively supports the lookaround/backrefs RE2J rejects (both linear
on PikeVM/BitState routes).

## RE2J usage — logs-backend (4 BUILD.bazel deps: quantization, processing-parsing,
## processing-common, intake-backend/common)
- domains/apm/libs/quantization QuantizationRules: org-configurable quantization rules,
  re2j Pattern per rule; compile-duration metric (apm_processing.quantization_rules.compile.
  duration); rules that fail compile are DROPPED from a scope's compiled set.
- processing-common: RenamingRuleEvaluator (service_resolution renaming rules) and
  StringerRuleEventFilter — re2j Pattern + PatternSyntaxException.
- processing-parsing grok/regex: a 13-class engine-pluggable supplier layer (see
  find-logs-backend-re2j-migration).
- event-jobs-logs InspectGrokParserMatchRulesWithRE2Job: fleet-wide compatibility audit —
  "load all track pipelines and try to parse all grok matchRules with RE2-J", with
  event_jobs_logs.inspect_re2j.{success,failed}.count metrics.

## JDK java.util.regex usage (main code)
| service | files w/ import | Pattern.compile sites | literal | dynamic |
|---|---|---|---|---|
| profiling-backend | 47 | 51 | 53 (34 files) | 3 |
| logs-backend | 355 | 666 | 550 (232 files) | 84 |
Implicit per-call regex (String.matches/replaceAll/split — each call compiles a fresh JDK
Pattern): prof 55/26/49, logs 485/245/767. ~800 total call sites = the JIT-specialization
win class for reggie migration.
Dynamic compile sites (84 logs + 3 prof — config/user-supplied patterns) are the
DoS-relevant class: exactly what reggie's 10s deadline + refuse-by-default are designed
for; census cannot cover runtime values — note for migration testing.

## Census on the REAL backend corpus (563 unique literal patterns: 511 logs, 50 prof, 2 shared)
**552 native (98.0%), 11 refused (2.0%).** Compile: median 587µs, p90 1.8ms.
Refusal taxonomy: 4 alternation-priority conflicts (DFA longest vs JDK first-alternative);
2 anchor-dilution; 1 nullable-capture divergence guard; 1 anchor-in-quantifier;
1 lookbehind combination `(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])`; 1 capture-ambiguous
group spans; 1 lazy quantifier (prof). All are honest divergence refusals — patterns where
today the services get JDK semantics; each needs a per-pattern migration decision
(rewrite, opt-in JDK fallback, or accept reggie's semantics where provably equivalent).
Slowest compile: semver bounded-quantifier pattern `^(?<major>0|[1-9]\d{0,256})\.…{0,256}…`
→ BitStateMatcher in 2.11s (4.7x inside the 10s deadline; once per pattern under cache;
same {0,256}-unrolling family as find-openj9-deadline-rejection, on HotSpot well within
envelope).

## Bottom line for q-prod-readiness
Real-corpus refuse-rate = 2.0% (11/563, triageable one-by-one) vs the 14.1% industry-
corpus rate; compile-cost headroom >= 4.7x worst-case; service-shaped RE2J sites map
directly onto reggie's safety model; logs-backend already has the pluggable engine seam
reggie needs (no service redesign required).
