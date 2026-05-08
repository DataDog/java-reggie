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

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class MatchCursorTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  // 1. MatchCursor implements Iterator and AutoCloseable
  @Test
  void testImplementsIteratorAndAutoCloseable() {
    ReggieMatcher m = RuntimeCompiler.compile("\\d+");
    MatchCursor cursor = m.cursor("abc");
    assertTrue(cursor instanceof java.util.Iterator);
    assertTrue(cursor instanceof AutoCloseable);
  }

  // 2. findNext() basic iteration
  @Test
  void testFindNextBasicIteration() {
    ReggieMatcher m = RuntimeCompiler.compile("\\d+");
    MatchCursor cursor = m.cursor("abc 123 def 456");
    MatchResult first = cursor.findNext();
    assertNotNull(first);
    assertEquals("123", first.group());
    MatchResult second = cursor.findNext();
    assertNotNull(second);
    assertEquals("456", second.group());
    assertNull(cursor.findNext());
  }

  // 3. findNext() empty input / no matches
  @Test
  void testFindNextNoMatches() {
    ReggieMatcher m = RuntimeCompiler.compile("\\d+");
    MatchCursor cursor = m.cursor("no digits here");
    assertNull(cursor.findNext());
  }

  @Test
  void testFindNextEmptyInput() {
    ReggieMatcher m = RuntimeCompiler.compile("\\d+");
    MatchCursor cursor = m.cursor("");
    assertNull(cursor.findNext());
  }

  // 4. reset() after exhaustion starts fresh
  @Test
  void testResetAfterExhaustion() {
    ReggieMatcher m = RuntimeCompiler.compile("\\d+");
    MatchCursor cursor = m.cursor("123");
    cursor.findNext(); // consume match
    assertNull(cursor.findNext()); // exhausted
    cursor.reset("456");
    MatchResult result = cursor.findNext();
    assertNotNull(result);
    assertEquals("456", result.group());
  }

  // 5. reset() mid-iteration
  @Test
  void testResetMidIteration() {
    ReggieMatcher m = RuntimeCompiler.compile("\\d+");
    MatchCursor cursor = m.cursor("111 222 333");
    MatchResult first = cursor.findNext();
    assertNotNull(first);
    assertEquals("111", first.group());
    cursor.reset("999");
    MatchResult after = cursor.findNext();
    assertNotNull(after);
    assertEquals("999", after.group());
  }

  // 6. Non-overlapping sequential matches
  @Test
  void testNonOverlappingSequentialMatches() {
    ReggieMatcher m = RuntimeCompiler.compile("[a-z]+");
    MatchCursor cursor = m.cursor("hello world foo");
    List<String> words = new ArrayList<>();
    MatchResult r;
    while ((r = cursor.findNext()) != null) {
      words.add(r.group());
    }
    assertEquals(List.of("hello", "world", "foo"), words);
  }

  // 7. Numeric backreferences ($1, $2)
  @Test
  void testNumericBackreferences() {
    ReggieMatcher m = RuntimeCompiler.compile("(\\w+)@(\\w+)");
    MatchCursor cursor = m.cursor("user@host");
    assertNotNull(cursor.findNext());
    StringBuilder sb = new StringBuilder();
    cursor.appendReplacement(sb, "$2/$1");
    cursor.appendTail(sb);
    assertEquals("host/user", sb.toString());
  }

  // 8. Named backreferences (${name})
  @Test
  void testNamedBackreferences() {
    ReggieMatcher m = RuntimeCompiler.compile("(?<user>\\w+)@(?<domain>\\w+)");
    MatchCursor cursor = m.cursor("alice@example");
    assertNotNull(cursor.findNext());
    StringBuilder sb = new StringBuilder();
    cursor.appendReplacement(sb, "${domain}/${user}");
    cursor.appendTail(sb);
    assertEquals("example/alice", sb.toString());
  }

  // 9. Non-participating group → empty string
  @Test
  void testNonParticipatingGroupEmitsEmpty() {
    ReggieMatcher m = RuntimeCompiler.compile("(a)?(b)");
    MatchCursor cursor = m.cursor("b");
    assertNotNull(cursor.findNext());
    StringBuilder sb = new StringBuilder();
    cursor.appendReplacement(sb, "[$1][$2]");
    cursor.appendTail(sb);
    assertEquals("[][b]", sb.toString());
  }

  // 10. appendReplacement returns this (fluent)
  @Test
  void testAppendReplacementIsFluent() {
    ReggieMatcher m = RuntimeCompiler.compile("x");
    MatchCursor cursor = m.cursor("x");
    cursor.findNext();
    StringBuilder sb = new StringBuilder();
    assertSame(cursor, cursor.appendReplacement(sb, "y"));
  }

  // 11. appendTail returns the StringBuilder
  @Test
  void testAppendTailReturnsSb() {
    ReggieMatcher m = RuntimeCompiler.compile("x");
    MatchCursor cursor = m.cursor("xrest");
    cursor.findNext();
    StringBuilder sb = new StringBuilder();
    cursor.appendReplacement(sb, "y");
    StringBuilder returned = cursor.appendTail(sb);
    assertSame(sb, returned);
    assertEquals("yrest", sb.toString());
  }

  // 12. Thread-safety: 10 threads each with their own matcher and cursor
  // (ReggieMatcher NFA state is mutable; sharing across threads requires external sync)
  @Test
  void testThreadSafetyEachThreadOwnMatcher() throws Exception {
    String pattern = "\\d+";
    int threads = 10;
    String input = "a1b2c3";
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    List<Future<String>> futures = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      futures.add(
          pool.submit(
              () -> {
                ReggieMatcher m = RuntimeCompiler.compile(pattern);
                MatchCursor cursor = m.cursor(input);
                ready.countDown();
                start.await();
                StringBuilder sb = new StringBuilder();
                MatchResult r;
                while ((r = cursor.findNext()) != null) {
                  cursor.appendReplacement(sb, "[$0]");
                }
                cursor.appendTail(sb);
                return sb.toString();
              }));
    }
    ready.await();
    start.countDown();
    pool.shutdown();
    for (Future<String> f : futures) {
      assertEquals("a[1]b[2]c[3]", f.get());
    }
  }

  // 13. appendReplacement before findNext → IllegalStateException
  @Test
  void testAppendReplacementBeforeFindNext() {
    ReggieMatcher m = RuntimeCompiler.compile("x");
    MatchCursor cursor = m.cursor("x");
    assertThrows(
        IllegalStateException.class, () -> cursor.appendReplacement(new StringBuilder(), "y"));
  }

  // 14. appendReplacement after exhaustion → IllegalStateException
  @Test
  void testAppendReplacementAfterExhaustion() {
    ReggieMatcher m = RuntimeCompiler.compile("x");
    MatchCursor cursor = m.cursor("x");
    cursor.findNext(); // consumed into lastMatch
    StringBuilder sb = new StringBuilder();
    cursor.appendReplacement(sb, "y"); // consumes lastMatch
    // now no active match
    assertThrows(IllegalStateException.class, () -> cursor.appendReplacement(sb, "z"));
  }

  // 15. appendTail called twice → IllegalStateException on second call
  @Test
  void testAppendTailTwiceThrows() {
    ReggieMatcher m = RuntimeCompiler.compile("x");
    MatchCursor cursor = m.cursor("x");
    cursor.findNext();
    StringBuilder sb = new StringBuilder();
    cursor.appendReplacement(sb, "y");
    cursor.appendTail(sb);
    assertThrows(IllegalStateException.class, () -> cursor.appendTail(sb));
  }

  // 16. findNext skip (no appendReplacement between two findNext calls)
  @Test
  void testFindNextSkip() {
    ReggieMatcher m = RuntimeCompiler.compile("\\d+");
    MatchCursor cursor = m.cursor("1 2 3");
    MatchResult first = cursor.findNext();
    assertNotNull(first);
    assertEquals("1", first.group());
    // searchPos advances on each findNext() regardless of appendReplacement
    MatchResult second = cursor.findNext();
    assertNotNull(second);
    assertEquals("2", second.group());
  }

  // 17. findNext after appendTail → null
  @Test
  void testFindNextAfterAppendTailReturnsNull() {
    ReggieMatcher m = RuntimeCompiler.compile("\\d+");
    MatchCursor cursor = m.cursor("123");
    cursor.findNext();
    StringBuilder sb = new StringBuilder();
    cursor.appendReplacement(sb, "X");
    cursor.appendTail(sb);
    assertNull(cursor.findNext());
  }

  // 18. close() is idempotent
  @Test
  void testCloseIsIdempotent() {
    ReggieMatcher m = RuntimeCompiler.compile("x");
    MatchCursor cursor = m.cursor("x");
    assertDoesNotThrow(cursor::close);
    assertDoesNotThrow(cursor::close);
    assertNull(cursor.findNext());
  }

  // 19. Coexists with replaceAll(Function)
  @Test
  void testCoexistsWithReplaceAll() {
    ReggieMatcher m = RuntimeCompiler.compile("\\d+");
    String ra = m.replaceAll("a1b2c3", mr -> "[" + mr.group() + "]");
    assertEquals("a[1]b[2]c[3]", ra);
    // cursor usage after replaceAll
    MatchCursor cursor = m.cursor("x9y");
    MatchResult r = cursor.findNext();
    assertNotNull(r);
    assertEquals("9", r.group());
  }

  // 20. hasNext()/next() basic iteration
  @Test
  void testHasNextNextIteration() {
    ReggieMatcher m = RuntimeCompiler.compile("\\d+");
    MatchCursor cursor = m.cursor("a1b2c3");
    List<String> groups = new ArrayList<>();
    while (cursor.hasNext()) {
      groups.add(cursor.next().group());
    }
    assertEquals(List.of("1", "2", "3"), groups);
    assertFalse(cursor.hasNext());
  }

  // 21. hasNext() then findNext() consumes peeked buffer — no skip
  @Test
  void testHasNextThenFindNextNoDuplicate() {
    ReggieMatcher m = RuntimeCompiler.compile("\\d+");
    MatchCursor cursor = m.cursor("1 2");
    assertTrue(cursor.hasNext());
    MatchResult r = cursor.findNext();
    assertNotNull(r);
    assertEquals("1", r.group()); // must not skip to "2"
    assertEquals("2", cursor.findNext().group());
    assertNull(cursor.findNext());
  }

  // 22. mix hasNext()/next() with appendReplacement — peeked buffer cleared correctly
  @Test
  void testHasNextMixedWithAppendReplacement() {
    ReggieMatcher m = RuntimeCompiler.compile("\\d+");
    MatchCursor cursor = m.cursor("a1b2c3");
    StringBuilder sb = new StringBuilder();
    while (cursor.hasNext()) {
      cursor.findNext(); // consume peeked into lastMatch
      cursor.appendReplacement(sb, "[$0]");
    }
    cursor.appendTail(sb);
    assertEquals("a[1]b[2]c[3]", sb.toString());
  }

  // 24. Full streaming pipeline end-to-end
  @Test
  void testFullStreamingPipeline() {
    ReggieMatcher m = RuntimeCompiler.compile("(\\w+)@(\\w+)\\.(\\w+)");
    String input = "Send to alice@example.com and bob@test.org please";
    StringBuilder sb = new StringBuilder();
    try (MatchCursor cursor = m.cursor(input)) {
      MatchResult r;
      while ((r = cursor.findNext()) != null) {
        cursor.appendReplacement(sb, "$1 at $2 dot $3");
      }
      cursor.appendTail(sb);
    }
    assertEquals("Send to alice at example dot com and bob at test dot org please", sb.toString());
  }
}
