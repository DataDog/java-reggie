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

import java.util.Objects;

/** Immutable outcome of a native full-capture linear-token-sequence compilation request. */
public final class ReggieCompilationResult {
  private final ReggieCompiledPattern pattern;
  private final ReggieCompilationRejection rejection;

  private ReggieCompilationResult(
      ReggieCompiledPattern pattern, ReggieCompilationRejection rejection) {
    if ((pattern == null) == (rejection == null)) {
      throw new IllegalArgumentException("exactly one of pattern or rejection is required");
    }
    this.pattern = pattern;
    this.rejection = rejection;
  }

  static ReggieCompilationResult admitted(ReggieCompiledPattern pattern) {
    return new ReggieCompilationResult(Objects.requireNonNull(pattern, "pattern"), null);
  }

  static ReggieCompilationResult rejected(ReggieCompilationRejection rejection) {
    return new ReggieCompilationResult(null, Objects.requireNonNull(rejection, "rejection"));
  }

  public boolean isAdmitted() {
    return pattern != null;
  }

  public ReggieCompiledPattern pattern() {
    if (pattern == null) {
      throw new IllegalStateException("compilation was rejected: " + rejection);
    }
    return pattern;
  }

  public ReggieCompilationRejection rejection() {
    if (rejection == null) {
      throw new IllegalStateException("compilation was admitted");
    }
    return rejection;
  }
}
