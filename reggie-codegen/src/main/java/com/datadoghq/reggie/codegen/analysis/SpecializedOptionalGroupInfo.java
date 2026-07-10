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
package com.datadoghq.reggie.codegen.analysis;

/**
 * Pattern information for the {@code SPECIALIZED_OPTIONAL_GROUP} strategy.
 *
 * <p>Handles the narrow shape {@code (anchor-start)? (optional-group) (suffix-literal-char)
 * (anchor-end)?}: exactly one optional ({@code min=0,max=1}) capturing group whose entire content
 * is a single literal char, immediately followed by a single literal char suffix, with an optional
 * {@code ^}/{@code \A} before the group and/or {@code $}/{@code \z} after the suffix.
 *
 * <p>Examples: {@code (a)?b}, {@code ^(a)?b$}, {@code (?<x>a)?b}.
 *
 * <p>Deliberately minimal by design: unlike {@code OptionalGroupBackrefInfo} (which carries {@code
 * prefix}/{@code middle}/backref-entry fields that its generator never fully consumes - see {@code
 * doc/2026-07-09-optional-group-backref-backtracking-bug.md}), this class holds only the fields
 * {@link SpecializedOptionalGroupBytecodeGenerator} actually reads, so there is no unused field for
 * a future change to silently leave stale.
 */
public final class SpecializedOptionalGroupInfo implements PatternInfo {

  /** Capture index of the optional group (1-based). */
  public final int groupNumber;

  /** Literal char the optional group matches when it participates. */
  public final char groupChar;

  /** Literal char required immediately after the optional group's position. */
  public final char suffixChar;

  /** Whether the pattern requires the match to start at position 0 ({@code ^} or {@code \A}). */
  public final boolean hasStartAnchor;

  /**
   * Whether the pattern requires the match to end at the input's length ({@code $} or {@code \z}).
   */
  public final boolean hasEndAnchor;

  public SpecializedOptionalGroupInfo(
      int groupNumber,
      char groupChar,
      char suffixChar,
      boolean hasStartAnchor,
      boolean hasEndAnchor) {
    this.groupNumber = groupNumber;
    this.groupChar = groupChar;
    this.suffixChar = suffixChar;
    this.hasStartAnchor = hasStartAnchor;
    this.hasEndAnchor = hasEndAnchor;
  }

  /**
   * Structural hash deliberately excludes {@link #groupChar}/{@link #suffixChar} (literal
   * *values*), keeping only structural shape ({@link #groupNumber}, {@link #hasStartAnchor}, {@link
   * #hasEndAnchor}) - two patterns like {@code (a)?b} and {@code (x)?y} are structurally equivalent
   * and should generate cache-compatible bytecode shells differing only in which literal chars are
   * baked in. Note this differs from the nearest sibling class, {@code OptionalGroupBackrefInfo},
   * whose {@code structuralHashCode()} does fold in literal char/string values; that is a
   * pre-existing convention on that class, not one this class follows, since including literal
   * values here would defeat the structural-cache's purpose of sharing bytecode across patterns
   * differing only by literal value.
   */
  @Override
  public int structuralHashCode() {
    int hash = getClass().getName().hashCode();
    hash = 31 * hash + groupNumber;
    hash = 31 * hash + (hasStartAnchor ? 1 : 0);
    hash = 31 * hash + (hasEndAnchor ? 1 : 0);
    return hash;
  }

  @Override
  public String toString() {
    return "SpecializedOptionalGroupInfo{"
        + "groupNumber="
        + groupNumber
        + ", groupChar='"
        + groupChar
        + "', suffixChar='"
        + suffixChar
        + "', hasStartAnchor="
        + hasStartAnchor
        + ", hasEndAnchor="
        + hasEndAnchor
        + '}';
  }
}
