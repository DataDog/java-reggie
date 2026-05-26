# Sub-2× perf candidates (Reggie vs JDK)

Throughput sweep across the benchmark suite on `fix/anchor-semantics`
(post `3fdeee5`). Ratio = Reggie throughput / JDK throughput on the
same micro. Ratios are JMH single-fork, 1×warmup, 2×measurement
(noisy — error bars omitted, magnitudes meant for triage prioritization
not for shipping numbers).

## Critical: Reggie 50-100× slower than JDK

| Class | Bench | Reggie | JDK | Ratio | Notes |
|---|---|---|---|---|---|
| ComplexNFABenchmark | ComplexEmailLongMatch | 0.02 | 2.31 | **0.01×** | Strategy: `HYBRID_DFA_LOOKAHEAD` |
| ComplexNFABenchmark | ComplexEmailNoMatch | 0.20 | 12.37 | **0.02×** | same |
| ComplexNFABenchmark | ComplexEmailMatch | 0.08 | 4.31 | **0.02×** | same |

Pattern: `(?=.{1,64}@)[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}`

The HYBRID_DFA_LOOKAHEAD strategy degrades catastrophically on this
pattern. JDK's NFA backtracker handles it competently. Investigation
target: `PatternAnalyzer.analyzeAndRecommend()` may be picking the
hybrid path for a pattern shape where the recursive-descent or
optimized-NFA path would be faster. Profile the hybrid implementation
on this exact pattern.

## Anchor-fix sites — slightly slower than JDK

| Class | Bench | Reggie | JDK | Ratio | Notes |
|---|---|---|---|---|---|
| AnchorPlacementBenchmark | AtEndConcat_match | 9.32 | 11.79 | 0.79× | `xyz#$`, DFA_UNROLLED |
| AnchorPlacementBenchmark | AlternationMixed_endBranch | 29.85 | 33.55 | 0.89× | `^a\|z$` |
| AnchorPlacementBenchmark | AlternationMixed_startBranch | 29.74 | 33.17 | 0.90× | same |

Per-state acceptance-condition checks emitted at every accept site by
`emitAcceptanceAnchorChecks`. The check is fast (one or two compares)
but adds branches in the hot path. Likely fixable by hoisting the
length-load out of the inner state loop, or by short-circuiting when
the EnumSet is known to be `{END}` only.

## Lookbehind + backref — near-parity

| Class | Bench | Reggie | JDK | Ratio |
|---|---|---|---|---|
| ComplexNFABenchmark | LookbehindBackrefMatch | 6.16 | 6.27 | 0.98× |
| ComplexNFABenchmark | LookbehindBackrefNoMatch | 4.84 | 4.83 | 1.00× |

Pattern: `(?<=prefix)(\w+)\1(?=suffix)`. Reggie is at parity, which is
worth investigating because we'd expect a DFA-class engine to beat
JDK's backtracker. The hybrid path likely doesn't kick in for combined
lookbehind+backref patterns.

## Borderline (1.0×–2.0×) — investigate after the above

| Class | Bench | Ratio | Notes |
|---|---|---|---|
| AnchorPlacementBenchmark | UserPattern_leadingDigit | 1.04× | `$[^a-zA-Z0-9]\|^[0-9]` — only barely ahead of JDK |
| StringAnchorBenchmark | StringEnd_long_noNewline | 1.14× | `.*suffix\Z` matching no-newline input |
| BackreferenceBenchmark | SelfRefFourGroupsNoMatch | 1.19× | Self-referencing backreference, no match |
| AnchorPlacementBenchmark | TrailingZeros_noMatch | 1.30× | `\.?0+$` against non-matching input |
| GroupExtractionBenchmark | EmailGroups | 1.32× | Group-capture path |
| NamedGroupExtractionBenchmark | LogSpansByName | 1.35× | Named group, find by name |
| BackreferenceBenchmark | SelfRefFourGroupsMatch4 | 1.64× | Self-referencing backreference, match |

Most of these are group-capture or backreference workloads — areas
where Reggie's overhead for setting up the tagged-DFA tag arrays
shows. Reasonable to leave as-is unless a consumer reports a hot path.

## Verification methodology

```
./gradlew :reggie-benchmark:jmh \
  -Pjmh.args="(AnchorPlacement|StringAnchor|MatchOperation|FindOperation|Replacement|StateExplosion|BranchReset|ComplexNFA|NamedGroupExtraction|GroupExtraction|Backreference|Conditional|Assertion).*Benchmark -wi 1 -i 2 -f 1 -tu us -bm thrpt"
python3 /tmp/pairup_benchmarks.py reggie-benchmark/build/reports/jmh/results.json 2.0
```

The pair-up script lives at `/tmp/pairup_benchmarks.py` in this
session; should be checked into `reggie-benchmark/scripts/` if
adopted.
