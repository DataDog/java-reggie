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

import java.util.Arrays;
import java.util.Objects;

/**
 * Single-thread-confined mutable matching state for a {@link ReggieCompiledPattern}.
 *
 * <p>The state retains spans only; it never retains the input sequence.
 */
public final class ReggieMatchState {
  private final LinearTokenSequenceMatcher matcher;
  private final LinearTokenSequenceMatcher.MatchWorkspace workspace;
  private final int[] starts;
  private final int[] ends;
  private boolean matched;
  private boolean usedInterruptibleExecution;

  ReggieMatchState(LinearTokenSequenceMatcher matcher) {
    this.matcher = Objects.requireNonNull(matcher, "matcher");
    this.workspace = matcher.newMatchWorkspace();
    this.starts = new int[matcher.groupCount() + 1];
    this.ends = new int[matcher.groupCount() + 1];
    clear();
  }

  /**
   * Attempts a full match inside {@code [start, end)}.
   *
   * <p>Prior state is cleared before validating the input or attempting the match.
   */
  public boolean matches(CharSequence input, int start, int end) {
    clear();
    if (input instanceof InterruptibleCharSequence interruptible) {
      usedInterruptibleExecution = true;
      return matched =
          matcher.matchIntoBoundedInterruptibly(interruptible, start, end, starts, ends, workspace);
    }
    return matched = matcher.matchIntoBounded(input, start, end, starts, ends, workspace);
  }

  boolean usedInterruptibleExecution() {
    return usedInterruptibleExecution;
  }

  public int start() {
    requireMatched();
    return starts[0];
  }

  public int end() {
    requireMatched();
    return ends[0];
  }

  public int start(String name) {
    requireMatched();
    return starts[matcher.groupIndex(name)];
  }

  public int end(String name) {
    requireMatched();
    return ends[matcher.groupIndex(name)];
  }

  private void clear() {
    Arrays.fill(starts, -1);
    Arrays.fill(ends, -1);
    matched = false;
    usedInterruptibleExecution = false;
  }

  private void requireMatched() {
    if (!matched) {
      throw new IllegalStateException("there is no current match");
    }
  }
}
