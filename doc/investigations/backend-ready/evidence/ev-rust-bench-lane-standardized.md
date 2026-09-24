---
id: ev-rust-bench-lane-standardized
type: evidence
status: confirmed
depends_on: [find-rust-engine-crossover]
supersedes: []
related: [hyp-unanchored-find-prefilter]
tags: [benchmark, 4th-lane, jni, rust-regex, real-corpus, buildRustEngine, 60db93c]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# Rust regex standardized as the 4th benchmark lane (60db93c)

## Reasoning chain
User: "let's standardize the rust matcher as a part of benchmarks". Shipped in java-reggie
60db93c (feat/dd_backend_check, pushed through 5210bac):
- reggie-benchmark/rust-regex-engine: JNI cdylib over the regex crate (NFA limit 256MB);
  GetStringUTFChars marshal DELIBERATELY in the timed path (production FFI cost). JNI not
  FFM because repo toolchain = Java 21 (FFM preview-only).
- engines.RustRegexEngine: compile() scan semantics / compileFullMatch() JDK-matches parity
  (\A(?:pat)\z); graceful unavailability; smoke main() = 3 checks incl. real-semver refusal.
- gradle :reggie-benchmark:buildRustEngine (cargo); cargo target/ gitignored.
- MatchOperationBenchmark: rust lanes (full-match parity).
- RealCorpusScanBenchmark: 513 real logs-backend patterns committed as
  corpus/logs-backend-patterns.tsv; MATCH/NOMATCH sweep split; setup reproduces the smoke
  EXACTLY (513/491, refusals jdk=0/reggie=10/rust=12; rust no-match ~303ns/pair).
Role: the acceptance gate for R1/R2 — the NOMATCH sweeps move when the prefilter gaps close
and make regressions CI-visible. JDK 24+ native-access warning: --enable-native-access.


MATCH-MODE SIMPLE-PATTERNS (workspace-jb, MatchOperationBenchmark, post-R1 build):
phone lane thrpt: reggie 257,579 ops/ms vs jdk 5,802 vs re2j 1,456 vs rust 6,104 —
reggie 42x rust / 44x jdk on codegen-specialized full-match lanes. 'reggie ~ rust'
holds only for the hard scan shapes; simple patterns are reggie's home field.
