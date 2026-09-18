---
id: ev-real-input-parity
type: evidence
status: confirmed
depends_on: [ev-r2b-landed]
supersedes: []
related: [q-real-input-validation, find-backend-prod-regex-cost]
tags: [real-inputs, parity, shadow-seam, drop-in, harvest, 32a9e26]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# Real-input parity battery: 270k pairs, 0 divergences after 3 fixes (32a9e26)

USER DIRECTIVE: "proceed" — item B (real-input validation via the shadow seam).

## Method (local stand-in for the shadow mismatch metric)
Harvested 527 real log lines from logs-backend grok/processing test fixtures (633 java
files, marker-heuristic string-literal extraction; /tmp/prefilter/real-inputs.txt; committed
as reggie-runtime/src/test/resources/corpus/real-inputs.txt). Ran the 513-pattern real
matchRule corpus x real lines under the EXACT drop-in supplier contract (ReggieFlags.DOTALL
+ allowJdkFallback, matches() + every capture group vs java.util.regex) — the same predicate
RegexMatcherShadowActor compares in prod. Probe: /tmp/prefilter/RealInputParityProbe.java.

## Found (all invisible to the synthetic 6-line corpus)
1. Nullable-tail group span: ^(\w+:(?://)?)[^#]+ — group 1 "http:" instead of "http://"
   (tagged DFA writes group END at the early exit; no re-fire after the optional tail
   matched). 13 hits on real inputs. FIX: PatternAnalyzer routes tail-nullable capturing
   groups (concat spine ends with a skippable element) to PIKEVM_CAPTURE, guarded against
   the B16 nullable-content shape. Alternation-length ambiguity is NOT flagged (exit
   re-fires along consuming paths — spans correct).
2. VARIABLE_CAPTURE_BACKREF: detector admitted trailing suffix nodes the generator never
   emitted (x(\d+)y\1z -> silently dropped, false); generator also SKIPPED the
   backrefEnd==len check under trailing $ (x(\d+)y\1$ accepted "x123y123z"/"x123y123\n";
   jdk matches() is full-region — a$ on "aa\n" is false). FIX: detector declines non-empty
   suffixes (-> OPTIMIZED_NFA_WITH_BACKREFS); end check unconditional.
3. Parse-time refusals escaped allowJdkFallback: RegexParser.UnsupportedPatternException
   (variable-width lookbehind etc.) was rethrown instead of fallbackOrThrow — violated the
   zero-functional-refusals drop-in contract (the find-refusal-set-parity census measured
   engine refusals; the parse-time path was a blind spot). FIX: routed through
   fallbackOrThrow (nameMap unavailable pre-parse — JDK fallback serves groups directly).

## After
270,351 pairs, 8,179 full matches, 0 divergences, 0 refusals. Battery committed as
RealInputParityTest (runs ~1.2s in CI — the R1 prefilter makes 262k rejects cheap);
per-fix regression tests included. Box re-run flat (951us no-match, 340us matched,
controls flat) — the fixes cost nothing on the corpus.

## Shadow-seam state (the REAL-traffic half of q-real-input-validation)
- logs-backend main (d6ab332b57f2): GrokPatternBundle wires main=Caching(Jdk) (or
  prefiltered, via dd.grok-parser.use_prefiltered_literal_supplier_as_main), shadow via
  dd.grok-parser.shadow.shadow_ratio (default 0) + ShadowRegexPatternSupplier +
  RegexMatcherShadowActor (metrics: regex.matcher.shadow.count status success/mismatch/
  skipped + latency distributions). RE2J suppliers exist but are NOT wired into the bundle
  (consistent with re2j 0.00% prod CPU).
- DRAFT supplier written: doc/temp/ReggieRegexPatternSupplier.java (java-reggie worktree;
  93 lines, same package, drop-in behind the factory). Requires com.datadoghq:reggie:0.4.0
  in maven_install — Bastien Lemale's branch
  (feat_processing_add_reggieipextractionstrategy_with_shadow_mode) already wires reggie
  0.3.0 for a ReggieIpExtractionStrategy (IP-extraction surface, NOT merged in prod) —
  natural ally for the supplier PR after 0.4.0 is published.
- Real-traffic validation = flip the shadow wiring + shadow_ratio>0 in prod and watch
  regex.matcher.shadow.count status:mismatch. That is a logs-backend PR + rollout
  (service-owner decision), not doable from this repo.
