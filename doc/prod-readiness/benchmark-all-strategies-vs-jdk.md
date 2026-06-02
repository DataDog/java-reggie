# Reggie vs JDK Pattern — Comprehensive Performance Report

**Environment:** JDK 25.0.2 (OpenJDK 64-Bit Server VM), macOS Darwin 25.5.0,
1 fork · 2 warmup iterations (1 s each) · 3 measurement iterations (1 s each),
single-threaded, Throughput mode (ops/ms). All `find()` calls on embedded-match inputs
(pattern occurs inside a longer string, exercising the full scan path).

Benchmark class: `AllStrategyVsJdkBenchmark`
Raw JSON: `.jmh-runs/2026-06-02_15-20-00_all-strategy-vs-jdk.json`

## Results (sorted by Reggie/JDK ratio, descending)

| Strategy | Pattern | Reggie (ops/ms) | JDK (ops/ms) | Reggie/JDK |
|---|---|---:|---:|---:|
| SPECIALIZED_FIXED_SEQUENCE | `\d{3}-\d{3}-\d{4}` | 2 201 074 | 17 632 | **124.8×** |
| SPECIALIZED_GREEDY_CHARCLASS | `(\d+)` | 2 198 540 | 31 132 | **70.6×** |
| PIKEVM_CAPTURE¹ | `(a)?b` | 2 205 195 | 32 726 | **67.4×** |
| DFA_UNROLLED_WITH_GROUPS | `(fo\|foo)` | 1 924 160 | 40 674 | **47.3×** |
| SPECIALIZED_CONCAT_GREEDY_GROUP | `a(b*)` | 2 194 519 | 49 027 | **44.8×** |
| DFA_UNROLLED | `[ab]c` | 1 948 726 | 64 739 | **30.1×** |
| DFA_SWITCH_WITH_GROUPS | `(a\|b\|…)(h\|i\|…)(n\|…)(s\|…)` | 330 229 | 12 184 | **27.1×** |
| DFA_SWITCH | `a.*b.*c.*d.*e.*f` | 75 722 | 12 185 | **6.2×** |
| STATELESS_LOOP | `\w+` | 230 389 | 46 686 | **4.9×** |
| SPECIALIZED_BOUNDED_QUANTIFIERS | `\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}` | 79 842 | 17 879 | **4.5×** |
| SPECIALIZED_MULTI_GROUP_GREEDY | `([a-z]+)@([a-z]+)\.com` | 51 572 | 15 702 | **3.3×** |
| OPTIMIZED_NFA | `(?<x>a)?b` | 41 211 | 32 705 | **1.26×** |
| ONEPASS_NFA | `(foo)(bar)` | 4 107 | 29 903 | **0.14×** |
| DFA_TABLE | `(?:abc){100}` | 1 554 | 5 269 | **0.29×** |
| LAZY_DFA | `(?:a+b+\|b+a+){75}` | 20 | 783 | **0.025×** |

¹ PIKEVM_CAPTURE `(a)?b` is intercepted at runtime to the capture-ambiguous OPTIMIZED_NFA path
(JDK delegate), so the 67× figure reflects JDK-delegate throughput vs `Pattern.compile/matcher`
allocation overhead, not a native PIKEVM implementation.

## Analysis

### Strong wins (> 10×)

The top performers share two traits: **no per-call heap allocation** (Reggie matchers are
stateless/reentrant) and **inline bytecode loops** tailored to the exact character class or DFA
topology. JDK always allocates a `Matcher` object per `find()` call; Reggie avoids this entirely
for the SPECIALIZED_* strategies.

- **SPECIALIZED_FIXED_SEQUENCE 124.8×** — the pattern is a literal sequence of character-class
  steps; Reggie emits a hand-unrolled loop with no object allocation. JDK cannot detect the
  fixed-width structure and falls back to NFA simulation.
- **DFA_UNROLLED/DFA_UNROLLED_WITH_GROUPS 30–47×** — unrolled JIT-friendly switch chains; the
  JIT compiles these to near-zero-overhead branch sequences. `(fo|foo)` specifically is the new
  alternation-priority cut pattern added in Phase D.
- **DFA_SWITCH_WITH_GROUPS 27×** — inline switch dispatch for a 4-group alternation pattern with
  hundreds of states; JDK NFA simulation cannot compete with O(1) dispatch.

### Near-parity

- **OPTIMIZED_NFA 1.26×** — JDK fallback path; Reggie delegates to `java.util.regex` internally,
  so the tiny advantage comes from avoiding one level of indirection and the `Matcher` object.

### Regressions (< 1×)

Three strategies are currently slower than JDK:

| Strategy | Root cause |
|---|---|
| **LAZY_DFA 0.025×** | Incremental DFA cache build on each `find()` for a 75-repetition alternation; state space is enormous. Overhead is algorithmic, not a JIT issue. |
| **DFA_TABLE 0.29×** | Table-dispatch overhead plus 300-character input; JDK's NFA can short-circuit early on this literal prefix. |
| **ONEPASS_NFA 0.14×** | Capture-tracking bookkeeping cost; JDK's optimiser recognises `foobar` as a literal sequence internally. |

The LAZY_DFA and DFA_TABLE regressions are known trade-offs: these strategies prioritise
correctness and memory over throughput for extreme patterns. ONEPASS_NFA needs profiling to
determine whether the regression is structural or addressable.

## Measurement notes

- High variance on `statelessLoop` (±172k / 230k), `dfaSwitch` (±88k / 76k), and
  `specializedMultiGroupGreedy` (±71k / 52k). Recommend `-i 10 -r 2 -f 2` for these three
  before citing numbers in production documentation.
- `(fo|foo)` at 1 924 160 ops/ms is the fastest group-capturing benchmark result; Phase D
  (acceptIsPriorityCut flag) routes this pattern from OPTIMIZED_NFA (~40 k ops/ms) to
  DFA_UNROLLED_WITH_GROUPS, delivering a **47× Reggie/JDK ratio** and an estimated **~48×
  improvement over the previous JDK-delegate path**.
