# Native grapheme matching

`Reggie.compile("\\X")` matches one extended grapheme cluster with the Unicode rules
of the executing JDK. Its matcher is stateless and shared by the compilation cache.
Boolean matching, bounds, rich results, bounded matching and caller-provided group
arrays use the native scanner. Replacement, splitting and cursors use those native
operations. Rich results allocate their result objects and group arrays.

Only the exact pattern source `\X` has this native route. Captures, non-capturing
groups, inline modifiers, concatenation and quantifiers remain unsupported, including
`(\X)`, `(?:\X)`, `(?i)\X`, `a\X` and `\X+`. The specialised
`ReggieCompiledPattern` API retains its existing linear-token-sequence admission rules.

Existing external `ReggieFlags` apply normally. `CASE_INSENSITIVE`, `MULTILINE`,
`DOTALL` and `UNICODE_CHARACTER_CLASS` leave standalone grapheme matching unchanged.
`LITERAL` matches the literal backslash and `X`. Unsupported flag bits retain their
existing validation.

## Executing JDK

Native admission accepts GA JDK versions 21 through 27. The implementation selects
the grapheme rules and missing property data at runtime:

| JDK versions | Unicode rules |
| ------------ | ------------- |
| 21           | 15.0          |
| 22-23        | 15.1          |
| 24-25        | 16.0          |
| 26-27        | 17.0          |

Compatibility follows the executing JDK's regex behaviour, including its Unicode
deviations and UTF-16 edge cases. Public `Character` properties follow patches to the
executing JDK; bundled property tables require regeneration when their data changes.
Feature-version admission does not establish compatibility with every vendor and
patch build. Verification results must identify the vendor and complete JDK version.

Unknown versions and early-access builds throw `UnsupportedPatternException` from
strict compilation. `Reggie.compileAllowingFallback("\\X")` or
`ALLOW_JDK_FALLBACK` permits JDK delegation on those runtimes. This preserves the
existing explicit fallback policy documented in
[`kb/find-fallback-optin-refuse`](../.knowledgebase/memories/find-fallback-optin-refuse.md).

For `@RegexPattern("\\X")`, the generated accessor selects the matcher when called
on the executing JVM. It caches successful compilation and embeds no Unicode profile
from the processor JVM. On an unsupported runtime, each strict accessor call throws
`UnsupportedPatternException`; failure leaves the accessor cache unset. The annotation's
`ALLOW_JDK_FALLBACK` option permits delegation in that case.

## Bounds and arguments

Indices are UTF-16 offsets. Each successful `\X` match consumes at least one code unit;
empty input has no match. Group 0 contains the whole cluster and `groupCount()` is zero.

`findFrom`, `findMatchFrom`, `findBoundsFrom` and `findMatchInto` clamp negative starting
offsets to zero. An offset at or beyond the input length has no match. A supplied offset
starts a new matching attempt, including offsets inside an existing cluster. Bounded
methods follow `Matcher.region(start, end).matches()` and return offsets into the
original input.

Simple matching methods and `findBoundsFrom` treat null input as no match. Bounded
methods reject null input and invalid regions. `matchInto` and `findMatchInto` reject
null input or group arrays before matching, and require space for group 0 when a match
succeeds. `findBoundsFrom` requires two array entries when a match succeeds. A failed
match leaves caller-provided arrays unchanged.

## Verification

`GraphemeMatcherTest` compares native outcomes with the executing JDK, including
contextual combinations, malformed UTF-16 and regions. `GraphemeConformanceTest`
checks version-matched Unicode fixtures independently of the JDK comparison.
`GraphemeProfileTest` verifies runtime admission and specialised-API refusal.
`GraphemeProcessorTest` also exercises a provider compiled by the Java 21 toolchain
on the executing test JVM.

```sh
./gradlew :reggie-runtime:test --tests '*Grapheme*'
./gradlew :reggie-processor:test --tests '*GraphemeProcessorTest'
./gradlew :reggie-runtime:graphemeTest :reggie-processor:graphemeTest -PgraphemeTestJava=25
```

The dedicated `graphemeTest` tasks keep compilation on Java 21 and select the test JVM
with `graphemeTestJava`. The following runtime and processor checks passed:

| Vendor   | JDK build          | Passed | Skipped |
| -------- | ------------------ | ------ | ------- |
| Temurin  | 21.0.11+10-LTS     | 20     | 0       |
| Temurin  | 22.0.2+9           | 20     | 0       |
| Temurin  | 23.0.2+7           | 20     | 0       |
| Temurin  | 24.0.2+12          | 20     | 0       |
| Temurin  | 25.0.5+8-LTS       | 20     | 0       |
| Temurin  | 26.0.2.1+1         | 20     | 0       |
| Corretto | 27.0.1+11-FR       | 20     | 0       |
| Temurin  | 28-beta+20-ea      | 11     | 9       |

The early-access JVM exercises strict refusal, explicit fallback and repeated accessor
failure; native-only cases are skipped. No deviations between the selected Unicode
fixtures and the tested JDKs were found.

## Benchmarks

`GraphemeScanBenchmark` scans the complete input in every operation. Setup checks every
boundary and the traversal checksum against the executing JDK before measurement.
Patterns are precompiled. JDK matcher reuse and construction are measured separately.

