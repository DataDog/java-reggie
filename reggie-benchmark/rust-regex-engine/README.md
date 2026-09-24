# Rust regex engine (benchmark lane)

JNI shim over the Rust [`regex`](https://crates.io/crates/regex) crate — the
regex-automata meta engine (hybrid lazy DFA + SIMD literal prefilters), the same
engine family as the production `dd_sds` scanner — exposed to the reggie benchmark
suite as the fourth comparison engine: `java.util.regex` / reggie / RE2J / rust.

- **Build**: `./gradlew :reggie-benchmark:buildRustEngine` (or `cargo build --release`
  here; requires cargo). Without the library the rust benchmark lanes fail fast
  with a descriptive error; other engines are unaffected.
- **Java binding**: `com.datadoghq.reggie.benchmark.engines.RustRegexEngine` —
  `compile()` (scan semantics) and `compileFullMatch()` (JDK `matches()` parity),
  `isMatch()` / `find()` / `close()`.
- **Marshal cost included**: JNI `GetStringUTFChars` runs per call — the same
  per-event UTF-8 boundary cost the production FFI engine pays. Do not cache raw
  pointers to "optimize" benchmarks.
- **Refusals are signal**: patterns the engine cannot compile (syntax, or NFA size
  limit — raised to 256MB; the bounded-quantifier `{0,256}` families exceed it) throw
  `PatternUnsupportedException`. On the real logs-backend corpus (491 common patterns)
  rust refuses 12 vs reggie's 10 — coverage is part of what this lane measures.
- **Smoke validation**: `java -cp ... com.datadoghq.reggie.benchmark.engines.RustRegexEngine`
  (3 checks incl. the counted-quantifier refusal).

Benchmarks using this lane: `MatchOperationBenchmark` (full-match parity lane),
`RealCorpusScanBenchmark` (real 513-pattern corpus sweep, match/no-match modes).

On JDK 24+ the JVM prints a native-access warning for `System.load`; pass
`--enable-native-access=ALL-UNNAMED` to silence it (not needed on the repo's
Java 21 toolchain).
