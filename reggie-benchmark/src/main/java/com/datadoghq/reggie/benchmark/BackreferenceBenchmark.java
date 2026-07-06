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
  private Pattern jdkSelfRefRepeated;
  private Pattern jdkSelfRefFourGroups;

  // Reggie patterns
  private ReggieMatcher reggieSimpleBackref;
  private ReggieMatcher reggieRepeatedWord;
  private ReggieMatcher reggieHtmlTag;
  private ReggieMatcher reggieMultipleBackref;
  private ReggieMatcher reggieSelfRefRepeated;
  private ReggieMatcher reggieSelfRefFourGroups;

  // Test data
  private static final String SIMPLE_MATCH = "aa"; // matches (a)\1
  private static final String SIMPLE_NO_MATCH = "ab"; // doesn't match (a)\1
  private static final String REPEATED_WORD_MATCH = "the the"; // matches \b(\w+)\s+\1\b
  private static final String REPEATED_WORD_NO_MATCH = "the cat"; // doesn't match
  private static final String HTML_TAG_MATCH = "<div>content</div>"; // matches <(\w+)>.*</\1>
  private static final String HTML_TAG_NO_MATCH = "<div>content</span>"; // doesn't match
  private static final String MULTIPLE_BACKREF_MATCH = "abab"; // matches (\w)(\w)\1\2
  private static final String MULTIPLE_BACKREF_NO_MATCH = "abcd"; // doesn't match
  private static final String SELF_REF_MATCH_4 = "aaaa"; // matches (a\1?){4} and 4-group variant
  private static final String SELF_REF_MATCH_6 = "aaaaaa"; // matches 4-group variant
  private static final String SELF_REF_NO_MATCH = "aaa"; // doesn't match either

  // Adversarial data for \b(\w+)\s+\1\b (routes to PINNED_BACKREFERENCE): many distinct
  // consecutive words, each word boundary a false start the old retry-loop codegen would have
  // to re-examine before finding (or failing to find) an actual repeat.
  private static final String REPEATED_WORD_ADVERSARIAL_PREFIX =
      "the quick brown fox jumps over lazy dog while cat sits under table near window ".repeat(30);
  private static final String REPEATED_WORD_ADVERSARIAL_MATCH =
      REPEATED_WORD_ADVERSARIAL_PREFIX + "banana banana";
  private static final String REPEATED_WORD_ADVERSARIAL_NO_MATCH =
      REPEATED_WORD_ADVERSARIAL_PREFIX + "banana";

  // Adversarial data for <(\w+)>.*</\1> (stays on SPECIALIZED_BACKREFERENCE's retry-loop
  // codegen): many closing tags with different names between the opening <div> and the real
  // (or missing) </div>, each "</...>" a false start for the retry loop.
  private static final String HTML_TAG_ADVERSARIAL_CONTENT =
      ("<span>x</span><a>y</a><p>z</p><b>w</b><i>v</i><em>u</em><small>t</small>"
              + "<strong>s</strong><code>r</code><mark>q</mark>")
          .repeat(20);
  private static final String HTML_TAG_ADVERSARIAL_MATCH =
      "<div>" + HTML_TAG_ADVERSARIAL_CONTENT + "</div>";
  private static final String HTML_TAG_ADVERSARIAL_NO_MATCH =
      "<div>" + HTML_TAG_ADVERSARIAL_CONTENT + "</section>";

  @Setup
  public void setup() {
    // JDK patterns
    jdkSimpleBackref = Pattern.compile("(a)\\1");
    jdkRepeatedWord = Pattern.compile("\\b(\\w+)\\s+\\1\\b");
    jdkHtmlTag = Pattern.compile("<(\\w+)>.*</\\1>");
    jdkMultipleBackref = Pattern.compile("(\\w)(\\w)\\1\\2");
    jdkSelfRefRepeated = Pattern.compile("^(a\\1?){4}$");
    jdkSelfRefFourGroups = Pattern.compile("^(a\\1?)(a\\1?)(a\\2?)(a\\3?)$");

    // Reggie patterns
    reggieSimpleBackref = RuntimeCompiler.compile("(a)\\1");
    reggieRepeatedWord = RuntimeCompiler.compile("\\b(\\w+)\\s+\\1\\b");
    reggieHtmlTag = RuntimeCompiler.compile("<(\\w+)>.*</\\1>");
    reggieMultipleBackref = RuntimeCompiler.compile("(\\w)(\\w)\\1\\2");
    reggieSelfRefRepeated = RuntimeCompiler.compile("^(a\\1?){4}$");
    reggieSelfRefFourGroups = RuntimeCompiler.compile("^(a\\1?)(a\\1?)(a\\2?)(a\\3?)$");
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

  // Adversarial repeated-word benchmarks: PINNED_BACKREFERENCE's single-pass forward scan vs.
  // JDK's backtracking, over many false-start word boundaries.
  @Benchmark
  public boolean reggieRepeatedWordAdversarialMatch() {
    return reggieRepeatedWord.find(REPEATED_WORD_ADVERSARIAL_MATCH);
  }

  @Benchmark
  public boolean jdkRepeatedWordAdversarialMatch() {
    return jdkRepeatedWord.matcher(REPEATED_WORD_ADVERSARIAL_MATCH).find();
  }

  @Benchmark
  public boolean reggieRepeatedWordAdversarialNoMatch() {
    return reggieRepeatedWord.find(REPEATED_WORD_ADVERSARIAL_NO_MATCH);
  }

  @Benchmark
  public boolean jdkRepeatedWordAdversarialNoMatch() {
    return jdkRepeatedWord.matcher(REPEATED_WORD_ADVERSARIAL_NO_MATCH).find();
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

  // Adversarial HTML-tag benchmarks: SPECIALIZED_BACKREFERENCE's existing retry-loop codegen vs.
  // JDK's backtracking, over many false-start "</...>" occurrences before the real (or missing)
  // closing tag.
  @Benchmark
  public boolean reggieHtmlTagAdversarialMatch() {
    return reggieHtmlTag.matches(HTML_TAG_ADVERSARIAL_MATCH);
  }

  @Benchmark
  public boolean jdkHtmlTagAdversarialMatch() {
    return jdkHtmlTag.matcher(HTML_TAG_ADVERSARIAL_MATCH).matches();
  }

  @Benchmark
  public boolean reggieHtmlTagAdversarialNoMatch() {
    return reggieHtmlTag.matches(HTML_TAG_ADVERSARIAL_NO_MATCH);
  }

  @Benchmark
  public boolean jdkHtmlTagAdversarialNoMatch() {
    return jdkHtmlTag.matcher(HTML_TAG_ADVERSARIAL_NO_MATCH).matches();
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

  // Self-referencing backreference benchmarks: ^(a\1?){4}$
  @Benchmark
  public boolean reggieSelfRefRepeatedMatch() {
    return reggieSelfRefRepeated.matches(SELF_REF_MATCH_4);
  }

  @Benchmark
  public boolean jdkSelfRefRepeatedMatch() {
    return jdkSelfRefRepeated.matcher(SELF_REF_MATCH_4).matches();
  }

  @Benchmark
  public boolean reggieSelfRefRepeatedNoMatch() {
    return reggieSelfRefRepeated.matches(SELF_REF_NO_MATCH);
  }

  @Benchmark
  public boolean jdkSelfRefRepeatedNoMatch() {
    return jdkSelfRefRepeated.matcher(SELF_REF_NO_MATCH).matches();
  }

  // Self-referencing backreference benchmarks: ^(a\1?)(a\1?)(a\2?)(a\3?)$
  @Benchmark
  public boolean reggieSelfRefFourGroupsMatch4() {
    return reggieSelfRefFourGroups.matches(SELF_REF_MATCH_4);
  }

  @Benchmark
  public boolean jdkSelfRefFourGroupsMatch4() {
    return jdkSelfRefFourGroups.matcher(SELF_REF_MATCH_4).matches();
  }

  @Benchmark
  public boolean reggieSelfRefFourGroupsMatch6() {
    return reggieSelfRefFourGroups.matches(SELF_REF_MATCH_6);
  }

  @Benchmark
  public boolean jdkSelfRefFourGroupsMatch6() {
    return jdkSelfRefFourGroups.matcher(SELF_REF_MATCH_6).matches();
  }

  @Benchmark
  public boolean reggieSelfRefFourGroupsNoMatch() {
    return reggieSelfRefFourGroups.matches(SELF_REF_NO_MATCH);
  }

  @Benchmark
  public boolean jdkSelfRefFourGroupsNoMatch() {
    return jdkSelfRefFourGroups.matcher(SELF_REF_NO_MATCH).matches();
  }
}
