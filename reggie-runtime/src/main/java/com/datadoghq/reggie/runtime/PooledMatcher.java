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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Thread-shareable wrapper around a matcher that writes instance state while matching. Every call
 * takes the pooled instance from a single slot, or creates one through {@code factory} when the
 * slot is empty (a concurrent or re-entrant call holds it), and puts it back after a normal return.
 * Calls that do not overlap reuse one instance and allocate nothing; an instance whose call throws
 * is dropped.
 *
 * <p>Used by annotation-processor generated {@code $Impl} classes, which cache one matcher per
 * method, and for other matchers that are cached and shared across threads.
 */
public final class PooledMatcher extends ReggieMatcher {
  private final MethodHandle factory;
  private final AtomicReference<ReggieMatcher> slot;

  /**
   * @param factory no-arg handle returning a new matcher (e.g. a constructor handle); its first
   *     instance supplies {@link #pattern()} and the group-name map
   */
  public PooledMatcher(MethodHandle factory) {
    this(adapt(factory), create(adapt(factory)));
  }

  private PooledMatcher(MethodHandle factory, ReggieMatcher first) {
    super(first.pattern());
    this.factory = factory;
    this.slot = new AtomicReference<>(first);
    this.nameToIndex = first.nameToIndex;
  }

  private static MethodHandle adapt(MethodHandle factory) {
    return factory.asType(MethodType.methodType(ReggieMatcher.class));
  }

  private static ReggieMatcher create(MethodHandle factory) {
    try {
      return (ReggieMatcher) factory.invokeExact();
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable t) {
      throw new IllegalStateException("Matcher construction failed", t);
    }
  }

  private ReggieMatcher acquire() {
    ReggieMatcher m = slot.getAndSet(null);
    return m != null ? m : create(factory);
  }

  private void release(ReggieMatcher m) {
    slot.setRelease(m);
  }

  @Override
  public boolean isJdkFallback() {
    ReggieMatcher m = acquire();
    boolean r = m.isJdkFallback();
    release(m);
    return r;
  }

  @Override
  public boolean matches(String input) {
    ReggieMatcher m = acquire();
    boolean r = m.matches(input);
    release(m);
    return r;
  }

  @Override
  public boolean find(String input) {
    ReggieMatcher m = acquire();
    boolean r = m.find(input);
    release(m);
    return r;
  }

  @Override
  public int findFrom(String input, int start) {
    ReggieMatcher m = acquire();
    int r = m.findFrom(input, start);
    release(m);
    return r;
  }

  @Override
  public MatchResult match(String input) {
    ReggieMatcher m = acquire();
    MatchResult r = m.match(input);
    release(m);
    return r;
  }

  @Override
  public boolean matchesBounded(CharSequence input, int start, int end) {
    ReggieMatcher m = acquire();
    boolean r = m.matchesBounded(input, start, end);
    release(m);
    return r;
  }

  @Override
  public MatchResult matchBounded(CharSequence input, int start, int end) {
    ReggieMatcher m = acquire();
    MatchResult r = m.matchBounded(input, start, end);
    release(m);
    return r;
  }

  @Override
  public MatchResult findMatch(String input) {
    ReggieMatcher m = acquire();
    MatchResult r = m.findMatch(input);
    release(m);
    return r;
  }

  @Override
  public MatchResult findMatchFrom(String input, int start) {
    ReggieMatcher m = acquire();
    MatchResult r = m.findMatchFrom(input, start);
    release(m);
    return r;
  }

  @Override
  public boolean matchInto(String input, int[] groupStarts, int[] groupEnds) {
    ReggieMatcher m = acquire();
    boolean r = m.matchInto(input, groupStarts, groupEnds);
    release(m);
    return r;
  }

  @Override
  public boolean findMatchInto(String input, int[] groupStarts, int[] groupEnds) {
    ReggieMatcher m = acquire();
    boolean r = m.findMatchInto(input, groupStarts, groupEnds);
    release(m);
    return r;
  }

  @Override
  public boolean findMatchInto(String input, int start, int[] groupStarts, int[] groupEnds) {
    ReggieMatcher m = acquire();
    boolean r = m.findMatchInto(input, start, groupStarts, groupEnds);
    release(m);
    return r;
  }

  @Override
  public boolean findBoundsFrom(String input, int start, int[] bounds) {
    ReggieMatcher m = acquire();
    boolean r = m.findBoundsFrom(input, start, bounds);
    release(m);
    return r;
  }

  @Override
  public String replaceFirst(String input, String replacement) {
    ReggieMatcher m = acquire();
    String r = m.replaceFirst(input, replacement);
    release(m);
    return r;
  }

  @Override
  public String replaceAll(String input, String replacement) {
    ReggieMatcher m = acquire();
    String r = m.replaceAll(input, replacement);
    release(m);
    return r;
  }

  @Override
  public String replaceAll(String input, Function<MatchResult, String> replacer) {
    ReggieMatcher m = acquire();
    String r = m.replaceAll(input, replacer);
    release(m);
    return r;
  }

  @Override
  public String[] split(String input) {
    ReggieMatcher m = acquire();
    String[] r = m.split(input);
    release(m);
    return r;
  }

  @Override
  public String[] split(String input, int limit) {
    ReggieMatcher m = acquire();
    String[] r = m.split(input, limit);
    release(m);
    return r;
  }

  @Override
  public List<MatchResult> findAll(String input) {
    ReggieMatcher m = acquire();
    List<MatchResult> r = m.findAll(input);
    release(m);
    return r;
  }
}
