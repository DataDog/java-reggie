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

import java.util.Map;

/**
 * Wraps a ReggieMatcher to enrich MatchResult returns with named group support. Used by
 * RuntimeCompiler for non-hybrid matchers when the pattern contains named capture groups.
 */
final class NameEnrichingMatcher extends ReggieMatcher {

  private final ReggieMatcher delegate;

  NameEnrichingMatcher(ReggieMatcher delegate) {
    super(delegate.pattern());
    this.delegate = delegate;
    this.nameToIndex = delegate.nameToIndex;
  }

  @Override
  public boolean matches(String input) {
    return delegate.matches(input);
  }

  @Override
  public boolean find(String input) {
    return delegate.find(input);
  }

  @Override
  public int findFrom(String input, int start) {
    return delegate.findFrom(input, start);
  }

  @Override
  public MatchResult match(String input) {
    return enrich(delegate.match(input));
  }

  @Override
  public boolean matchesBounded(CharSequence input, int start, int end) {
    return delegate.matchesBounded(input, start, end);
  }

  @Override
  public MatchResult matchBounded(CharSequence input, int start, int end) {
    return enrich(delegate.matchBounded(input, start, end));
  }

  @Override
  public MatchResult findMatch(String input) {
    return enrich(delegate.findMatch(input));
  }

  @Override
  public MatchResult findMatchFrom(String input, int start) {
    return enrich(delegate.findMatchFrom(input, start));
  }

  @Override
  public boolean findBoundsFrom(String input, int start, int[] bounds) {
    return delegate.findBoundsFrom(input, start, bounds);
  }

  private MatchResult enrich(MatchResult r) {
    if (r == null) {
      return null;
    }
    if (r instanceof MatchResultImpl) {
      return ((MatchResultImpl) r).withNames(nameToIndex);
    }
    return new NamedDelegate(r, nameToIndex);
  }

  private static final class NamedDelegate implements MatchResult {
    private final MatchResult base;
    private final Map<String, Integer> nameToIndex;

    NamedDelegate(MatchResult base, Map<String, Integer> nameToIndex) {
      this.base = base;
      this.nameToIndex = nameToIndex;
    }

    @Override
    public int start() {
      return base.start();
    }

    @Override
    public int end() {
      return base.end();
    }

    @Override
    public String group() {
      return base.group();
    }

    @Override
    public int groupCount() {
      return base.groupCount();
    }

    @Override
    public String group(int group) {
      return base.group(group);
    }

    @Override
    public int start(int group) {
      return base.start(group);
    }

    @Override
    public int end(int group) {
      return base.end(group);
    }

    @Override
    public String group(String name) {
      if (name == null) throw new IllegalArgumentException("group name must not be null");
      Integer idx = nameToIndex.get(name);
      if (idx == null) throw new IllegalArgumentException("No group with name: " + name);
      return base.group(idx);
    }

    @Override
    public int start(String name) {
      if (name == null) throw new IllegalArgumentException("group name must not be null");
      Integer idx = nameToIndex.get(name);
      if (idx == null) throw new IllegalArgumentException("No group with name: " + name);
      return base.start(idx);
    }

    @Override
    public int end(String name) {
      if (name == null) throw new IllegalArgumentException("group name must not be null");
      Integer idx = nameToIndex.get(name);
      if (idx == null) throw new IllegalArgumentException("No group with name: " + name);
      return base.end(idx);
    }
  }
}