- `nativeBounds` reuses a two-element bounds array.
- `jdkReusedMatcher` resets and reuses a JDK matcher; `jdkNewMatcher` creates one per traversal.
- `explicitFallback` directly uses `JavaRegexFallbackMatcher`, including its existing
  allocation costs. Calling `compileAllowingFallback` on a supported JDK selects native `\X`.
- `breakIterator` includes `setText` and complete character-boundary iteration.
- `nativeRich` and `jdkRich` consume each result object through a JMH blackhole to measure
  escaping rich results separately from bounds traversal.

Corpora cover ASCII, decomposed accents, CJK, emoji, Indic conjuncts, CRLF, a single
long combining sequence (`MARKS`) and a regional-indicator run (`RI`). `size` is the
minimum input length in UTF-16 code units; corpus units remain whole. Compare scaling
within the same corpus. JMH reports time and allocated bytes per complete traversal.

Run a selected corpus set with allocation profiling:

```sh
./gradlew :reggie-benchmark:jmh -Pjmh.args='GraphemeScanBenchmark -p corpus=ASCII,DECOMPOSED,EMOJI -p size=1024 -prof gc'
```

Run all corpora at small, typical and large sizes:

```sh
./gradlew :reggie-benchmark:jmh -Pjmh.args='GraphemeScanBenchmark -p size=16,1024,65536 -prof gc'
```

Defaults are two forks, five one-second warmup iterations and five one-second measurement
iterations.

### Recorded measurements

Measured on 2026-11-01 with OpenJDK `27+35-2325`, JMH 1.37, macOS 26.7.2 and an
Apple M4 Max. These runs used two forks, three 500 ms warmups, five 500 ms measurements
and the GC profiler. Patterns were precompiled. Times are microseconds per complete
traversal with `size=1024`; preserving whole corpus units can make the actual input
slightly longer.

| Corpus     | Native bounds, us | Reused JDK matcher, us |
| ---------- | ----------------- | ---------------------- |
| ASCII      | 1.612             | 8.627                  |
| Decomposed | 3.532             | 7.361                  |
| CJK        | 19.038            | 26.134                 |
| Emoji      | 9.144             | 12.064                 |
| Indic      | 12.029            | 20.214                 |
| CRLF       | 1.327             | 7.900                  |
| Marks      | 1.829             | 4.586                  |
| RI         | 8.779             | 13.163                 |

Both bounds loops allocated at the profiler's background level, approximately
0.012 MB/s, with no GC events. Residual B/op increases with traversal duration and
does not establish allocation by the matching thread. A separate warmed
`ThreadMXBean` probe measured zero matching-thread bytes across 20 traversals each
of ASCII, marks and RI inputs, including marks and RI runs of 1,048,576 UTF-16 units.

Large-input checks used sizes 16, 65,536 and 1,048,576. At the largest size, native
marks traversal took 1.919 ms against the JDK's 4.776 ms; RI traversal took 10.652 ms
against 13.441 ms. Working-set effects change throughput per code unit, while the
scanner retains constant working state and advances through the input.

Rich-result measurements consume each result through a blackhole:

| Corpus | Native rich, us | JDK rich, us | Native B/traversal | JDK B/traversal |
| ------ | --------------- | ------------ | ------------------ | --------------- |
| ASCII  | 7.747           | 40.882       | 66,368             | 107,848         |
| Emoji  | 12.080          | 20.526       | 19,776             | 37,904          |

The initial classifier prototype was slower than the reused JDK matcher for
decomposed text and long combining sequences. The measured fast paths removed those
regressions. RI traversal became slower than that prototype while remaining faster
than the reused JDK matcher. These figures describe the tested inputs and machine;
they are not universal speed guarantees. The measurements exclude startup and compilation.

To reproduce the bounds comparison with the same sampling settings:

```sh
./gradlew :reggie-benchmark:jmh -Pjmh.args='GraphemeScanBenchmark.(nativeBounds|jdkReusedMatcher) -p corpus=ASCII,DECOMPOSED,CJK,EMOJI,INDIC,CRLF,MARKS,RI -p size=1024 -f 2 -wi 3 -i 5 -w 500ms -r 500ms -prof gc'
```

JMH writes raw results to `reggie-benchmark/build/reports/jmh/results.json`. Select
the same Java 27 executable with JMH's `-jvm` option for comparisons across JDK installations.

## Updating profile data

The `reggie.grapheme.data` plugin in the included `build-logic` build owns
[`GraphemeDataGenerator.java`](../build-logic/src/graphemeGenerator/java/com/datadoghq/reggie/gradle/GraphemeDataGenerator.java), which extracts compact
property corrections from audited JDK installations and the pinned Unicode 15.1
data file. Its Javadoc records the input URL and checksum. The generator verifies
all 1,114,112 code points and checks the bounds assumed by the fast paths. Internal
JDK access is confined to this offline tool; production matching uses public APIs.

Run the explicit Gradle maintenance task with the pinned Unicode file:

```sh
./gradlew :reggie-runtime:generateGraphemeData -PgraphemeUnicodeData=/path/to/DerivedCoreProperties.txt
./gradlew spotlessApply
```

Gradle selects JDKs 21, 25, 26 and 27 through its toolchains. The task updates the
committed tables and verifies every profile on each invocation. Normal compilation,
benchmarks and CI use the committed tables without running generation.

Generation preserves the scanner and classifier method bodies. Rerun the JDK matrix
before enabling a new profile. The versioned Unicode fixtures and derived property
data retain their Unicode licence.
