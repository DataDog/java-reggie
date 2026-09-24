# ev-rust-engine-bench-standardized: Rust regex as the 4th benchmark lane (2026-09-17, 60db93c)

USER DIRECTIVE: "let's standardize the rust matcher as a part of benchmarks".

WHAT SHIPPED (commit 60db93c, branch feat/dd_backend_check):
1. reggie-benchmark/rust-regex-engine — JNI cdylib over the Rust regex crate
   (regex-automata meta engine = dd_sds family; NFA size limit raised to 256MB so
   refusals reflect true limits). JNI GetStringUTFChars marshalling DELIBERATELY in
   the timed path (same per-event UTF-8 boundary cost dd.sds pays). rxCompile /
   rxIsMatch / rxFind / rxDispose; refusal -> null handle.
2. engines.RustRegexEngine adapter: compile() = scan semantics (is_match),
   compileFullMatch() = JDK matches() parity via \A(?:pat)\z; graceful
   unavailability (isAvailable()); descriptive UnavailableException; smoke main()
   (3 checks incl. counted-quantifier refusal on the REAL semver pattern).
   Repo targets Java 21 so JNI (not FFM) — FFM is preview-only on 21.
3. Gradle :reggie-benchmark:buildRustEngine (cargo). JDK 24+ prints a
   native-access warning for System.load (silence w/ --enable-native-access);
   not needed on the Java 21 toolchain.
4. MatchOperationBenchmark: rust lanes added (full-match parity) —
   rustPhoneMatch 12.1K ops/ms sanity.
5. RealCorpusScanBenchmark: the 513 real logs-backend patterns committed as
   corpus/logs-backend-patterns.tsv (from the readiness census, escaped TSV);
   sweeps jdk/reggie/rust over 6 realistic log lines with MATCH/NOMATCH mode
   split (the permanent version of the 2026-09-17 smoke). SETUP REPRODUCES THE
   SMOKE EXACTLY: 513 loaded, 491 common, refusals jdk=0/reggie=10/rust=12;
   rust no-match sweep 793.7us/2619 pairs ~ 303ns/pair.
   Cargo target/ gitignored; no artifacts staged.

WHY THIS LANE MATTERS (from ev-reggie-cpu-savings-estimate follow-up): the
real-mix smoke showed the no-match scan is where reggie's literal-prefilter gap
lives (reggie/rust median 4.9x NOMATCH vs 2.8x MATCH) — this benchmark makes
that regression-visible in every future run, i.e. it is the acceptance gate for
roadmap items R1 (unanchored find() literal prefilter) and R2 (single-pass .*
scanning).

KNOWN HONEST GAPS:
- The R1/R2 projection from the pre-roadmap analysis was INVALID (my quick
  literal extractor returned 0/513 prefilterable — obviously broken given
  patterns like '\S+ successfully created user "..."'; needs a sound extractor
  — same class of analysis RE2 does — before quoting any projected f).
- Benchmark inputs are synthetic-but-realistic lines; ratios per mode are the
  signal, absolutes indicative.
