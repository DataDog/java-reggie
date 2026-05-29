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
package com.datadoghq.reggie;

/** Controls which capturing groups Reggie should track and expose. */
public enum CapturePolicy {
  /** Track all capturing groups, matching java.util.regex group numbering semantics. */
  ALL,

  /**
   * Track named groups and groups required by regex semantics (for example backreference targets).
   * Unnamed groups that are only used for precedence are compiled as non-capturing groups.
   */
  NAMED_ONLY
}
