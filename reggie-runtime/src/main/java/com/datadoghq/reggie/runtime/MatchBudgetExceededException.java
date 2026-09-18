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

/**
 * Thrown when a bounded-match engine (currently the counted-loop backtracking matcher) exhausts its
 * per-call step budget. The alternative engines for these patterns (java.util.regex backtracking)
 * can burn unbounded time on the same inputs — this budget converts that into a fast, bounded
 * failure so no match call can hang or be used for denial of service.
 *
 * <p>Callers should treat this like a transient, input-dependent error: the pattern is compiled and
 * valid, but this particular input is too ambiguous for the bounded exploration. Retry with a
 * higher budget ({@code -Dreggie.countedloop.maxSteps}) only if the input is trusted.
 */
public class MatchBudgetExceededException extends RuntimeException {
  public MatchBudgetExceededException(String message) {
    super(message);
  }
}
