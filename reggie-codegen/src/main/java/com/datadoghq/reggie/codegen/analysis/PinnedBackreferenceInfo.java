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

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import java.util.Objects;

/**
 * Pattern information for PINNED_BACKREFERENCE strategy.
 *
 * <p>Handles patterns where a capturing group's content charset is provably disjoint from whatever
 * immediately follows it (and, if present, from any separator between the group's close and the
 * backreference site). Because the group's closing boundary is therefore unambiguous, matching
 * reduces to a single forward scan to that boundary, an optional separator scan, and one equality
 * check against the backreference site - no retry or backtracking is needed.
 *
 * <p>Examples: - {@code <(\w+)>.*</\1>} - tag body charset disjoint from the {@code </} delimiter -
 * {@code \b(\w+)\s+\1\b} - word charset disjoint from the whitespace separator
 */
public class PinnedBackreferenceInfo implements PatternInfo {

  /** Group number of the backreferenced group (1-based). */
  public final int groupIndex;

  /** The AST node for the capturing group's content. Used by codegen for the forward scan. */
  public final RegexNode groupBody;

  /** Character set the group's content matches. */
  public final CharSet groupCharSet;

  /**
   * Node(s) between the group's close and the backreference site, or null if the backreference
   * directly follows the group.
   */
  public final RegexNode separator;

  /** Character set of the separator, or null if there is no separator. */
  public final CharSet separatorCharSet;

  public PinnedBackreferenceInfo(
      int groupIndex,
      RegexNode groupBody,
      CharSet groupCharSet,
      RegexNode separator,
      CharSet separatorCharSet) {
    this.groupIndex = groupIndex;
    this.groupBody = Objects.requireNonNull(groupBody);
    this.groupCharSet = Objects.requireNonNull(groupCharSet);
    this.separator = separator;
    this.separatorCharSet = separatorCharSet;
  }

  /** Whether a separator exists between the group's close and the backreference site. */
  public boolean hasSeparator() {
    return separator != null;
  }

  @Override
  public int structuralHashCode() {
    int hash = getClass().getName().hashCode();
    hash = 31 * hash + groupIndex;
    hash = 31 * hash + groupBody.getClass().getName().hashCode();
    hash = 31 * hash + groupCharSet.hashCode();
    hash = 31 * hash + (separator != null ? separator.getClass().getName().hashCode() : 0);
    hash = 31 * hash + (separatorCharSet != null ? separatorCharSet.hashCode() : 0);
    return hash;
  }

  @Override
  public String toString() {
    return "PinnedBackreferenceInfo{"
        + "group="
        + groupIndex
        + ", groupCharSet="
        + groupCharSet
        + ", hasSeparator="
        + hasSeparator()
        + '}';
  }
}
