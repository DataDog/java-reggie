/*
 * Copyright 2026-Present Datadog, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datadoghq.reggie.benchmark.engines;

import java.io.File;
import java.util.Locale;

/**
 * JNI binding to the Rust {@code regex} crate (regex-automata meta engine: hybrid lazy DFA + SIMD
 * literal prefilters) — the same engine family as the production dd_sds scanner — used as the
 * fourth comparison engine in the benchmark suite (JDK / reggie / RE2J / rust).
 *
 * <p>The native library lives in {@code reggie-benchmark/rust-regex-engine} and is built with
 * {@code ./gradlew :reggie-benchmark:buildRustEngine} (requires cargo). When the library is not
 * built, {@link #isAvailable()} returns false and {@link #compile(String)} throws {@link
 * UnavailableException} — other engines' benchmarks are unaffected, and the rust lanes report clear
 * errors if run without the library.
 *
 * <p>Per-call JNI string marshalling (UTF-8 conversion) is deliberately included in the timed path:
 * it is the same boundary cost the production FFI engine pays per event.
 *
 * <p>Two compile semantics:
 *
 * <ul>
 *   <li>{@link #compile(String)} — scan semantics: {@code isMatch} = "matches somewhere" (Rust
 *       {@code is_match}), {@code find} = first match somewhere.
 *   <li>{@link #compileFullMatch(String)} — JDK {@code Matcher.matches()} parity, via {@code
 *       \\A(?:pattern)\\z} wrapping.
 * </ul>
 *
 * <p>Patterns the Rust engine cannot serve (unsupported syntax, or NFA size limits — e.g.
 * bounded-quantifier families such as the semver {@code {0,256}} pattern) throw {@link
 * PatternUnsupportedException}; coverage gaps are part of what this lane measures.
 */
public final class RustRegexEngine implements AutoCloseable {

  private static final String LIB_NAME = "rust_regex_engine";
  private static volatile Boolean available;
  private long handle;

  private RustRegexEngine(long handle) {
    this.handle = handle;
  }

  // ---- native interface (see rust-regex-engine/src/lib.rs) ----

  private static native long rxCompile(String pattern);

  private static native boolean rxIsMatch(long handle, String input);

  private static native long rxFind(long handle, String input);

  private static native void rxDispose(long handle);

  // ---- loading ----

  /** Returns true when the native engine is built and loadable. */
  public static boolean isAvailable() {
    Boolean loaded = available;
    if (loaded == null) {
      synchronized (RustRegexEngine.class) {
        if (available == null) {
          available = loadLibrary();
        }
        loaded = available;
      }
    }
    return loaded;
  }

  private static boolean loadLibrary() {
    String envPath = System.getenv("RUST_REGEX_ENGINE_LIB");
    if (envPath != null && new File(envPath).canRead()) {
      System.load(envPath);
      return true;
    }
    String dylib = "lib" + LIB_NAME + ".dylib";
    String so = "lib" + LIB_NAME + ".so";
    for (String rel :
        new String[] {
          "reggie-benchmark/rust-regex-engine/target/release/", "rust-regex-engine/target/release/"
        }) {
      for (String lib : new String[] {rel + dylib, rel + so}) {
        File f = new File(lib);
        if (f.canRead()) {
          System.load(f.getAbsolutePath());
          return true;
        }
      }
    }
    return false;
  }

  private static void requireAvailable() {
    if (!isAvailable()) {
      throw new UnavailableException(
          "rust regex engine library not built; run ./gradlew :reggie-benchmark:buildRustEngine"
              + " (requires cargo) or set RUST_REGEX_ENGINE_LIB");
    }
  }

  // ---- compile / match API ----

  /**
   * Compiles with scan semantics: {@code isMatch(input)} is true when the pattern matches anywhere.
   */
  public static RustRegexEngine compile(String pattern) {
    return compileRaw(pattern);
  }

  /** Compiles for JDK {@code matches()} parity (anchored both ends). */
  public static RustRegexEngine compileFullMatch(String pattern) {
    return compileRaw("\\A(?:" + pattern + ")\\z");
  }

