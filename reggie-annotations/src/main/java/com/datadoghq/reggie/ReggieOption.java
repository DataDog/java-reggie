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

/**
 * Extensible set of boolean compilation toggles for {@link ReggieOptions}. Add future on/off
 * behaviors by appending a constant here — no new types or builder plumbing required. Multi-valued
 * or parametric settings (3+ states, numeric thresholds) belong on the {@link
 * ReggieOptions.Builder} as typed fields, not here.
 */
public enum ReggieOption {
  /**
   * Track only named and semantically-required capturing groups (e.g. backreference targets).
   * Absent: track all capturing groups, matching {@code java.util.regex} numbering.
   */
  CAPTURE_NAMED_ONLY,

  /**
   * Permit {@code java.util.regex} fallback for patterns Reggie cannot compile natively. Absent:
   * {@link Reggie#compile(String, ReggieOptions)} throws {@link UnsupportedPatternException} for
   * such patterns instead of returning a JDK-backed matcher.
   */
  ALLOW_JDK_FALLBACK
}
