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
import java.util.Set;

/**
 * Immutable native compiled pattern for the named linear-token-sequence profile.
 *
 * <p>This API never selects another Reggie strategy and never delegates to the JDK.
 */
public final class ReggieCompiledPattern {
  private static final Set<ReggieNativeCapability> CAPABILITIES =
      Set.of(
          ReggieNativeCapability.NATIVE_ONLY,
          ReggieNativeCapability.LINEAR_TIME,
          ReggieNativeCapability.INTERRUPTIBLE_CHAR_SEQUENCE);
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
    RuntimeCompiler.NamedOnlyLtsCompilation compilation =
        RuntimeCompiler.tryCompileNamedOnlyLinearTokenSequence(
            request.source(), request.flag().reggieFlags());
    if (compilation.matcher() != null) {
      return ReggieCompilationResult.admitted(new ReggieCompiledPattern(compilation.matcher()));
    }
    return ReggieCompilationResult.rejected(
        ReggieCompilationRejection.valueOf(compilation.rejection().name()));
  }

  /** Creates a new single-thread-confined state object for matching this immutable pattern. */
  public ReggieMatchState newState() {
    return new ReggieMatchState(matcher);
  }

  /** Returns the immutable capabilities of this native named-LTS compiled pattern. */
  public Set<ReggieNativeCapability> capabilities() {
    return CAPABILITIES;
  }
}
