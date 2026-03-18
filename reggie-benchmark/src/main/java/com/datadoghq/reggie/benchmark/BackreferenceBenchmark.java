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

import com.datadoghq.reggie.runtime.ReggieMatcher;
import com.datadoghq.reggie.runtime.RuntimeCompiler;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.openjdk.jmh.annotations.*;

/**
 * JMH benchmark comparing backreference pattern performance. Backreferences force NFA execution in
 * both Reggie and JDK. Tests patterns like (word)\1 to see how Reggie's NFA compares to JDK's
 * backtracking engine.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class BackreferenceBenchmark {

  // JDK patterns
  private Pattern jdkSimpleBackref;
  private Pattern jdkRepeatedWord;
  private Pattern jdkHtmlTag;
  private Pattern jdkMultipleBackref;

  // Reggie patterns
  private ReggieMatcher reggieSimpleBackref;
  private ReggieMatcher reggieRepeatedWord;
  private ReggieMatcher reggieHtmlTag;
  private ReggieMatcher reggieMultipleBackref;

  // Test data
  private static final String SIMPLE_MATCH = "aa"; // matches (a)\1
  private static final String SIMPLE_NO_MATCH = "ab"; // doesn't match (a)\1
  private static final String REPEATED_WORD_MATCH = "the the"; // matches \b(\w+)\s+\1\b
  private static final String REPEATED_WORD_NO_MATCH = "the cat"; // doesn't match
  private static final String HTML_TAG_MATCH = "<div>content</div>"; // matches <(\w+)>.*</\1>
  private static final String HTML_TAG_NO_MATCH = "<div>content</span>"; // doesn't match
  private static final String MULTIPLE_BACKREF_MATCH = "abab"; // matches (\w)(\w)\1\2
  private static final String MULTIPLE_BACKREF_NO_MATCH = "abcd"; // doesn't match

  @Setup
  public void setup() {
    // JDK patterns
    jdkSimpleBackref = Pattern.compile("(a)\\1");
    jdkRepeatedWord = Pattern.compile("\\b(\\w+)\\s+\\1\\b");
    jdkHtmlTag = Pattern.compile("<(\\w+)>.*</\\1>");
    jdkMultipleBackref = Pattern.compile("(\\w)(\\w)\\1\\2");

    // Reggie patterns
    reggieSimpleBackref = RuntimeCompiler.compile("(a)\\1");
    reggieRepeatedWord = RuntimeCompiler.compile("\\b(\\w+)\\s+\\1\\b");
    reggieHtmlTag = RuntimeCompiler.compile("<(\\w+)>.*</\\1>");
    reggieMultipleBackref = RuntimeCompiler.compile("(\\w)(\\w)\\1\\2");
  }

  // Simple backreference benchmarks
  @Benchmark
  public boolean reggieSimpleBackrefMatch() {
    return reggieSimpleBackref.matches(SIMPLE_MATCH);
  }

  @Benchmark
  public boolean jdkSimpleBackrefMatch() {
    return jdkSimpleBackref.matcher(SIMPLE_MATCH).matches();
  }

  @Benchmark
  public boolean reggieSimpleBackrefNoMatch() {
    return reggieSimpleBackref.matches(SIMPLE_NO_MATCH);
  }

  @Benchmark
  public boolean jdkSimpleBackrefNoMatch() {
    return jdkSimpleBackref.matcher(SIMPLE_NO_MATCH).matches();
  }

  // Repeated word benchmarks
  @Benchmark
  public boolean reggieRepeatedWordMatch() {
    return reggieRepeatedWord.find(REPEATED_WORD_MATCH);
  }

  @Benchmark
  public boolean jdkRepeatedWordMatch() {
    return jdkRepeatedWord.matcher(REPEATED_WORD_MATCH).find();
  }

  @Benchmark
  public boolean reggieRepeatedWordNoMatch() {
    return reggieRepeatedWord.find(REPEATED_WORD_NO_MATCH);
  }

  @Benchmark
  public boolean jdkRepeatedWordNoMatch() {
    return jdkRepeatedWord.matcher(REPEATED_WORD_NO_MATCH).find();
  }

  // HTML tag benchmarks
  @Benchmark
  public boolean reggieHtmlTagMatch() {
    return reggieHtmlTag.matches(HTML_TAG_MATCH);
  }

  @Benchmark
  public boolean jdkHtmlTagMatch() {
    return jdkHtmlTag.matcher(HTML_TAG_MATCH).matches();
  }

  @Benchmark
  public boolean reggieHtmlTagNoMatch() {
    return reggieHtmlTag.matches(HTML_TAG_NO_MATCH);
  }

  @Benchmark
  public boolean jdkHtmlTagNoMatch() {
    return jdkHtmlTag.matcher(HTML_TAG_NO_MATCH).matches();
  }

  // Multiple backreferences benchmarks
  @Benchmark
  public boolean reggieMultipleBackrefMatch() {
    return reggieMultipleBackref.matches(MULTIPLE_BACKREF_MATCH);
  }

  @Benchmark
  public boolean jdkMultipleBackrefMatch() {
    return jdkMultipleBackref.matcher(MULTIPLE_BACKREF_MATCH).matches();
  }

  @Benchmark
  public boolean reggieMultipleBackrefNoMatch() {
    return reggieMultipleBackref.matches(MULTIPLE_BACKREF_NO_MATCH);
  }

  @Benchmark
  public boolean jdkMultipleBackrefNoMatch() {
    return jdkMultipleBackref.matcher(MULTIPLE_BACKREF_NO_MATCH).matches();
  }
}
