# Remaining losses vs JDK and Rust: investigation plan

## Scope

The four-engine run (`MatchOperation`, `EngineShape`, `IastRegexp`, `RealCorpusScan`; JDK 21, Xeon
Platinum 8175M) has seven reggie rows slower than JDK and three slower than Rust. The same rows
are as slow on the tree before the linear DFA find, the backtracker step budget and the reverse-gate
precompute. They also do not change with `-Dreggie.dfa.linearFindWorkFactor=100000000`, which keeps
the work count but never switches to the linear find. So the ReDoS protections are not the cause.
This plan covers these rows:

| ID | Benchmark row | Route | reggie vs JDK | reggie vs Rust |
|---|---|---|---|---|
| L1 | `EngineShape` `(?m)^.+$` find / findMatch, SINGLE | `DFA_UNROLLED` | 0.27x / 0.17x | 1.84x / 1.16x |
| L2 | `EngineShape` `(?m)^.+$` find / findMatch, MULTI | `DFA_UNROLLED` | 0.54x / 0.42x | 12.9x / 10.1x |
| L3 | `EngineShape` `(?m)\w+$` find / findMatch, SINGLE | `DFA_UNROLLED` | 1.66x / 1.67x | 0.45x / 0.45x |
| L4 | `IastRegexp` `UrlAuthCapture` / `UrlAuthFind`, LONG | `DETERMINISTIC_CHAIN_BYTECODE` | 0.48x / 0.60x | no lane / 4.66x |
| L5 | `RealCorpusScan` `SweepMatched` | mixed (corpus) | 1.56x | 0.53x |

Baseline: the `rust4` run on workspace-jb (`~/bench/jdk-strict/rust4/{0..3}-<Class>.json`, JCC
mitigation on) and the `jccr` run (`~/bench/jdk-strict/jccr/`, mitigation off).

`EngineShape` inputs are 20 `key<i>=lorem ipsum dolor sit amet 42` lines followed by `zz`. SINGLE
joins them with spaces (712 chars, no line terminator). MULTI alternates `\n` and `\r\n` (722
chars; the first line is 34 chars). The `UrlAuth` LONG input is `https://` + 2000 `a` + `:` + 2000
`b` + `@internal.corp/api/v1/health`, against
`^(?:[^:]+:)?//(?<AUTHORITY>[^@]+)@|[?#&]([^=&;]+)=(?<QUERY>[^?#&]+)`.

The Rust lane converts the input to UTF-8 through JNI on every call. Rows where Rust looks slow on
short inputs measure that cost, not the Rust engine. Only L3 and L5 are Rust losses.

## Rules for every step

- Route first: `./gradlew :reggie-runtime:debugPattern -Ppattern="..."` (sections 5 and 6 give the
  actual routing and the generated methods). Use a probe pattern only if it routes to the same
  strategy as the row it stands for; otherwise discard it.
- Write probes as JUnit tests in the repository, not scratch programs.
- Measure on workspace-jb with JDK 21 and `-f 2` (the benchmarks declare `@Fork(1)`). Run each row
  twice: default and `-XX:-IntelJccErratumMitigation`. A difference that exists in only one of the
  two is code layout, not the engine (see the CharClassLookbehind finding: -27% with the
  mitigation, -4% without).
- Probe patterns are added as temporary `@Param` values and run as separate invocations
  (`-p pattern=...`), so the standard matrix does not change.
- Compare against the JDK control rows of the same run; discard runs where the controls move more
  than 5% from the baseline.
- Re-run only the rows under investigation; one full four-engine run at the end.
- Each step ends with a root cause stated with evidence (bytecode, profile, or a JMH difference that
  isolates one change). Fixes are proposed to the user before they are implemented.

## Step 0 for every workstream: JIT state

The per-character costs below (3.8 ns/char for about ten bytecode operations) suggest a compilation
or inlining problem as much as an instruction count. Generated methods over `HugeMethodLimit`
have run interpreted before (`doc/investigations/backend-ready/nodes/find-jit-hugemethodlimit.md`).
`LineTerminators.isMultilineEnd` takes a `CharSequence`, so the type profile of its `charAt` is
shared by every caller in the JVM.

For each row, before any other step: record the bytecode size of the generated methods
(`matchesAtStart`, `linStep`, `findLongestMatchEnd`, the chain scan method), then run the row with
`-XX:+UnlockDiagnosticVMOptions -XX:+PrintCompilation -XX:+PrintInlining` and note whether these
methods and `isMultilineEnd` are compiled by C2 and inlined.

## L1, L2: `(?m)^.+$` on `DFA_UNROLLED`

