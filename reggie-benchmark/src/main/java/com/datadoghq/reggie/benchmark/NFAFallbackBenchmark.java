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
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.openjdk.jmh.annotations.*;

/**
 * JMH benchmark comparing NFA fallback patterns: Reggie vs JDK Pattern. Tests cases where Reggie
 * cannot use fast DFA path.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class NFAFallbackBenchmark {

  // JDK patterns
  private Pattern jdkDuplicateWord;
  private Pattern jdkBackrefWithContent;
  private Pattern jdkRepeatedSequence;
  private Pattern jdkEmailWithGroups;
  private Pattern jdkPhoneWithVariableLength;
  private Pattern jdkXmlTags;
  private Pattern jdkLookaheadWithQuantifier;
  private Pattern jdkLookaheadWithPlusQuantifier;
  private Pattern jdkLookaheadNoBoyerMoore;
  private Pattern jdkMultipleLookaheads;
  private Pattern jdkLargeAlternationWithStar;
  private Pattern jdkMultipleCharClassStars;
  private Pattern jdkOverlappingAlternation;
  private Pattern jdkFixedLengthGroups;
  private Pattern jdkFixedLengthLookbehind;
  private Pattern jdkBoundedQuantifiers;

  // Reggie patterns
  private NFAFallbackPatterns patterns;

  // Test data - backreferences
  private static final String DUPLICATE_WORD_MATCH = "hello hello";
  private static final String DUPLICATE_WORD_NO_MATCH = "hello world";
  private static final String BACKREF_MATCH = "abcXYZabc";
  private static final String BACKREF_NO_MATCH = "abcXYZdef";
  private static final String REPEATED_SEQ_MATCH = "aaaa"; // aa repeated
  private static final String REPEATED_SEQ_NO_MATCH = "aaa";

  // Test data - variable-length groups
  private static final String EMAIL_MATCH = "john@example.com";
  private static final String EMAIL_NO_MATCH = "john@example";
  private static final String PHONE_MATCH = "123-4567890-1234";
  private static final String PHONE_NO_MATCH = "123-45-1234";
  private static final String XML_MATCH = "<div>content</div>";
  private static final String XML_NO_MATCH = "<div>content";

  // Test data - complex assertions
  private static final String QUANTIFIER_LOOKAHEAD_MATCH = "abc123xyz";
  private static final String QUANTIFIER_LOOKAHEAD_NO_MATCH = "abc12xyz";
  private static final String PLUS_LOOKAHEAD_MATCH = "user@example.com";
  private static final String PLUS_LOOKAHEAD_NO_MATCH = "@example.com";
  private static final String NO_BOYER_MOORE_MATCH = "user@domain.org";
  private static final String NO_BOYER_MOORE_NO_MATCH = "@domain.org";
  private static final String MULTI_LOOKAHEAD_MATCH = "user@example.com";
  private static final String MULTI_LOOKAHEAD_NO_MATCH = "@example.com";

  // Test data - state explosion
  private static final String LARGE_ALT_MATCH = "abcdefghijx";
  private static final String LARGE_ALT_NO_MATCH = "abcdefghijw";
  private static final String MULTI_CHARCLASS_MATCH = "abc123ABC!@#";
  private static final String MULTI_CHARCLASS_NO_MATCH = "";
  private static final String OVERLAPPING_ALT_MATCH = "abcdeabcdex";
  private static final String OVERLAPPING_ALT_NO_MATCH = "abcdey";

  // Test data - should use DFA
  private static final String FIXED_GROUPS_MATCH = "abc@def.com";
  private static final String FIXED_GROUPS_NO_MATCH = "ab@def.com";
  private static final String FIXED_LOOKBEHIND_MATCH = "123x";
  private static final String FIXED_LOOKBEHIND_NO_MATCH = "12x";
  private static final String BOUNDED_QUANT_MATCH = "aaaaaabbbbb";
  private static final String BOUNDED_QUANT_NO_MATCH = "aaabbb";

  @Setup
  public void setup() {
    // JDK patterns - backreferences
    jdkDuplicateWord = Pattern.compile("(\\w+)\\s+\\1");
    jdkBackrefWithContent = Pattern.compile("([a-z]{3}).*\\1");
    jdkRepeatedSequence = Pattern.compile("(a+)\\1");

    // JDK patterns - variable-length groups
    jdkEmailWithGroups = Pattern.compile("([a-z]+)@([a-z]+)\\.com");
    jdkPhoneWithVariableLength = Pattern.compile("(\\d{3})-(\\d+)-(\\d{4})");
    jdkXmlTags = Pattern.compile("(<\\w+>).*?(</\\w+>)");

    // JDK patterns - complex assertions
    jdkLookaheadWithQuantifier = Pattern.compile("(?=.*\\d{3})\\w+");
    jdkLookaheadWithPlusQuantifier = Pattern.compile("(?=\\w+@).*@example\\.com");
    jdkLookaheadNoBoyerMoore = Pattern.compile("(?=\\w+@).*@\\w+\\.\\w+");
    jdkMultipleLookaheads = Pattern.compile("(?=\\w+@)(?=.*example).*@\\w+\\.com");

    // JDK patterns - state explosion
    jdkLargeAlternationWithStar = Pattern.compile("(a|b|c|d|e|f|g|h|i|j)*(x|y|z)");
    jdkMultipleCharClassStars = Pattern.compile("[a-z]*[0-9]*[A-Z]*[!@#$]*");
    jdkOverlappingAlternation = Pattern.compile("(a|ab|abc|abcd|abcde)*x");

    // JDK patterns - should use DFA
    jdkFixedLengthGroups = Pattern.compile("([a-z]{3})@([a-z]{3})\\.com");
    jdkFixedLengthLookbehind = Pattern.compile("(?<=[0-9]{3})x");
    jdkBoundedQuantifiers = Pattern.compile("a{5,10}b{3,7}");

    // Reggie patterns
    patterns = Reggie.patterns(NFAFallbackPatterns.class);
  }

  // ==================== BACKREFERENCES ====================

  @Benchmark
  public boolean reggieDuplicateWordMatch() {
    return patterns.duplicateWord().find(DUPLICATE_WORD_MATCH);
  }

  @Benchmark
  public boolean jdkDuplicateWordMatch() {
    return jdkDuplicateWord.matcher(DUPLICATE_WORD_MATCH).find();
  }

  @Benchmark
  public boolean reggieDuplicateWordNoMatch() {
    return patterns.duplicateWord().find(DUPLICATE_WORD_NO_MATCH);
  }

  @Benchmark
  public boolean jdkDuplicateWordNoMatch() {
    return jdkDuplicateWord.matcher(DUPLICATE_WORD_NO_MATCH).find();
  }

  @Benchmark
  public boolean reggieBackrefWithContentMatch() {
    return patterns.backrefWithContent().find(BACKREF_MATCH);
  }

  @Benchmark
  public boolean jdkBackrefWithContentMatch() {
    return jdkBackrefWithContent.matcher(BACKREF_MATCH).find();
  }

  @Benchmark
  public boolean reggieRepeatedSequenceMatch() {
    return patterns.repeatedSequence().find(REPEATED_SEQ_MATCH);
  }

  @Benchmark
  public boolean jdkRepeatedSequenceMatch() {
    return jdkRepeatedSequence.matcher(REPEATED_SEQ_MATCH).find();
  }

  // ==================== VARIABLE-LENGTH GROUPS ====================

  @Benchmark
  public boolean reggieEmailWithGroupsMatch() {
    return patterns.emailWithGroups().find(EMAIL_MATCH);
  }

  @Benchmark
  public boolean jdkEmailWithGroupsMatch() {
    return jdkEmailWithGroups.matcher(EMAIL_MATCH).find();
  }

  @Benchmark
  public boolean reggiePhoneWithVariableLengthMatch() {
    return patterns.phoneWithVariableLength().find(PHONE_MATCH);
  }

  @Benchmark
  public boolean jdkPhoneWithVariableLengthMatch() {
    return jdkPhoneWithVariableLength.matcher(PHONE_MATCH).find();
  }

  @Benchmark
  public boolean reggieXmlTagsMatch() {
    return patterns.xmlTags().find(XML_MATCH);
  }

  @Benchmark
  public boolean jdkXmlTagsMatch() {
    return jdkXmlTags.matcher(XML_MATCH).find();
  }

  // ==================== COMPLEX ASSERTIONS ====================

  @Benchmark
  public boolean reggieLookaheadWithQuantifierMatch() {
    return patterns.lookaheadWithQuantifier().find(QUANTIFIER_LOOKAHEAD_MATCH);
  }

  @Benchmark
  public boolean jdkLookaheadWithQuantifierMatch() {
    return jdkLookaheadWithQuantifier.matcher(QUANTIFIER_LOOKAHEAD_MATCH).find();
  }

  @Benchmark
  public boolean reggieLookaheadWithPlusQuantifierMatch() {
    return patterns.lookaheadWithPlusQuantifier().find(PLUS_LOOKAHEAD_MATCH);
  }

  @Benchmark
  public boolean jdkLookaheadWithPlusQuantifierMatch() {
    return jdkLookaheadWithPlusQuantifier.matcher(PLUS_LOOKAHEAD_MATCH).find();
  }

  @Benchmark
  public boolean reggieLookaheadNoBoyerMooreMatch() {
    return patterns.lookaheadNoBoyerMoore().find(NO_BOYER_MOORE_MATCH);
  }

  @Benchmark
  public boolean jdkLookaheadNoBoyerMooreMatch() {
    return jdkLookaheadNoBoyerMoore.matcher(NO_BOYER_MOORE_MATCH).find();
  }

  @Benchmark
  public boolean reggieMultipleLookaheadsMatch() {
    return patterns.multipleLookaheads().find(MULTI_LOOKAHEAD_MATCH);
  }

  @Benchmark
  public boolean jdkMultipleLookaheadsMatch() {
    return jdkMultipleLookaheads.matcher(MULTI_LOOKAHEAD_MATCH).find();
  }

  // ==================== STATE EXPLOSION ====================

  @Benchmark
  public boolean reggieLargeAlternationWithStarMatch() {
    return patterns.largeAlternationWithStar().find(LARGE_ALT_MATCH);
  }

  @Benchmark
  public boolean jdkLargeAlternationWithStarMatch() {
    return jdkLargeAlternationWithStar.matcher(LARGE_ALT_MATCH).find();
  }

  @Benchmark
  public boolean reggieMultipleCharClassStarsMatch() {
    return patterns.multipleCharClassStars().find(MULTI_CHARCLASS_MATCH);
  }

  @Benchmark
  public boolean jdkMultipleCharClassStarsMatch() {
    return jdkMultipleCharClassStars.matcher(MULTI_CHARCLASS_MATCH).find();
  }

  @Benchmark
  public boolean reggieOverlappingAlternationMatch() {
    return patterns.overlappingAlternation().find(OVERLAPPING_ALT_MATCH);
  }

  @Benchmark
  public boolean jdkOverlappingAlternationMatch() {
    return jdkOverlappingAlternation.matcher(OVERLAPPING_ALT_MATCH).find();
  }

  // ==================== SHOULD USE DFA (for comparison) ====================

  @Benchmark
  public boolean reggieFixedLengthGroupsMatch() {
    return patterns.fixedLengthGroups().find(FIXED_GROUPS_MATCH);
  }

  @Benchmark
  public boolean jdkFixedLengthGroupsMatch() {
    return jdkFixedLengthGroups.matcher(FIXED_GROUPS_MATCH).find();
  }

  @Benchmark
  public boolean reggieFixedLengthLookbehindMatch() {
    return patterns.fixedLengthLookbehind().find(FIXED_LOOKBEHIND_MATCH);
  }

  @Benchmark
  public boolean jdkFixedLengthLookbehindMatch() {
    return jdkFixedLengthLookbehind.matcher(FIXED_LOOKBEHIND_MATCH).find();
  }

  @Benchmark
  public boolean reggieBoundedQuantifiersMatch() {
    return patterns.boundedQuantifiers().find(BOUNDED_QUANT_MATCH);
  }

  @Benchmark
  public boolean jdkBoundedQuantifiersMatch() {
    return jdkBoundedQuantifiers.matcher(BOUNDED_QUANT_MATCH).find();
  }
}
