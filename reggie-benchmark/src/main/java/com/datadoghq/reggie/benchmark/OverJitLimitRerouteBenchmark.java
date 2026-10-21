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
package com.datadoghq.reggie.benchmark;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggieOptions;
import com.datadoghq.reggie.runtime.ReggieMatcher;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.openjdk.jmh.annotations.*;

/**
 * Patterns whose generated method exceeds HotSpot's 8000-bytecode JIT limit, compiled with JDK
 * fallback allowed. Depending on the routing in {@code RuntimeCompiler}, such a pattern runs on the
 * Laurikari/JDK split ({@code OverJitSplitMatcher}), on BitState, or on the JDK fallback; the
 * {@code jdk*} methods run {@code java.util.regex} directly for reference.
 *
 * <p>Each pattern is selected by a short id; {@link #PATTERNS} maps it to the regex and the text
 * that matches it. The short input is about 100 chars, the long inputs about 10k chars; the match
 * (when there is one) is at the end, since every pattern ends with {@code $} or {@code \Z}. The
 * no-match input repeats the matching text inside the filler, so it holds every character the
 * pattern requires, and ends in filler, so the end anchor never matches.
 *
 * <p>The {@code *Dense} methods cycle through {@value #DENSE_COUNT} random 400-char strings over
 * {@code [a-f0-9 ]} (fixed seed), on a matcher of their own: for {@code alnumDigitsEnd}, the
 * setup's check of these inputs fills the Laurikari DFA cache of that split matcher, which then
 * sends every search call to its JDK half, so the measured calls run after that point. For the
 * other patterns, these inputs stay below the cache cap.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class OverJitLimitRerouteBenchmark {

  /** id -> {regex, matching text}. */
  private static final Map<String, String[]> PATTERNS =
      Map.of(
          "neverMatchNl",
          new String[] {"[^a]{3,30}b+(?:\\n{2}\\n{1,25}\\n$)\\d{1,25}", "xyzb\n\n\n\n1"},
          "alnumDigitsEnd",
          new String[] {"[a-z]{2,30}[a-z0-9]{2,30}\\d{2,30}$", "session7f3a9b2c1d0e12345"},
          "hexDigitsEndZ",
          new String[] {"[0-9a-f]{4,40}[0-9]{4,40}\\Z", "deadbeef0123456789"},
          "emailEnd",
          new String[] {"[\\w.-]{1,40}@[\\w.-]{1,40}\\.[a-z]{2,6}$", "john.doe@mail.example.com"},
          "hostPortEnd",
          new String[] {"[A-Za-z ]{1,60}[A-Za-z]{1,40}:\\d{1,5}$", "Server Host Name:8080"},
          "domainEnd",
          new String[] {"\\w{1,30}[\\w.]{1,40}\\.(?:com|org|net)$", "www.example.com"});

  private static final String[] WORDS = {
    "lorem", "ipsum", "dolor", "sit", "amet", "consectetur", "adipiscing", "elit", "sed", "do",
    "eiusmod", "tempor", "incididunt", "ut", "labore", "et", "dolore", "magna", "aliqua", "Ut",
    "enim", "ad", "minim", "veniam,", "quis", "nostrud", "exercitation", "ullamco", "laboris."
  };

  @Param({
    "neverMatchNl",
    "alnumDigitsEnd",
    "hexDigitsEndZ",
    "emailEnd",
    "hostPortEnd",
    "domainEnd"
  })
  public String pattern;

  private ReggieMatcher reggie;
  private ReggieMatcher reggieDense;
  private Pattern jdk;

  private String shortInput;
  private String longInput;
  private String longNoMatchInput;
  private String matchText;

  static final int DENSE_COUNT = 64;
  private final String[] denseInputs = new String[DENSE_COUNT];
  private int denseIndex;

  @Setup(Level.Trial)
  public void setup() {
    String[] entry = PATTERNS.get(pattern);
    if (entry == null) {
      throw new IllegalArgumentException("unknown pattern id: " + pattern);
    }
    Reggie.clearCache();
    ReggieOptions fallback = ReggieOptions.builder().allowJdkFallback().build();
    reggie = Reggie.compile(entry[0], fallback);
    reggieDense = Reggie.compile(entry[0], fallback);
    jdk = Pattern.compile(entry[0]);
    matchText = entry[1];

    String suffix = " " + matchText;
    shortInput = filler(100 - suffix.length()) + suffix;
    longInput = filler(10_000 - suffix.length()) + suffix;
    StringBuilder noMatch = new StringBuilder(10_000 + 64);
    while (noMatch.length() < 10_000) {
      noMatch.append(filler(200)).append(suffix).append(' ');
    }
    longNoMatchInput = noMatch.append(filler(200)).toString();

    for (String in : new String[] {shortInput, longInput, longNoMatchInput}) {
      if (reggie.find(in) != jdk.matcher(in).find()) {
        throw new IllegalStateException(pattern + ": find disagrees with the JDK");
      }
    }
    if (reggie.matches(matchText) != jdk.matcher(matchText).matches()) {
      throw new IllegalStateException(pattern + ": matches disagrees with the JDK");
    }
    if (jdk.matcher(longNoMatchInput).find()) {
      throw new IllegalStateException(pattern + ": no-match input matches");
    }

    Random random = new Random(42);
    String alphabet = "abcdef0123456789 ";
    for (int n = 0; n < DENSE_COUNT; n++) {
      char[] c = new char[400];
      for (int i = 0; i < c.length; i++) {
        c[i] = alphabet.charAt(random.nextInt(alphabet.length()));
      }
      denseInputs[n] = new String(c);
    }
    for (String in : denseInputs) {
      if (reggieDense.find(in) != jdk.matcher(in).find()
          || reggieDense.matches(in) != jdk.matcher(in).matches()) {
        throw new IllegalStateException(pattern + ": dense input disagrees with the JDK");
      }
    }
  }

  private String nextDense() {
    int i = denseIndex;
    denseIndex = (i + 1) & (DENSE_COUNT - 1);
    return denseInputs[i];
  }

  /** Deterministic word filler of exactly {@code length} chars, with no digit, '@' or ':'. */
  private static String filler(int length) {
    StringBuilder sb = new StringBuilder(length + 16);
    int i = 0;
    while (sb.length() < length) {
      if (sb.length() > 0) {
        sb.append(' ');
      }
      sb.append(WORDS[i++ % WORDS.length]);
    }
    sb.setLength(length);
    return sb.toString();
  }

  @Benchmark
  public boolean reggieFindShort() {
    return reggie.find(shortInput);
  }

  @Benchmark
  public boolean reggieFindLong() {
    return reggie.find(longInput);
  }

  @Benchmark
  public boolean reggieFindNoMatch() {
    return reggie.find(longNoMatchInput);
  }

  @Benchmark
  public boolean reggieMatches() {
    return reggie.matches(matchText);
  }

  @Benchmark
  public boolean reggieFindDense() {
    return reggieDense.find(nextDense());
  }

  @Benchmark
  public boolean reggieMatchesDense() {
    return reggieDense.matches(nextDense());
  }

  @Benchmark
  public boolean jdkFindShort() {
    return jdk.matcher(shortInput).find();
  }

  @Benchmark
  public boolean jdkFindLong() {
    return jdk.matcher(longInput).find();
  }

  @Benchmark
  public boolean jdkFindNoMatch() {
    return jdk.matcher(longNoMatchInput).find();
  }

  @Benchmark
  public boolean jdkMatches() {
    return jdk.matcher(matchText).matches();
  }

  @Benchmark
  public boolean jdkFindDense() {
    return jdk.matcher(nextDense()).find();
  }

  @Benchmark
  public boolean jdkMatchesDense() {
    return jdk.matcher(nextDense()).matches();
  }
}