Per-character cost, from the SINGLE row: JDK about 1.0 ns/char (1400 ops/ms over 712 chars),
reggie find about 3.7 ns/char (383 ops/ms), findMatch about 5.8 ns/char (241 ops/ms).

Hypotheses:

1. H1a: the multiline `$` check runs at every character. The `.+` loop state is accepting with the
   condition `END_MULTILINE` (`SubsetConstructor` marks a state with conditions as accepting).
   `generateMatchAtStartStateCode` (used by `matchesAtStart` and `linStep`) emits the acceptance
   anchor checks at the entry of every accepting state. So each character pays a call to
   `LineTerminators.isMultilineEnd`: a bounds test, a second `CharSequence.charAt` of the same char,
   and four compares (`\n`, `\r`, U+0085, U+2028/U+2029). `findLongestMatchEnd` already avoids this
   (`deferAcceptance`): when no transition of the state consumes a line terminator, it checks the
   condition only where the state is left without a transition.
2. H1b: findMatch scans the line twice. The code shows this: `findMatchFrom` calls `findFrom`,
   whose `matchesAtStart` runs to position 712 because `$` holds only at the end. Then
   `findLongestMatchEnd` runs again from the start, and the call allocates two `int[]` and a
   `MatchResultImpl`. The second scan already defers the `$` check, which is consistent with
   findMatch costing about 2 ns/char more than find. Step 3 measures the share; it does not need to
   prove the two passes.
3. H1c (check in the dump only): the `.` charset. `(?m)` does not change `.`, and
   `BytecodeUtil.emitCharSetCheck` has a hot-range fast path (for `.`, the range
   [0x0E, 0x84], two compares for ASCII). Confirm in the dump that this path is taken; no probe.
4. H1d: JIT state (step 0).

Steps:

1. Dump the generated `findFrom`, `matchesAtStart` and `findMatch` paths for `(?m)^.+$`. Read the
   per-character loop of `matchesAtStart`; count the operations per character and note every call.
2. Isolate H1a with one probe: `(?m)^.+\z`. `\z` is emitted inline (one `pos != len` compare)
   instead of the `isMultilineEnd` call; the rest of the DFA is the same. Confirm with
   `debugPattern` that it routes to `DFA_UNROLLED` with the same state count. If find on the probe
   is close to the JDK row, H1a is the main cost. The direct test is the A/B of candidate fix 1.
   (A probe without `$` is not valid: `(?m)^.+` accepts after the first character, while JDK's
   greedy `.+` still reads the whole line.)
3. Measure H1b: findMatch minus find on the same input, before and after candidate fix 1.
4. L2 (MULTI, 34-char first line). Hypothesis: the H1a excess (about 2.7 ns/char × 34 chars,
   about 95 ns) accounts for most of the gap. After the L1 fix, measure again. If a gap remains,
   compare the 34-char first line alone (`key0=lorem ipsum dolor sit amet 42`) against the full
   MULTI input to split fixed per-call cost from per-char cost.

Candidate fixes (to be confirmed by the steps above):

1. Port `deferAcceptance` from `findLongestMatchEnd` to `generateMatchAtStartStateCode` (both the
   `matchesAtStart` and the `linStep` modes).
2. A line-terminator scan for `.+` runs under `(?m)` (`indexOf` or SWAR through `SWARUtils`).
3. A single-pass findMatch for patterns whose start is known after the first pass.

## L3: `(?m)\w+$` vs Rust

Reggie runs `matchesAtStart` from each start position in turn. Every run fails at the next space,
so a call costs about the sum of `k(k+1)/2 + k` over the 141 `\w` runs of length `k` (about 2100
steps). JDK's `Matcher.find` also tries each start in turn and is 1.7x slower than reggie. The
Rust engine (`regex` meta engine) runs a forward DFA to the end of the leftmost match, then a
reverse DFA for its start.

Hypotheses:

1. H3a: the per-step `$` cost of H1a. The `\w` loop state is also accepting with `END_MULTILINE`
   and consumes no line terminator, so each of the about 2100 steps pays `isMultilineEnd`.
2. H3b: the restart strategy itself (quadratic in run length).
3. H3c: JIT state (step 0).

`linearFindWorkFactor` facts for this row: at the default factor of 4, `workLimit(712)` is 3104
and the work is about 2100, so this row never switches to `DFALinearFind`. A switch needs a factor
of 2 or less. Factor 0 sets `workLimit` and `SHORT_SPAN` to 0, so the first failed run switches.
This applies only to DFAs that are not start-anchored.

Steps:

