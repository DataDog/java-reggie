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
package com.datadoghq.reggie.runtime;

import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Fallback matcher that delegates to {@code java.util.regex} when the reggie engine has a known
 * correctness bug for the given pattern. A warning is logged once at construction time.
 */
public final class JavaRegexFallbackMatcher extends ReggieMatcher {

  private static final Logger LOG = Logger.getLogger(JavaRegexFallbackMatcher.class.getName());

  private final Pattern javaPattern;

  JavaRegexFallbackMatcher(String pattern, String reason) {
    super(pattern);
    this.javaPattern = Pattern.compile(toJdkCompatible(pattern));
    LOG.warning("Falling back to java.util.regex for pattern '" + pattern + "': " + reason);
  }

  /**
   * Converts Perl-style single-quoted named groups and backreferences to JDK angle-bracket syntax:
   * {@code (?'name'...)} → {@code (?<name>...)} and {@code \k'name'} → {@code \k<name>}.
   */
  static String toJdkCompatible(String pattern) {
    if (pattern == null || (!pattern.contains("(?'") && !pattern.contains("\\k'"))) {
      return pattern;
    }
    StringBuilder sb = new StringBuilder(pattern.length());
    int i = 0;
    while (i < pattern.length()) {
      if (pattern.startsWith("(?'", i)) {
        int nameEnd = pattern.indexOf("'", i + 3);
        if (nameEnd > i + 3) {
          sb.append("(?<").append(pattern, i + 3, nameEnd).append(">");
          i = nameEnd + 1;
          continue;
        }
      } else if (pattern.startsWith("\\k'", i)) {
        int nameEnd = pattern.indexOf("'", i + 3);
        if (nameEnd > i + 3) {
          sb.append("\\k<").append(pattern, i + 3, nameEnd).append(">");
          i = nameEnd + 1;
          continue;
        }
      }
      sb.append(pattern.charAt(i++));
    }
    return sb.toString();
  }

  @Override
  public boolean matches(String input) {
    return javaPattern.matcher(input).matches();
  }

  @Override
  public boolean find(String input) {
    return javaPattern.matcher(input).find();
  }

  @Override
  public int findFrom(String input, int start) {
    java.util.regex.Matcher m = javaPattern.matcher(input);
    return m.find(start) ? m.start() : -1;
  }

  @Override
  public MatchResult match(String input) {
    java.util.regex.Matcher m = javaPattern.matcher(input);
    return m.matches() ? toMatchResult(input, m) : null;
  }

  @Override
  public boolean matchesBounded(CharSequence input, int start, int end) {
    java.util.regex.Matcher m = javaPattern.matcher(input);
    m.region(start, end);
    return m.matches();
  }

  @Override
  public MatchResult matchBounded(CharSequence input, int start, int end) {
    java.util.regex.Matcher m = javaPattern.matcher(input);
    m.region(start, end);
    if (!m.matches()) return null;
    String inputStr = input instanceof String ? (String) input : input.toString();
    return toMatchResult(inputStr, m);
  }

  @Override
  public MatchResult findMatch(String input) {
    return findMatchFrom(input, 0);
  }

  @Override
  public MatchResult findMatchFrom(String input, int start) {
    java.util.regex.Matcher m = javaPattern.matcher(input);
    return m.find(start) ? toMatchResult(input, m) : null;
  }

  @Override
  public boolean matchInto(String input, int[] groupStarts, int[] groupEnds) {
    java.util.regex.Matcher m = javaPattern.matcher(input);
    if (!m.matches()) {
      return false;
    }
    copyGroups(m, groupStarts, groupEnds);
    return true;
  }

  @Override
  public boolean findMatchInto(String input, int start, int[] groupStarts, int[] groupEnds) {
    java.util.regex.Matcher m = javaPattern.matcher(input);
    if (!m.find(start)) {
      return false;
    }
    copyGroups(m, groupStarts, groupEnds);
    return true;
  }

  private void copyGroups(java.util.regex.Matcher m, int[] groupStarts, int[] groupEnds) {
    int gc = m.groupCount();
    if (groupStarts.length <= gc || groupEnds.length <= gc) {
      throw new IndexOutOfBoundsException(
          "group arrays must have length at least " + (gc + 1) + " for this pattern");
    }
    for (int i = 0; i <= gc; i++) {
      groupStarts[i] = m.start(i);
      groupEnds[i] = m.end(i);
    }
  }

  private MatchResult toMatchResult(String input, java.util.regex.Matcher m) {
    int gc = m.groupCount();
    int[] starts = new int[gc + 1];
    int[] ends = new int[gc + 1];
    starts[0] = m.start();
    ends[0] = m.end();
    for (int i = 1; i <= gc; i++) {
      starts[i] = m.start(i);
      ends[i] = m.end(i);
    }
    return new MatchResultImpl(input, starts, ends, gc, javaPattern.namedGroups());
  }
}
