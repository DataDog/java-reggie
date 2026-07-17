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
 * A character sequence that can cooperatively interrupt a native Reggie match.
 *
 * <p>{@link #checkInterrupted()} is invoked synchronously on the matching caller thread. Matching
 * state remains single-thread-confined; implementations must not concurrently reuse a state.
 */
public interface InterruptibleCharSequence extends CharSequence {
  /**
   * Checks whether the current match should stop.
   *
   * <p>Implementations may throw an unchecked cancellation or deadline exception. A sequence is
   * owned by its caller; a {@link ReggieMatchState} never retains it after {@code matches} returns.
   */
  void checkInterrupted();
}
