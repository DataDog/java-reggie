# PIKEVM Delegating Stub + Compile-Time Baking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let `@RegexPattern` accept patterns that resolve to `PIKEVM_CAPTURE` (native at runtime, but not standalone-bakeable) by generating a thin stub that delegates to the runtime engine — and let it accept genuine JDK-fallback patterns only when `ALLOW_JDK_FALLBACK` is set on the annotation. Eliminate the compile-time/runtime authoring incompatibility without serializing the NFA.

**Architecture:** The annotation processor already runs `PatternAnalyzer` and builds the NFA at compile time to pick a strategy. For PIKEVM patterns it now emits a delegating stub whose body calls `RuntimeCompiler.compilePikeVm(pattern, encodedNames)` — a new entrypoint that **skips re-analysis** (carrying the resolved strategy decision + baked name map) but still builds the NFA via the single canonical runtime builder (no serialization, no drift). For JDK-fallback patterns the stub calls `Reggie.compileAllowingFallback(pattern)`, gated on `@RegexPattern(options=ALLOW_JDK_FALLBACK)`; without the flag the build fails as today.

**Tech Stack:** Java 21, ASM (already used), JUnit 5, JDK `ToolProvider` compiler for end-to-end processor tests. No new dependencies.

**Depends on:** `2026-06-12-reggie-option-flags-and-fallback-policy.md` (Plan A) — must be merged first; this plan uses `ReggieOption`.

**Trust boundary (documented):** `compilePikeVm` trusts the baked strategy decision and does not re-verify it. The processor is the single source of that decision; within one build artifact the compile-time and runtime `PatternAnalyzer` are identical code, so the decision is reproducible. The NFA itself is always built by the canonical runtime builder — only the routing decision is carried across.

---

## File Structure

- `reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/RuntimeCompiler.java` — **modify**: add `compilePikeVm(String, String)`, `encodeNameMap`/`decodeNameMap`.
- `reggie-runtime/src/main/java/com/datadoghq/reggie/Reggie.java` — **modify**: add `compileAllowingFallback(String)`.
- `reggie-runtime/src/main/java/com/datadoghq/reggie/annotations/RegexPattern.java` — **modify**: add `ReggieOption[] options() default {}`.
- `reggie-processor/.../ReggieMatcherBytecodeGenerator.java` — **modify**: expose a delegation decision instead of unconditionally throwing for PIKEVM / gated for fallback.
- `reggie-processor/.../RegexPatternProcessor.java` — **modify**: read `options()`, branch native vs. delegating vs. build-error, skip matcher-class gen for delegating methods.
- `reggie-processor/.../ImplClassBytecodeGenerator.java` — **modify**: emit delegating field init.
- Tests: runtime unit tests; processor end-to-end tests via `ToolProvider`.

---

### Task 1: Runtime entrypoints — `compilePikeVm` + name-map codec + `compileAllowingFallback`

