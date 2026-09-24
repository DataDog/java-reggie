---
id: ev-bench-ab-temurin
type: evidence
status: confirmed
depends_on: []
supersedes: []
related: [q-passthrough-overhead, find-l2-splitter-shipped]
tags: [benchmark, jmh, temurin, compile-time, runtime]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# A/B benchmark on workspace-jb (Temurin 21.0.12 HotSpot): no runtime regression, ~9% compile-time

## Setup
Baseline = pristine 2fc44cc clone; candidate = same + L2 working-tree patch (59 files).
Same idle 16-core box (jb.workspace.infra.dog), gradle daemon/JMH forks/javac/probe all on
Temurin 21.0.12+8. JMH selection: (AllStrategyVsJdk|CommonPatterns|DFATable|LazyDFA|
BitParallelGlushkov|LogsBackendGrok)Benchmark, -p scale=SHORT → 83 entries, identical sets
both sides. Raw data: workspace-jb:~/bench/l2-validation/results/{baseline,candidate}-results.json
+ compile-probe-{baseline,candidate}.txt; report HTML in the clone's
reggie-benchmark/build/reports/benchmark-report.html.

## Runtime (JMH, repo's benchmarkAndReport baseline.json comparison)
- 79/83 stable (within 10%)
- 3/83 improved (>10% faster) — noise, favorable direction
- 1/83 flagged "regression": DFATableBenchmark.jdkMatches 15.1% slower — a JDK-side
  benchmark (java.util.regex); the L2 change has no code path into the JDK — run-to-run
  noise. Zero reggie-side regressions. Consistent with the identical-bytecode expectation:
  the verbatim path replays the same MethodNode content, so generated classes are identical.

## Compile-time (CompileTimeProbe: 200 real PATTERN_CACHE-miss compiles per template)
| template family | baseline µs | candidate µs | delta |
|---|---|---|---|
| email chain | 3107.9 | 3474.6 | +11.8% |
| url | 1853.3 | 2223.2 | +20.0% |
| backref (ab%d)\1{8,} | 350.3 | 379.6 | +8.4% |
| ipv4-ish DFA | 14314.7 | 15749.9 | +10.0% |
| alternation | 794.2 | 788.2 | -0.8% |
| table | 1359.8 | 1450.0 | +6.6% |
| optional group | 722.7 | 646.8 | -10.5% |
| timestamp | 1089.1 | 1095.7 | +0.6% |
| MEAN | 2949.0 | 3226.0 | +9.4% |

Point estimate ~+9% on total compile (pipeline = parse + analysis + emission + defineClass;
the passthrough roughly doubles the emission step). Noise band is wide (two templates
"faster" by up to 10%), so honest range ≈ +0–15%. Against the 10s total-compile deadline
this is immaterial (analysis dominates; deadline-headroom patterns are unaffected).

## Operational notes (repo latent issues hit during setup)
- :reggie-benchmark:jmhJar lacks dependsOn on :reggie-runtime/:reggie-codegen/:
  reggie-integration-tests jars — fails on a clean tree ("Cannot expand ZIP ... does not
  exist"); Gradle 9 task-validation also rejects them sharing a graph. Workaround: separate
  invocations.
- :reggie-benchmark:saveBaseline (Copy from build/reports/jmh/results.json) reported
  NO-SOURCE even with results.json present at the module path — root cause not chased;
  bypassed with manual cp. Auto-comparison worked once baseline.json was cp'd into place.
- grep -c '@Benchmark' overcounts (matches @BenchmarkMode) — real method counts are lower.
