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

/** Capabilities explicitly guaranteed by a native full-capture linear-token-sequence pattern. */
public enum ReggieNativeCapability {
  /**
   * Compiles and matches only through Reggie's native full-capture LTS profile, never a JDK
   * fallback.
   */
  NATIVE_ONLY,

  /**
   * Uses a linear-time native LTS algorithm for the admitted pattern, excluding work or latency
   * imposed by a caller-provided {@link CharSequence} and cooperative checkpoints.
   */
  LINEAR_TIME,

  /**
   * Supports caller-thread cooperative interruption through {@link InterruptibleCharSequence} in
   * {@link ReggieMatchState#matches(CharSequence, int, int)}: before validation and about every 256
   * native character reads. It is not preemptive thread interruption.
   */
  INTERRUPTIBLE_CHAR_SEQUENCE
}
