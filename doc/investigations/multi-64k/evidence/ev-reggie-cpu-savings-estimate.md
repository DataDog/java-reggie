# ev-reggie-cpu-savings-estimate: measured prod regex CPU + $ savings (2026-09-17)

METHODOLOGY: Dogfood (us1 prod org) continuous-profiler Java CPU flamegraphs pulled via
the dodo-cli flamegraph aggregate endpoint (POST app.datadoghq.com/api/ui/ide/profiles/
flamegraph, 2h window, env:prod, family java, cpu-time; 321,656 / 286,553 / 70,724
profiles aggregated for logs-processing / apm-processing / prof-analyzer). Regex share =
sum of java.util.regex.* self-time / profile total. Cost = CCM all.cost service-
allocated, 29-day daily avg. CPU = container.cpu.usage fleet sum 24h avg. Full write-up:
Datadog notebook 15576172 (https://app.datadoghq.com/notebook/15576172).

MEASURED:
- logs-processing: $20,227/day ($7.38M/yr), 32,790 cores used (64,963 requested).
  java.util.regex self = 5.60% of CPU (~1,836 cores). Pattern.compile = 0.003% (pure
  match-time). com.google.re2j = 0.00% -> the grok RE2J migration has NOT landed in
  prod; today the entire share is JDK-replaceable. Driver: ~100% grok (11.47 of 11.95%
  cumulative under Matcher entries attributed to grok/fsmatic call sites; top frames
  Pattern$Branch/BmpCharProperty/GroupHead/GroupTail/Curly = backtracking-node profile).
  +0.55% InterruptibleCharSequence.charAt regex-input plumbing on top.
- prof-analyzer: $2,977/day ($1.09M/yr), 5,783 cores. java.util.regex = 1.00%, driver
  com.datadog.profiling (JFR/pprof parsing), compile 0.012%.
- apm-processing: $14,902/day. java.util.regex = 0.38% only — the heavy regex there is
  RUST dd_sds/regex-automata (~11%, hybrid lazy DFA + teddy SIMD): NOT reggie-
  replaceable. Also dd.sds.Native.scan = 8.14% of logs-processing CPU (same Rust
  scanner). dd.sds.Encoder.encodeEventRecursive ~1% of apm CPU = the cross-runtime
  marshalling tax the embedded Rust runtime pays.

SAVINGS (cost x share x (1-1/f), f = reggie vs JDK from our 3-engine JMH corpus,
median 5.9x, conservative 3x):
- logs-processing: $413K/yr regex spend -> $275-344K/yr saved (f=3-6)
- prof-analyzer: $7.3-9.0K/yr; apm-processing: $13.8-17.2K/yr (marginal)
- TOTAL: ~$296-370K/yr, ~89% of it in logs-processing.
- Rule of thumb: every 1% of logs-processing CPU in JDK regex = $49-62K/yr.

CAVEATS (in notebook): self-time attribution conservative (5.78% Unknown Java excluded);
savings realize only if HPA scales replicas down; f measured on repo corpus not grok mix
(shadow infra can A/B first); if RE2J migration lands first, grok delta shrinks to
parity (reggie ~ re2j 0.98-1.2x realistic).

RUST-vs-REGGIE verdict (user question): head-to-head on scan-heavy literal matching
regex-automata likely 1.5-3x FASTER than reggie's NFA/onepass engines (lazy DFA + SIMD
prefilters; RE2-class). reggie competitive/wins on: capture-extracting rules (lazy DFA
cannot capture), bounded {n,m} families (counted lowering vs NFA expansion), short
inputs (no FFI/encode tax). Consolidating dd_sds on reggie = architectural case, not
economic.

FOLLOW-UPS: (1) A/B reggie vs JDK on real grok corpus via shadow seam
(ReggieRegexPatternSupplier); (2) decide RE2J-vs-reggie before more grok migration
effort lands; (3) optional 4th (Rust/FFI) lane in the 3-engine bench to test the dd_sds
crossover empirically. dodo-cli temp fetcher (cmd/profshare) used and REMOVED per user
directive — never commit one-off fetchers into dodo-cli.

## FOLLOW-UP: real-corpus 3-engine smoke (reggie vs JDK vs Rust regex) — 2026-09-17

USER DIRECTIVE: smoke-test against "the rust thing". Built /tmp/rustbench (cdylib shim
over regex 1.13.1 = regex-automata meta engine, dd_sds family; C ABI + Java FFM incl.
per-call UTF-8 marshalling) and raced it on the REAL corpus: 513 logs-backend pattern
literals x 6 realistic log lines, per-(pattern,input) mode split MATCH/NOMATCH by JDK
boolean (RustSmoke3.java; /tmp/census/logs-pats.tsv from backend-pats.json census).

COVERAGE: jdk 0 refusals; reggie 10 (2.0%); rust 12 (2.3%). ZERO semantic divergences
across 2,946 pairs / 491 common patterns. Rust counted-quantifier wall CONFIRMED on
real-family pattern: semver {0,256} exceeds default 10MB NFA limit AND 256MB raised
limit; {0,128} builds in 394ms; {0,192} fails at 256MB. (tests/bounds_probe.rs)

RESULTS (totals ns/op summed over pairs; medians per pair):
- MATCH mode (327 pairs, grok-parsing shape where prod 5.6% CPU lives):
  jdk 197.6us, reggie 179.6us (0.91x), rust 15.6us (0.08x).
  reggie/jdk median 0.48x (~2.1x faster); reggie/rust median 2.77x (rust ~3x faster).
- NOMATCH mode (2,619 pairs, rule-filter scans):
  jdk 11.21ms, reggie 5.22ms (0.47x), rust 97us (0.01x).
  reggie/jdk median 0.68x; reggie/rust median 4.93x; totals 54x (rust ahead).
- Worst reggie shapes: .*-prefixed unanchored patterns, e.g. '(.*) \((.*)\)':
  jdk 213us, reggie 1.7ms(!), rust 316ns — no literal prefilter + per-start-position
  scanning; rust does one lazy-DFA pass + memchr prefilter.

CONSEQUENCES:
1. Rust verdict MEASURED (replaces my 1.5-3x guess): rust ~3-5x faster than reggie at
   median on real logs-backend mix, 11-54x on totals; survives FFI marshalling cost.
   dd_sds replacement by reggie would RAISE CPU. Architectural-only case confirmed.
2. f for the $ model REVISED: 3-6x was bench-corpus optimistic; real-mix matched-pairs
   median reggie/jdk ~0.48 -> honest central f ~= 2x. logs-processing savings central
   estimate revised: 5.6% x $7.38M x 0.5 = ~$210K/yr (was $275-344K). MATCH-mode floor
   f=2, NOMATCH floor f~2 (0.47x total) -> range ~$207-280K/yr.
3. ENGINEERING GAP for reggie surfaced by real corpus: (a) literal prefilter for
   unanchored find() (memchr required-literal scan like rust/JDK BnM) — collapses
   NOMATCH totals; (b) single-pass unanchored scan instead of per-start-position for
   .* -prefix shapes. Closing these moves reggie from ~2x to ~5x vs JDK fleet-wide
   and is worth ~$100K/yr on its own at logs-processing scale.
4. Engine-family split confirmed empirically: rust for scan-style filtering; reggie
   for capture-extracting + bounded-quantifier families (rust cannot compile those).
Notebook 15576172 updated with a smoke-test cell (2026-09-17).