  private static RustRegexEngine compileRaw(String pattern) {
    requireAvailable();
    long handle = rxCompile(pattern);
    if (handle == 0) {
      throw new PatternUnsupportedException(
          "rust regex engine refused pattern (syntax or NFA size limit, 256MB): " + pattern);
    }
    return new RustRegexEngine(handle);
  }

  /** Unanchored "matches somewhere" — mirrors Rust {@code is_match}. */
  public boolean isMatch(String input) {
    return rxIsMatch(handle, input);
  }

  /** True when the pattern matches somewhere in the input. */
  public boolean find(String input) {
    return rxFind(handle, input) >= 0;
  }

  /** Disposes the native compiled regex. Idempotent; the engine is unusable afterwards. */
  @Override
  public void close() {
    long h = handle;
    if (h != 0) {
      handle = 0; // before dispose: a second close() must not re-free the native box
      rxDispose(h);
    }
  }

  // ---- exceptions ----

  /** Thrown when the native engine is not built or not loadable. */
  public static final class UnavailableException extends IllegalStateException {
    public UnavailableException(String message) {
      super(message);
    }
  }

  /** Thrown when the Rust engine cannot compile a pattern (syntax or size limits). */
  public static final class PatternUnsupportedException extends UnsupportedOperationException {
    public PatternUnsupportedException(String message) {
      super(message);
    }
  }

  // ---- smoke validation: java engines.RustRegexEngine ----

  /** The semver {0,256} pattern family: known to exceed this engine's NFA size limits. */
  private static final String SEMVER_COUNTED_PATTERN =
      "^(?<major>0|[1-9]\\d{0,256})\\.(?<minor>0|[1-9]\\d{0,256})\\.(?<patch>0|[1-9]\\d{0,256})(?:-(?<prerelease>(?:0|[1-9]\\d{0,256}|\\d{0,256}[a-zA-Z-][0-9a-zA-Z-]{0,256})(?:\\.(?:0|[1-9]\\d{0,256}|\\d{0,256}[a-zA-Z-][0-9a-zA-Z-]{0,256})){0,256}))?(?:\\+(?<buildmetadata>[0-9a-zA-Z-]{0,40}(?:\\.[0-9a-zA-Z-]{0,40}){0,256}))?$";

  public static void main(String[] args) {
    System.out.println("available=" + isAvailable());
    if (!isAvailable()) {
      return;
    }
    String[][] cases = {
      {"\\d{3}-\\d{3}-\\d{4}", "555-123-4567", "555-12-4567", "fullmatch"},
      {
        "(?i)(password|secret)\\s*[=:]\\s*\\S+",
        "log line with password = hunter2",
        "no secrets here",
        "scan"
      },
    };
    int pass = 0;
    for (String[] c : cases) {
      String pattern = c[0], hit = c[1], miss = c[2];
      boolean full = c[3].equals("fullmatch");
      RustRegexEngine rust = null;
      java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(pattern);
      try {
        rust = full ? RustRegexEngine.compileFullMatch(pattern) : RustRegexEngine.compile(pattern);
      } catch (PatternUnsupportedException e) {
        System.out.println("REFUSED " + pattern + " (" + e.getMessage() + ")");
        continue;
      }
      boolean jdkHit = full ? jdk.matcher(hit).matches() : jdk.matcher(hit).find();
      boolean jdkMiss = full ? jdk.matcher(miss).matches() : jdk.matcher(miss).find();
      boolean rHit = rust.isMatch(hit);
      boolean rMiss = rust.isMatch(miss);
      boolean ok = rHit == jdkHit && rMiss == jdkMiss && jdkHit;
      System.out.printf(
          Locale.ROOT,
          "%s %s hit(hit=%b/%b miss=%b/%b)%n",
          ok ? "OK " : "FAIL",
          pattern,
          jdkHit,
          rHit,
          jdkMiss,
          rMiss);
      if (ok) {
        pass++;
      }
      rust.close();
    }
    // counted-quantifier wall: expected refusal
    try {
      RustRegexEngine.compile(SEMVER_COUNTED_PATTERN);
      System.out.println(
          "NOTE: semver counted pattern compiled (unexpected for this engine family)");
    } catch (PatternUnsupportedException e) {
      System.out.println("OK refused counted-quantifier pattern (expected for this engine family)");
      pass++;
    }
    System.out.println("smoke: " + pass + " checks passed");
  }
}
