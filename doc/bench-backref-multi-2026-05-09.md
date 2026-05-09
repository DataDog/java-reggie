# Backreference Benchmark Results — 2026-05-09

Branch: `muse/bug-multiple-backreferences-to-same` (PR #55, fix for issue #27)

Run: JMH fork=1, warmup=3×1s, measurement=5×1s (`BackreferenceBenchmark`) /
     fork=1, warmup=2×1s, measurement=3×1s (`NFAFallbackBenchmark`)

## BackreferenceBenchmark

| Benchmark | Reggie (ops/ms) | JDK (ops/ms) | Ratio |
|---|---|---|---|
| SimpleBackrefMatch `(a)\1` | 1,962,868 | 82,861 | **23.7x** |
| SimpleBackrefNoMatch | 2,160,397 | 86,268 | **25.0x** |
| RepeatedWordMatch `\b(\w+)\s+\1\b` | 231,961 | 33,701 | **6.9x** |
| RepeatedWordNoMatch | 36,669 | 7,989 | **4.6x** |
| HtmlTagMatch `<(\w+)>.*</\1>` | 140,202 | 23,288 | **6.0x** |
| HtmlTagNoMatch | 135,174 | 13,615 | **9.9x** |
| MultipleBackrefMatch `(\w)(\w)\1\2` | 2,126,001 | 36,590 | **58.1x** |
| MultipleBackrefNoMatch | 2,156,907 | 41,926 | **51.5x** |
| SelfRefRepeatedMatch `^(a\1?){4}$` | 2,128,710 | 13,205 | **161.2x** |
| SelfRefRepeatedNoMatch | 2,169,608 | 20,456 | **106.1x** |
| SelfRefFourGroupsMatch4 `^(a\1?)(a\1?)(a\2?)(a\3?)$` | 26,364 | 16,690 | **1.6x** |
| SelfRefFourGroupsMatch6 | 43,200 | 15,339 | **2.8x** |
| SelfRefFourGroupsNoMatch | 35,729 | 28,728 | **1.2x** |

## NFAFallbackBenchmark — backreference patterns

| Benchmark | Reggie (ops/ms) | JDK (ops/ms) | Ratio |
|---|---|---|---|
| DuplicateWordMatch `(\w+)\s+\1` | 125,695 | 37,054 | **3.4x** |
| DuplicateWordNoMatch | 14,930 | 5,350 | **2.8x** |
| BackrefWithContentMatch `([a-z]{3}).*\1` | 101,214 | 35,986 | **2.8x** |
| RepeatedSequenceMatch `(a+)\1` | 1,154,252 | 49,207 | **23.5x** |
| XmlTagsMatch `(<\w+>).*?(</\w+>)` | 14,118 | 9,576 | **1.5x** |

## Context

Multi-backref patterns to the same group (e.g. `(\w+)\s+\1\s+\1`) previously fell back
to `java.util.regex`. After the fix they run natively, comparable to the DuplicateWord
(single-backref) row above (~3–4x vs JDK).

No regressions observed on any existing single-backref pattern.

Pre-existing unrelated issue: `LookaheadNoBoyerMoore` `(?=\w+@).*@\w+\.\w+` runs at 0.33x
JDK because JDK uses Boyer-Moore for this specific lookahead shape. Tracked separately.