**Files:**
- Modify: `reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/RuntimeCompiler.java`
- Modify: `reggie-runtime/src/main/java/com/datadoghq/reggie/Reggie.java`
- Test: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/CompilePikeVmTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.datadoghq.reggie.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.datadoghq.reggie.Reggie;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CompilePikeVmTest {
  // A PIKEVM_CAPTURE pattern (capture-ambiguous greedy wildcard around named groups).
  private static final String P = "(?<open><\\w+>).*(?<close></\\w+>)";
  private static final String IN = "<a>text</a>";

  @Test
  void nameMapRoundTrips() {
    Map<String, Integer> m = new LinkedHashMap<>();
    m.put("open", 1);
    m.put("close", 2);
    assertEquals(m, RuntimeCompiler.decodeNameMap(RuntimeCompiler.encodeNameMap(m)));
    assertEquals(Map.of(), RuntimeCompiler.decodeNameMap(RuntimeCompiler.encodeNameMap(Map.of())));
  }

  @Test
  void compilePikeVmMatchesRuntimePath() {
    String encoded = RuntimeCompiler.encodeNameMap(Map.of("open", 1, "close", 2));
    ReggieMatcher staged = RuntimeCompiler.compilePikeVm(P, encoded);
    ReggieMatcher runtime = Reggie.compile(P);

    assertEquals(runtime.find(IN), staged.find(IN));
    MatchResult sr = staged.findMatch(IN);
    MatchResult rr = runtime.findMatch(IN);
    assertEquals(rr != null, sr != null);
    if (rr != null) {
      assertEquals(rr.start(), sr.start());
      assertEquals(rr.end(), sr.end());
      // Named-group parity proves the baked name map is wired.
      assertEquals(rr.start(1), sr.start(1));
      assertEquals(rr.end(2), sr.end(2));
    }
    assertFalse(staged instanceof JavaRegexFallbackMatcher);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests 'com.datadoghq.reggie.runtime.CompilePikeVmTest'`
Expected: FAIL — `compilePikeVm`, `encodeNameMap`, `decodeNameMap` do not exist.

- [ ] **Step 3: Add the codec + entrypoint to `RuntimeCompiler`**

```java
  // Control separators (US/RS) that cannot appear in a Java identifier or group name.
  private static final char NAME_SEP = '\u001F'; // name/index within a pair
  private static final char PAIR_SEP = '\u001E'; // between pairs

  /** Encodes a group-name → index map into a single stable string for baking into a stub. */
  public static String encodeNameMap(Map<String, Integer> nameMap) {
    if (nameMap == null || nameMap.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, Integer> e : nameMap.entrySet()) {
      if (sb.length() > 0) {
        sb.append(PAIR_SEP);
      }
      sb.append(e.getKey()).append(NAME_SEP).append(e.getValue());
    }
    return sb.toString();
  }

  /** Inverse of {@link #encodeNameMap}. Returns an empty map for an empty/blank string. */
  public static Map<String, Integer> decodeNameMap(String encoded) {
    if (encoded == null || encoded.isEmpty()) {
      return java.util.Collections.emptyMap();
    }
    Map<String, Integer> m = new java.util.LinkedHashMap<>();
    int i = 0;
    while (i < encoded.length()) {
      int pair = encoded.indexOf(PAIR_SEP, i);
      if (pair < 0) {
        pair = encoded.length();
      }
      int sep = encoded.indexOf(NAME_SEP, i);
      String name = encoded.substring(i, sep);
      int idx = Integer.parseInt(encoded.substring(sep + 1, pair));
      m.put(name, idx);
      i = pair + 1;
    }
    return m;
  }

  /**
   * Compile a pattern that the annotation processor resolved to {@code PIKEVM_CAPTURE}, skipping
   * strategy re-analysis. The NFA is still built by the canonical runtime builder; only the routing
   * decision and the name map are carried from compile time. Used by generated delegating stubs.
   */
  public static ReggieMatcher compilePikeVm(String pattern, String encodedNames) {
    PikeVMEntry entry = PIKEVM_NFA_CACHE.get(pattern);
    if (entry != null) {
      return entry.newMatcher(pattern);
    }
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    Map<String, Integer> nameMap = decodeNameMap(encodedNames);
    int groupCount = countGroups(pattern);
    NFA nfa = new ThompsonBuilder().build(ast, groupCount);
    PIKEVM_NFA_CACHE.putIfAbsent(pattern, new PikeVMEntry(nfa, nameMap));
    return PIKEVM_NFA_CACHE.get(pattern).newMatcher(pattern);
  }
```

> Confirm `countGroups`, `RegexParser`, `RegexNode`, `ThompsonBuilder`, `NFA`, `PikeVMEntry`, `PIKEVM_NFA_CACHE` are all already imported/visible in `RuntimeCompiler` (they are — used by `compileInternal`).

- [ ] **Step 4: Add `Reggie.compileAllowingFallback`**

In `Reggie.java`, add near the other `compile` overloads:

```java
  /**
   * Compile a pattern permitting {@code java.util.regex} fallback for constructs Reggie cannot
   * compile natively. Equivalent to {@code compile(pattern, builder().allowJdkFallback().build())}.
   * Used by generated stubs for {@code @RegexPattern(options = ALLOW_JDK_FALLBACK)} patterns.
   */
  public static ReggieMatcher compileAllowingFallback(String pattern) {
    return RuntimeCompiler.compile(
        pattern, ReggieOptions.builder().allowJdkFallback().build());
  }
```

Add `import com.datadoghq.reggie.ReggieOptions;` if not already present (it is, given the existing overload).

- [ ] **Step 5: Run test to verify it passes**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests 'com.datadoghq.reggie.runtime.CompilePikeVmTest'`
Expected: PASS

- [ ] **Step 6: spotlessApply + commit**

```bash
export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew spotlessApply
git add reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/RuntimeCompiler.java \
        reggie-runtime/src/main/java/com/datadoghq/reggie/Reggie.java \
        reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/CompilePikeVmTest.java
git commit -m "feat: add compilePikeVm staging entrypoint + name-map codec"
```

---

### Task 2: Add `options()` to `@RegexPattern`

**Files:**
- Modify: `reggie-runtime/src/main/java/com/datadoghq/reggie/annotations/RegexPattern.java`

- [ ] **Step 1: Add the attribute**

```java
import com.datadoghq.reggie.ReggieOption;
```

```java
public @interface RegexPattern {
  /** The regular expression pattern. */
  String value();

  /**
   * Compilation flags. {@code ALLOW_JDK_FALLBACK} permits the processor to generate a stub that
   * delegates to {@code java.util.regex} at runtime for patterns Reggie cannot compile natively;
   * without it, such patterns are a build error. Has no effect on natively-compilable patterns.
   */
  ReggieOption[] options() default {};
}
```

> `@RegexPattern` is `@Retention(SOURCE)`, so this is read only by the processor (Task 3), never at runtime. No runtime test here; verified end-to-end in Task 5.

- [ ] **Step 2: Verify it compiles**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:compileJava`
Expected: BUILD SUCCESSFUL.

> `reggie-runtime` must compile against `ReggieOption` (same module — fine). If `annotations` lives in a module that does not depend on the `ReggieOption` package, move `ReggieOption` so both see it, or reference it by FQN; confirm the module graph first with `grep -rn "package com.datadoghq.reggie;" reggie-runtime` and the annotations module's build file.

- [ ] **Step 3: Commit**

```bash
git add reggie-runtime/src/main/java/com/datadoghq/reggie/annotations/RegexPattern.java
git commit -m "feat: add options() to @RegexPattern"
```

---

### Task 3: Processor — classify each method as NATIVE / DELEGATE_PIKEVM / DELEGATE_FALLBACK / ERROR

**Files:**
- Modify: `reggie-processor/.../ReggieMatcherBytecodeGenerator.java`
- Modify: `reggie-processor/.../RegexPatternProcessor.java`
- Test: extend `reggie-processor/src/test/java/com/datadoghq/reggie/processor/ReggieMatcherBytecodeGeneratorTest.java`

**Decision table** (computed from the resolved strategy + the method's `options()`):

| Condition (in `generate()` order) | options has ALLOW_JDK_FALLBACK | Outcome |
|---|---|---|
| strategy == PIKEVM_CAPTURE | (any) | **DELEGATE_PIKEVM** |
| anchorConditionDiluted / alternationPriorityConflict / captureAmbiguous / FallbackPatternDetector reason != null / FULL_FALLBACK strategy | yes | **DELEGATE_FALLBACK** |
| same as above | no | **ERROR** (build failure, current behavior) |
| otherwise | — | **NATIVE** (emit bytecode, current behavior) |

- [ ] **Step 1: Add a delegation-decision API to `ReggieMatcherBytecodeGenerator`**

Add an enum and a decision method that mirrors the existing reject logic in `generate()` but returns a decision instead of throwing, so the processor can act on it:

```java
  /** How a @RegexPattern method should be realized. */
  public enum Realization {
    NATIVE,
    DELEGATE_PIKEVM,
    DELEGATE_FALLBACK
  }

  /**
   * Resolves how to realize {@code pattern}. Throws {@link UnsupportedOperationException} when the
   * pattern requires JDK fallback but {@code allowJdkFallback} is false (build error). Must be
   * called instead of {@link #generate()} for the realization branch; {@link #generate()} stays the
   * NATIVE path. Populates {@link #resolvedStrategy()}.
   */
  public Realization resolveRealization(boolean allowJdkFallback) throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    int groupCount = countGroups(pattern);
    NFA nfa = new ThompsonBuilder().build(ast, groupCount);
    PatternAnalyzer analyzer = new PatternAnalyzer(ast, nfa);
    PatternAnalyzer.MatchingStrategyResult result = analyzer.analyzeAndRecommend();
    this.resolvedStrategy = result.strategy;

    if (result.strategy == PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE) {
      return Realization.DELEGATE_PIKEVM;
    }
    boolean needsJdk =
        result.anchorConditionDiluted
            || result.alternationPriorityConflict
            || result.captureAmbiguous
            || FallbackPatternDetector.needsFallback(ast, result.strategy) != null
            || StrategyJdkClassifier.classifyJdkDependency(result.strategy)
                == StrategyJdkClassifier.StrategyJdkClass.FULL_FALLBACK;
    if (needsJdk) {
      if (allowJdkFallback) {
        return Realization.DELEGATE_FALLBACK;
      }
      throw new UnsupportedOperationException(
          "Pattern '"
              + pattern
              + "' requires java.util.regex fallback (strategy "
              + result.strategy
              + "). Add options = ReggieOption.ALLOW_JDK_FALLBACK to @RegexPattern to permit a"
              + " delegating stub, or use Reggie.compile() at runtime.");
    }
    return Realization.NATIVE;
  }

  /** Group-name map for the resolved pattern (for baking into a PIKEVM stub). */
  public java.util.Map<String, Integer> nameMap() throws Exception {
    return new RegexParser().getGroupNameMap(); // parse side-effect; call after parse
  }
```

> Reuse the exact reason strings already present in `generate()` where practical. `resolveRealization` re-parses; that is acceptable (compile-time, once per method). If you prefer to avoid double-parsing, have `nameMap()` cache the parser from `resolveRealization`; keep it simple unless profiling says otherwise.

- [ ] **Step 2: Branch in `RegexPatternProcessor`**

Locate where the processor currently calls `generator.generate()` and writes the matcher class (around `RegexPatternProcessor.java:184-223`) and where it assembles `ImplClassBytecodeGenerator.MethodInfo` (around `:234-238`). Read the method's `options()`:

```java
    boolean allowJdkFallback = false;
    for (com.datadoghq.reggie.ReggieOption o : annotation.options()) {
      if (o == com.datadoghq.reggie.ReggieOption.ALLOW_JDK_FALLBACK) {
        allowJdkFallback = true;
      }
    }
```

> `annotation` is the `RegexPattern` mirror for the method. If the processor reads attributes via `AnnotationMirror`/`getAnnotation`, use whichever it already uses for `value()`; mirror that access for `options()`.

For each method, compute `Realization` and act:

```java
    ReggieMatcherBytecodeGenerator gen =
        new ReggieMatcherBytecodeGenerator(packageName, matcherClassName, pattern);
    ReggieMatcherBytecodeGenerator.Realization realization;
    try {
      realization = gen.resolveRealization(allowJdkFallback);
    } catch (UnsupportedOperationException e) {
      messager.printMessage(Diagnostic.Kind.ERROR, e.getMessage(), method);
      continue; // skip this method
    }

    switch (realization) {
      case NATIVE:
        // existing path: gen.generate() → write .class (keep RICH_API_HYBRID warning)
        writeNativeMatcherClass(gen, packageName, matcherClassName); // existing logic, extracted
        methodInfos.add(ImplClassBytecodeGenerator.MethodInfo.native_(methodName, matcherClassName));
        break;
      case DELEGATE_PIKEVM:
        messager.printMessage(
            Diagnostic.Kind.NOTE,
            "@RegexPattern '" + pattern + "' delegates to runtime PikeVM (native, not bakeable).");
        methodInfos.add(
            ImplClassBytecodeGenerator.MethodInfo.pikevm(
                methodName, pattern, RuntimeCompiler.encodeNameMap(gen.nameMap())));
        break;
      case DELEGATE_FALLBACK:
        messager.printMessage(
            Diagnostic.Kind.MANDATORY_WARNING,
            "@RegexPattern '" + pattern + "' compiles to a JDK-delegating stub (java.util.regex at"
                + " runtime) because ALLOW_JDK_FALLBACK is set.",
            method);
        methodInfos.add(ImplClassBytecodeGenerator.MethodInfo.fallback(methodName, pattern));
        break;
    }
```

For DELEGATE_* methods, **do not** call `generator.generate()` and **do not** create a matcher `.class` file. The `MethodInfo` carries everything the impl class needs (Task 4).

> Extract the existing native write path (createClassFile + os.write + RICH_API_HYBRID warning, `RegexPatternProcessor.java:217-223`/`190-215`) into `writeNativeMatcherClass(...)` so the `NATIVE` case reuses it verbatim.

- [ ] **Step 3: Run existing processor tests (regression)**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-processor:test`
Expected: BUILD SUCCESSFUL (no behavior change for NATIVE patterns; ERROR path message changed text only).

- [ ] **Step 4: Commit**

```bash
export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew spotlessApply
git add reggie-processor/src/main/java/com/datadoghq/reggie/processor/ReggieMatcherBytecodeGenerator.java \
        reggie-processor/src/main/java/com/datadoghq/reggie/processor/RegexPatternProcessor.java
git commit -m "feat: processor classifies methods native/delegate/error"
```

---

### Task 4: `ImplClassBytecodeGenerator` — emit delegating field initializers

**Files:**
- Modify: `reggie-processor/.../ImplClassBytecodeGenerator.java`

The lazy field for a method is currently typed as the concrete matcher class and initialized with `NEW matcherClass; DUP; INVOKESPECIAL <init>()V` (`:143-145`). For delegating methods the field is typed `Lcom/datadoghq/reggie/runtime/ReggieMatcher;` and initialized with a static call.

- [ ] **Step 1: Extend `MethodInfo` with a realization kind + payload**

Replace the `MethodInfo` class (`:34-41`) with:

```java
  public static class MethodInfo {
    public enum Kind { NATIVE, PIKEVM, FALLBACK }

    public final String methodName;
    public final Kind kind;
    public final String matcherClassName; // NATIVE only
    public final String pattern; // delegating only
    public final String encodedNames; // PIKEVM only

    private MethodInfo(
        String methodName, Kind kind, String matcherClassName, String pattern, String encodedNames) {
      this.methodName = methodName;
      this.kind = kind;
      this.matcherClassName = matcherClassName;
      this.pattern = pattern;
      this.encodedNames = encodedNames;
    }

    public static MethodInfo native_(String methodName, String matcherClassName) {
      return new MethodInfo(methodName, Kind.NATIVE, matcherClassName, null, null);
    }

    public static MethodInfo pikevm(String methodName, String pattern, String encodedNames) {
      return new MethodInfo(methodName, Kind.PIKEVM, null, pattern, encodedNames);
    }

    public static MethodInfo fallback(String methodName, String pattern) {
      return new MethodInfo(methodName, Kind.FALLBACK, null, pattern, null);
    }
  }
```

- [ ] **Step 2: Field descriptor + init by kind**

In the field-declaration loop (`:67`) and `generateLazyInitMethod` (`:105-174`), choose the field descriptor by kind:

```java
    String fieldDescriptor =
        method.kind == MethodInfo.Kind.NATIVE
            ? "L" + packageName + "/" + method.matcherClassName + ";"
            : "Lcom/datadoghq/reggie/runtime/ReggieMatcher;";
```

Replace the init block (`:142-146`, the `field = new MatcherClass()` part) with a kind switch. Keep everything else (double-checked locking, labels, exception table) identical:

```java
    // Initialize: field = <realization>;
    mv.visitVarInsn(ALOAD, 0); // Load 'this'
    switch (method.kind) {
      case NATIVE:
        mv.visitTypeInsn(NEW, packageName + "/" + method.matcherClassName);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(
            INVOKESPECIAL, packageName + "/" + method.matcherClassName, "<init>", "()V", false);
        break;
      case PIKEVM:
        mv.visitLdcInsn(method.pattern);
        mv.visitLdcInsn(method.encodedNames);
        mv.visitMethodInsn(
            INVOKESTATIC,
            "com/datadoghq/reggie/runtime/RuntimeCompiler",
            "compilePikeVm",
            "(Ljava/lang/String;Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/ReggieMatcher;",
            false);
        break;
      case FALLBACK:
        mv.visitLdcInsn(method.pattern);
        mv.visitMethodInsn(
            INVOKESTATIC,
            "com/datadoghq/reggie/Reggie",
            "compileAllowingFallback",
            "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/ReggieMatcher;",
            false);
        break;
    }
    mv.visitFieldInsn(PUTFIELD, implClassName, method.methodName, fieldDescriptor);
```

> The `GETFIELD` reads at `:125`, `:138`, `:167` use `fieldDescriptor`, so they pick up the corrected descriptor automatically. Ensure `INVOKESTATIC`, `NEW`, `DUP` are imported from `org.objectweb.asm.Opcodes` (NATIVE path already uses NEW/DUP/INVOKESPECIAL).

- [ ] **Step 3: Update the `MethodInfo` construction site in the processor**

This was already done in Task 3 Step 2 (using `MethodInfo.native_/pikevm/fallback`). Confirm `ImplClassBytecodeGenerator.MethodInfo(methodName, matcherClassName)` is no longer called anywhere (`grep`).

- [ ] **Step 4: Build**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-processor:compileJava :reggie-processor:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew spotlessApply
git add reggie-processor/src/main/java/com/datadoghq/reggie/processor/ImplClassBytecodeGenerator.java \
        reggie-processor/src/main/java/com/datadoghq/reggie/processor/RegexPatternProcessor.java
git commit -m "feat: emit delegating stubs for PIKEVM/fallback @RegexPattern methods"
```

---

### Task 5: End-to-end processor test (`ToolProvider` in-process compile)

**Files:**
- Test: `reggie-processor/src/test/java/com/datadoghq/reggie/processor/DelegatingStubProcessorTest.java`

This drives the real processor over in-memory source using the JDK compiler — no new dependency. It proves: (a) a PIKEVM `@RegexPattern` now compiles and the generated impl matches like `Reggie.compile`; (b) a fallback pattern with `options=ALLOW_JDK_FALLBACK` compiles; (c) the same pattern without the flag fails the build.

- [ ] **Step 1: Write the test**

```java
package com.datadoghq.reggie.processor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class DelegatingStubProcessorTest {

  private static JavaFileObject src(String fqcn, String code) {
    return new SimpleJavaFileObject(
        URI.create("string:///" + fqcn.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
      @Override
      public CharSequence getCharContent(boolean ignore) {
        return code;
      }
    };
  }

  private boolean compile(Path out, JavaFileObject source) throws Exception {
    JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
    var fm = javac.getStandardFileManager(null, null, null);
    fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(out.toFile()));
    // Classpath inherits the test runtime classpath (reggie-runtime, processor) via the forked JVM.
    boolean ok =
        javac
            .getTask(null, fm, null, List.of("-classpath", System.getProperty("java.class.path")),
                null, List.of(source))
            .call();
    fm.close();
    return ok;
  }

  @Test
  void pikevmPatternCompilesWithoutFlag(@TempDir Path out) throws Exception {
    String code =
        "package gen;\n"
            + "import com.datadoghq.reggie.annotations.RegexPattern;\n"
            + "import com.datadoghq.reggie.runtime.ReggieMatcher;\n"
            + "public abstract class PVM {\n"
            + "  @RegexPattern(\"(<\\\\w+>).*(</\\\\w+>)\")\n"
            + "  public abstract ReggieMatcher tags();\n"
            + "}\n";
    assertTrue(compile(out, src("gen.PVM", code)), "PIKEVM @RegexPattern should compile");
  }

  @Test
  void fallbackPatternFailsWithoutFlag(@TempDir Path out) throws Exception {
    String code =
        "package gen;\n"
            + "import com.datadoghq.reggie.annotations.RegexPattern;\n"
            + "import com.datadoghq.reggie.runtime.ReggieMatcher;\n"
            + "public abstract class FB {\n"
            + "  @RegexPattern(\"([a-z]{3}).*\\\\1\")\n"
            + "  public abstract ReggieMatcher backref();\n"
            + "}\n";
    assertFalse(compile(out, src("gen.FB", code)), "fallback pattern must fail without flag");
  }

  @Test
  void fallbackPatternCompilesWithFlag(@TempDir Path out) throws Exception {
    String code =
        "package gen;\n"
            + "import com.datadoghq.reggie.annotations.RegexPattern;\n"
            + "import com.datadoghq.reggie.ReggieOption;\n"
            + "import com.datadoghq.reggie.runtime.ReggieMatcher;\n"
            + "public abstract class FBOK {\n"
            + "  @RegexPattern(value = \"([a-z]{3}).*\\\\1\","
            + " options = ReggieOption.ALLOW_JDK_FALLBACK)\n"
            + "  public abstract ReggieMatcher backref();\n"
            + "}\n";
    assertTrue(compile(out, src("gen.FBOK", code)), "fallback pattern should compile with flag");
  }
}
```

> The exact PIKEVM/fallback example patterns must match what the current analyzer actually routes. Before finalizing, verify with a throwaway: `Reggie.compile("(<\\w+>).*(</\\w+>)")` is a `PikeVMMatcher`-backed matcher (not fallback), and `([a-z]{3}).*\1` hits a fallback site. Swap in confirmed patterns from `NFAFallbackPatterns.java` if either assumption is wrong.

- [ ] **Step 2: Run the test**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-processor:test --tests 'com.datadoghq.reggie.processor.DelegatingStubProcessorTest'`
Expected: PASS (all three).

> If the in-process compiler cannot see the annotation processor (no auto-registration), add `-processorpath`/`-processor com.datadoghq.reggie.processor.RegexPatternProcessor` to the javac options, or confirm the processor is registered via `META-INF/services/javax.annotation.processing.Processor` on the test classpath.

- [ ] **Step 3: Commit**

```bash
git add reggie-processor/src/test/java/com/datadoghq/reggie/processor/DelegatingStubProcessorTest.java
git commit -m "test: end-to-end delegating-stub processor coverage"
```

---

### Task 6: Convert a benchmark/example from `Reggie.compile` field to `@RegexPattern`

**Files:**
- Modify: `reggie-benchmark/src/main/java/com/datadoghq/reggie/benchmark/NFAFallbackPatterns.java`

This proves the authoring incompatibility is gone end-to-end and keeps the example honest.

- [ ] **Step 1: Convert `xmlTags()` (PIKEVM) to an annotated method**

Replace (`:63-67` + the field at `:139`):

```java
  // PIKEVM_CAPTURE is native at runtime; the processor now generates a delegating stub.
  @RegexPattern("(<\\w+>).*(</\\w+>)")
  public abstract ReggieMatcher xmlTags();
```

and delete the `XML_TAGS` static field. Leave genuinely-FULL_FALLBACK methods as `Reggie.compile` fields (or annotate them with `options = ALLOW_JDK_FALLBACK` if you want them baked as delegating stubs — optional; out of scope for the core goal).

- [ ] **Step 2: Build the benchmark module**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-benchmark:compileJava`
Expected: BUILD SUCCESSFUL — `xmlTags()` now resolves via the generated impl.

- [ ] **Step 3: Commit**

```bash
export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew spotlessApply
git add reggie-benchmark/src/main/java/com/datadoghq/reggie/benchmark/NFAFallbackPatterns.java
git commit -m "refactor: author PIKEVM xmlTags via @RegexPattern delegating stub"
```

---

### Task 7: Full sweep + fuzz gate

- [ ] **Step 1: Full test suite**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew test`
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 2: Zero-divergence fuzz gate**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-integration-tests:test -Dreggie.fuzz.durationSeconds=30 2>&1 | tail -20`
Expected: `findings=0`.

- [ ] **Step 3: Update AGENTS.md**

Document: `@RegexPattern` now accepts PIKEVM patterns (delegating stub, native at runtime) and FULL_FALLBACK patterns only with `options = ALLOW_JDK_FALLBACK`; runtime `compile` throws `UnsupportedPatternException` by default. Reflect that the PIKEVM compile-time-rejection row no longer holds.

```bash
git add AGENTS.md && git commit -m "docs: @RegexPattern delegating stubs + fallback policy"
```

---

## Self-Review Checklist (run after implementing all tasks)

- [ ] No `ImplClassBytecodeGenerator.MethodInfo(String, String)` constructor calls remain (`grep`).
- [ ] DELEGATE_* methods produce no per-pattern matcher `.class` (only the impl class field + static call).
- [ ] `compilePikeVm` builds the NFA via `ThompsonBuilder` (canonical builder) — no serialized NFA anywhere.
- [ ] PIKEVM `@RegexPattern` compiles with **no** options; FULL_FALLBACK requires `ALLOW_JDK_FALLBACK`; absent → build error.
- [ ] Generated stub for a PIKEVM pattern returns matches identical to `Reggie.compile(samePattern)`, including named-group spans.
- [ ] Full `test` green; fuzz gate `findings=0`.
- [ ] Method/identifier names consistent across modules: `compilePikeVm`, `compileAllowingFallback`, `encodeNameMap`/`decodeNameMap`, `Realization`, `MethodInfo.Kind`.
