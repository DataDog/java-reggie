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

/**
 * Immutable native compiled pattern for the named linear-token-sequence profile.
 *
 * <p>This API never selects another Reggie strategy and never delegates to the JDK.
 */
public final class ReggieCompiledPattern {
  private final LinearTokenSequenceMatcher matcher;

  private ReggieCompiledPattern(LinearTokenSequenceMatcher matcher) {
    this.matcher = Objects.requireNonNull(matcher, "matcher");
  }

  /**
   * Attempts native compilation without consulting the general compiler or either compiler cache.
   */
  public static ReggieCompilationResult tryCompile(ReggieCompileRequest request) {
    Objects.requireNonNull(request, "request");
    return tryCompileNative(request);
  }

  static ReggieCompilationResult tryCompileNative(ReggieCompileRequest request) {
    Objects.requireNonNull(request, "request");
    RuntimeCompiler.NamedOnlyLtsCompilation compilation =
        RuntimeCompiler.tryCompileNamedOnlyLinearTokenSequence(
            request.source(), request.flag().reggieFlags());
    if (compilation.matcher() != null) {
      return ReggieCompilationResult.admitted(new ReggieCompiledPattern(compilation.matcher()));
    }
    return ReggieCompilationResult.rejected(mapRejection(compilation.rejection()));
  }

  /** Creates a new single-thread-confined state object for matching this immutable pattern. */
  public ReggieMatchState newState() {
    return new ReggieMatchState(matcher);
  }

  private static ReggieCompilationRejection mapRejection(
      RuntimeCompiler.NamedOnlyLtsRejection rejection) {
    return switch (rejection) {
      case UNSUPPORTED_FLAGS -> ReggieCompilationRejection.UNSUPPORTED_FLAGS;
      case SOURCE_INLINE_MODIFIER -> ReggieCompilationRejection.SOURCE_INLINE_MODIFIER;
      case PARSE_FAILURE -> ReggieCompilationRejection.PARSE_FAILURE;
      case PLAN_UNAVAILABLE -> ReggieCompilationRejection.PLAN_UNAVAILABLE;
      case MISSING_NAMED_CAPTURE -> ReggieCompilationRejection.MISSING_NAMED_CAPTURE;
      case PROFILE_INELIGIBLE -> ReggieCompilationRejection.PROFILE_INELIGIBLE;
    };
  }
}
