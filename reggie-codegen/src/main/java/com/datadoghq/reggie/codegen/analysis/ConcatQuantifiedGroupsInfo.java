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

import com.datadoghq.reggie.codegen.automaton.CharSet;
import java.util.List;

/**
 * Pattern information for concatenated quantified capturing groups.
 *
 * <p>Handles patterns like: (a+)+(b+)+ Each element is a quantified capturing group with explicit
 * last-iteration tracking.
 */
public class ConcatQuantifiedGroupsInfo implements PatternInfo {
  /** Info for each quantified group in the concatenation. */
  public static class GroupInfo {
    public final int groupNumber; // Capturing group number (1, 2, ...)
    public final CharSet charSet; // Characters this group can match
    public final int outerMin; // Outer quantifier min (iterations)
    public final int outerMax; // Outer quantifier max (iterations)
    public final int innerMin; // Inner quantifier min (chars per iteration)
    public final int innerMax; // Inner quantifier max (chars per iteration)

    public GroupInfo(
        int groupNumber, CharSet charSet, int outerMin, int outerMax, int innerMin, int innerMax) {
      this.groupNumber = groupNumber;
      this.charSet = charSet;
      this.outerMin = outerMin;
      this.outerMax = outerMax;
      this.innerMin = innerMin;
      this.innerMax = innerMax;
    }

    public boolean isOuterUnbounded() {
      return outerMax == Integer.MAX_VALUE;
    }

    public boolean isInnerUnbounded() {
      return innerMax == Integer.MAX_VALUE;
    }
  }

  public final List<GroupInfo> groups;

  public ConcatQuantifiedGroupsInfo(List<GroupInfo> groups) {
    this.groups = groups;
  }

  @Override
  public int structuralHashCode() {
    int hash = getClass().getName().hashCode();
    for (GroupInfo g : groups) {
      hash = 31 * hash + g.groupNumber;
      hash = 31 * hash + g.outerMin;
      hash = 31 * hash + (g.isOuterUnbounded() ? 1 : 0);
      hash = 31 * hash + g.innerMin;
      hash = 31 * hash + (g.isInnerUnbounded() ? 2 : 0);
    }
    return hash;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("ConcatQuantifiedGroupsInfo[");
    for (int i = 0; i < groups.size(); i++) {
      if (i > 0) sb.append(", ");
      GroupInfo g = groups.get(i);
      sb.append("group")
          .append(g.groupNumber)
          .append(":{")
          .append(g.innerMin)
          .append(",")
          .append(g.innerMax)
          .append("}")
          .append("{")
          .append(g.outerMin)
          .append(",")
          .append(g.outerMax)
          .append("}");
    }
    sb.append("]");
    return sb.toString();
  }
}
