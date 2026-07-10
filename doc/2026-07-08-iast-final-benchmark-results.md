# IAST Benchmark Results — Post Fast-Reject Fix (2026-07-08)

Final benchmark round after landing:
- `78440bb` / `68b09df` — wire `BitStateMatcher` fast-reject filters (single-char `indexOf`
  prefilter + shared `RejectDfaFactory` reject-DFA), pinned by
  `BitStateFastRejectRegressionTest`.
- `f995a40` — fix mislabeled `IastRegexpBenchmark.MYSQL_NO_MATCH` fixture (was a genuine match
  against `SQL_MYSQL`'s `\b\d+` branch via the `"10"` in `"LIMIT 10"`); also fills the
  `SQL_MYSQL` gap in `BitStateFastRejectRegressionTest`.

Raw JMH JSON output is committed alongside this doc:
- [`perf-results/2026-07-08-iast-regexp-benchmark.json`](perf-results/2026-07-08-iast-regexp-benchmark.json)
- [`perf-results/2026-07-08-iast-drain-benchmark.json`](perf-results/2026-07-08-iast-drain-benchmark.json)

## Methodology

```
./gradlew :reggie-benchmark:jmh -Pjmh.args="IastRegexpBenchmark -wi 3 -i 5 -f 2"
./gradlew :reggie-benchmark:jmh -Pjmh.args="IastTokenizerDrainBenchmark -wi 3 -i 5 -f 2"
```

3 warmup iterations, 5 measurement iterations, 2 JVM forks, 1s each — roughly 2x the class
defaults for confidence. JDK 21.0.10 (Zulu), macOS/arm64, single-threaded. `IastRegexpBenchmark`
is `Throughput` mode (ops/ms, higher is better); `IastTokenizerDrainBenchmark` is `AverageTime`
mode (µs/op, lower is better).

## IastRegexpBenchmark (throughput, ops/ms)

| case | reggie | jdk | re2j | reggie/jdk | reggie/re2j |
|---|---|---|---|---|---|
| CommandCapture | 32847.6 | 21543.4 | 343.6 | 1.52x | 95.61x |
| CommandFind | 72139.6 | 21421.7 | 713.0 | 3.37x | 101.17x |
| LdapFind | 3289.6 | 17819.1 | 1152.1 | 0.18x | 2.86x |
| LdapNoMatch | 692169.8 | 80074.9 | 86059.1 | 8.64x | 8.04x |
| QueryObfuscatorFind | 2463.9 | 7973.5 | 331.7 | 0.31x | 7.43x |
| QueryObfuscatorNoMatch | 8078.1 | 280.8 | 100.7 | 28.77x | 80.19x |
| SqlAnsiFind | 390.8 | 857.6 | 246.1 | 0.46x | 1.59x |
| SqlAnsiNoMatch | 6442.4 | 627.6 | 186.2 | 10.27x | 34.61x |
| SqlMysqlFind | 292.6 | 592.3 | 173.2 | 0.49x | 1.69x |
| SqlMysqlNoMatch | 5631.4 | 498.8 | 153.5 | 11.29x | 36.69x |
| SqlPostgresqlFind | 359.2 | 786.8 | 203.8 | 0.46x | 1.76x |
| SqlPostgresqlNoMatch | 8098.9 | 655.6 | 201.2 | 12.35x | 40.25x |
| UrlAuthCapture | 2655.7 | 22306.4 | 594.2 | 0.12x | 4.47x |
| UrlAuthFind | 2721.9 | 23910.1 | 1225.0 | 0.11x | 2.22x |
| UrlNoMatch | 11297.6 | 3390.9 | 855.3 | 3.33x | 13.21x |
| UrlQueryCapture | 452.5 | 2057.4 | 380.4 | 0.22x | 1.19x |
| UrlQueryFind | 457.8 | 2029.5 | 452.7 | 0.23x | 1.01x |

**No-match cases (the fixes' target) all improved 3x–29x vs JDK, 8x–80x vs RE2J.**

**Pre-existing gaps, unrelated to this fix** — the `*Find`/`*Capture` (match) paths trail JDK on
9/17 cases (0.11x–0.49x, always still ahead of RE2J). These are the known "inherent PikeVM
simulation cost" gaps described in `doc/temp/perf-r2-embed-name-map.md` (e.g. `UrlAuthCapture`
0.12x matches the originally-reported ~-28x gap) — not introduced by, or fixed by, this work.

## IastTokenizerDrainBenchmark (average time, µs/op — lower is better)

| scenario | size | reggie | jdk | re2j | jdk/reggie | re2j/reggie |
|---|---|---|---|---|---|---|
| COMMAND_BLANK_LINES | 512 | 21.9586 | 1190.9763 | 21.0075 | 54.24x | 0.96x |
| COMMAND_BLANK_LINES | 1024 | 84.4446 | 4231.1139 | 42.3296 | 50.11x | 0.50x |
| COMMAND_BLANK_LINES | 2048 | 324.4121 | 15032.1841 | 84.9298 | 46.34x | 0.26x |
| COMMAND_SINGLE_TOKEN | 512 | 0.0250 | 0.0467 | 13.0133 | 1.87x | 520.79x |
| COMMAND_SINGLE_TOKEN | 1024 | 0.0250 | 0.0479 | 25.4487 | 1.91x | 1016.89x |
| COMMAND_SINGLE_TOKEN | 2048 | 0.0228 | 1.5826 | 52.0030 | 69.27x | 2276.02x |
| LDAP_NESTED_OPEN_EQ | 512 | 2.1147 | 37493.7454 | 39.1079 | 17730.17x | 18.49x |
| LDAP_NESTED_OPEN_EQ | 1024 | 4.2544 | 132242.2885 | 77.5531 | 31083.46x | 18.23x |
| LDAP_NESTED_OPEN_EQ | 2048 | 8.5436 | 1044377.4709 | 156.4521 | 122240.42x | 18.31x |
| LDAP_UNCLOSED_FILTER | 512 | 2.0817 | 616.0615 | 39.7308 | 295.94x | 19.09x |
| LDAP_UNCLOSED_FILTER | 1024 | 4.3157 | 1520.8030 | 79.8843 | 352.39x | 18.51x |
| LDAP_UNCLOSED_FILTER | 2048 | 8.6313 | 6007.4396 | 158.0755 | 696.01x | 18.31x |
| SQL_ANSI_UNTERMINATED | 512 | 2.1131 | 24.2573 | 66.1335 | 11.48x | 31.30x |
| SQL_ANSI_UNTERMINATED | 1024 | 4.2475 | 50.2993 | 132.5312 | 11.84x | 31.20x |
| SQL_ANSI_UNTERMINATED | 2048 | 8.5549 | 101.9663 | 264.1539 | 11.92x | 30.88x |
| SQL_MYSQL_UNTERMINATED | 512 | 2.1128 | 25.8409 | 71.0451 | 12.23x | 33.63x |
| SQL_MYSQL_UNTERMINATED | 1024 | 4.2521 | 53.5355 | 141.3995 | 12.59x | 33.25x |
| SQL_MYSQL_UNTERMINATED | 2048 | 8.5568 | 108.5785 | 284.8270 | 12.69x | 33.29x |
| URL_AUTHORITY | 512 | 2.1070 | 4.0948 | 27.3051 | 1.94x | 12.96x |
| URL_AUTHORITY | 1024 | 4.2522 | 8.8165 | 54.2494 | 2.07x | 12.76x |
| URL_AUTHORITY | 2048 | 8.5529 | 17.6828 | 108.3236 | 2.07x | 12.67x |
| URL_QUERY | 512 | 2.1110 | 5.9723 | 28.9311 | 2.83x | 13.71x |
| URL_QUERY | 1024 | 4.1209 | 11.9096 | 58.0556 | 2.89x | 14.09x |
| URL_QUERY | 2048 | 8.5472 | 24.0287 | 116.6676 | 2.81x | 13.65x |
| URL_QUESTION_RUN | 512 | 2.0438 | 355.9957 | 30.6636 | 174.18x | 15.00x |
| URL_QUESTION_RUN | 1024 | 4.2522 | 1410.9508 | 58.3338 | 331.81x | 13.72x |
| URL_QUESTION_RUN | 2048 | 8.5384 | 5629.2536 | 123.3647 | 659.28x | 14.45x |

**Zero regressions vs JDK across all 27 scenario/size combinations.** Only soft spot: reggie
trails RE2J on `COMMAND_BLANK_LINES` at larger sizes (0.26x–0.96x) — pre-existing, unrelated to
this fix, and still far ahead of JDK (46x–54x) on the same cases.

## Conclusion

Both fixes landed cleanly with no regressions. The no-match fast-reject restoration is the
direct, measurable payoff: 3x–29x over JDK and 8x–80x over RE2J on exactly the cases that had
silently regressed to ~0.15x before the fix.