1. Re-baseline L3 after the L1 fix (H3a). Draw no conclusion about the switch threshold or a
   reverse DFA before this.
2. Measure `DFALinearFind.findFrom` on this input with `-Dreggie.dfa.linearFindWorkFactor=0`. Per
   position it makes one `linStartAccepts` interface call and one `linStep` interface call (with a
   `tableswitch`) per live run; it allocates five `int[stateCount]` arrays once per call. If it is
   faster than the restarts here, the factor is too generous for short failed runs.
3. Profile the remaining cost (async-profiler, if available on workspace-jb).
4. Split find from findMatch. `find()` returns a boolean and does not need the match start. A
   forward DFA that restarts itself at each position (the start state added to every step) answers
   find in one pass; prior art in this repository is `LazyDFACache.findFromUnion`. findMatch also
   needs the start; for that, compare a reverse DFA on paper (prior art: `FOLLOW_REVERSE` in
   `BitParallelGlushkovBytecodeGenerator`).

Candidate fixes: candidate fix 1 of L1; a dead-span skip (after a failed run, continue at the
position where it died, when the start state's transitions are covered by the loop state's); a
self-anchoring forward DFA for find; a lower switch threshold for short failed runs; a reverse DFA
for start recovery. The last two are design changes and need their own document.

## L4: `UrlAuth` LONG on `DETERMINISTIC_CHAIN_BYTECODE`

JDK about 0.6 ns/char over about 4000 chars, reggie about 1.0 ns/char (find) and 1.2 ns/char
(capture, 211 ops/ms vs 439).

Known from the code:

- The `[^@]+` run is a per-character loop: `emitGreedyLoop` in `DeterministicChainBytecodeGenerator`
  emits `charAt` plus a charset test per character, and `[^@]` has no hot range, so the test goes
  through range bisection.
- The chain's `findMatchFrom` uses the same single scan as find (`emitScanMethod`), so the capture
  row does not make a second pass. `NameEnrichingMatcher` adds one `NamedDelegate` allocation and a
  delegation per call (tens of ns).

Steps:

1. Step 0 (JIT state) for the chain scan method.
2. Explain the delta between capture and find (about 0.7 µs per call): the `MatchResult` build, the
   group offsets read through `NamedDelegate`, or a different route for the named pattern. Check the
   route of the pattern with and without the group names with `debugPattern`.
3. Probes such as `//[^@]+@` likely route to another strategy (`GREEDY_SUFFIX` or
   `DFA_UNROLLED`); use one only if `debugPattern` gives `DETERMINISTIC_CHAIN_BYTECODE`.
   Otherwise compare reggie find with findMatch on the same matcher and profile.

Candidate fix: emit `String.indexOf(c)` (a JIT intrinsic) for a greedy `[^c]+` run followed by `c`,
where the chain detector already proves the run cannot give back.

## L5: `RealCorpusScan` matched sweep vs Rust

The sweep is one total over all matched pattern/input pairs.

Hypotheses:

1. H5a: a few patterns account for most of the loss.
2. H5b: the loss is spread across all calls as a fixed per-call cost, including the megamorphic
   `reggie[p].find` dispatch over about 500 generated classes in the benchmark loop.

Steps:

1. Re-rank after the L1 fix (patterns with `$` under `(?m)` share H1a).
2. Add a per-pair timing JUnit probe in `reggie-runtime` over the matched pairs of
   `corpus/logs-backend-patterns.tsv`, ranking pairs by reggie time vs JDK time. (`RustRegexEngine`
   is in `reggie-benchmark/src/main`, which has no test source set, so the probe cannot call Rust.)
   The probe times each pair alone and so removes the dispatch of H5b; it only ranks.
3. Confirm the top pairs against Rust with JMH. If the top 20 pairs do not explain most of the
   loss, test H5b: compare the sweep against the sum of the per-pair times.
4. Group the top pairs by route (`debugPattern`) and shape. Shapes covered by L1-L4 go to those
   workstreams; each remaining shape gets one `EngineShape` row.

## Order and exit

1. L1 (largest ratio; the fix also affects L2, L3 and part of L5).
2. L4 (single pattern, contained).
3. L5 steps 1-3 (may re-rank the rest).
4. L3, re-baselined after L1.

Each workstream ends with a root-cause note in the investigation trail and a fix proposal for the
user. The plan is done when every row in the scope table has a stated root cause, and every
approved fix has a targeted JMH result (both mitigation settings) and a full four-engine run with
no new losses against the pre-fix tree. A new loss is a reggie row that is more than 5% slower
than on the pre-fix tree (after the control-row check), or a reggie/JDK or reggie/Rust ratio that
goes below 1.0.
