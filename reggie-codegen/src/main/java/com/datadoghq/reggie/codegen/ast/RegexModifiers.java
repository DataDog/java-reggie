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
package com.datadoghq.reggie.codegen.ast;

import java.util.EnumSet;
import java.util.Set;

/**
 * Represents regex modifiers that can be set inline in patterns. Examples: (?i), (?m), (?s), (?x),
 * (?-i), (?i:...)
 */
public class RegexModifiers {

  /** Available modifier flags */
  public enum Flag {
    CASE_INSENSITIVE('i'), // (?i) - case insensitive matching
    MULTILINE('m'), // (?m) - ^ and $ match line boundaries, not just string boundaries
    DOTALL('s'), // (?s) - . matches newline characters
    EXTENDED('x'); // (?x) - ignore whitespace and allow comments

    public final char symbol;

    Flag(char symbol) {
      this.symbol = symbol;
    }

    public static Flag fromChar(char c) {
      for (Flag flag : values()) {
        if (flag.symbol == c) {
          return flag;
        }
      }
      return null;
    }
  }

  private final Set<Flag> flags;

  /** Creates an empty modifier set */
  public RegexModifiers() {
    this.flags = EnumSet.noneOf(Flag.class);
  }

  /** Creates a modifier set with the given flags */
  public RegexModifiers(Set<Flag> flags) {
    this.flags = EnumSet.copyOf(flags);
  }

  /** Creates a copy of the given modifiers */
  public RegexModifiers(RegexModifiers other) {
    this.flags = EnumSet.copyOf(other.flags);
  }

  /** Adds a flag to this modifier set */
  public void add(Flag flag) {
    flags.add(flag);
  }

  /** Removes a flag from this modifier set */
  public void remove(Flag flag) {
    flags.remove(flag);
  }

  /** Checks if a flag is set */
  public boolean has(Flag flag) {
    return flags.contains(flag);
  }

  /** Checks if case-insensitive matching is enabled */
  public boolean isCaseInsensitive() {
    return flags.contains(Flag.CASE_INSENSITIVE);
  }

  /** Checks if multiline mode is enabled (^ and $ match line boundaries) */
  public boolean isMultiline() {
    return flags.contains(Flag.MULTILINE);
  }

  /** Checks if dotall mode is enabled (. matches newline) */
  public boolean isDotall() {
    return flags.contains(Flag.DOTALL);
  }

  /** Checks if extended mode is enabled (ignore whitespace) */
  public boolean isExtended() {
    return flags.contains(Flag.EXTENDED);
  }

  /** Returns true if no modifiers are set */
  public boolean isEmpty() {
    return flags.isEmpty();
  }

  /** Creates a new modifier set with the given flag added */
  public RegexModifiers with(Flag flag) {
    RegexModifiers result = new RegexModifiers(this);
    result.add(flag);
    return result;
  }

  /** Creates a new modifier set with the given flag removed */
  public RegexModifiers without(Flag flag) {
    RegexModifiers result = new RegexModifiers(this);
    result.remove(flag);
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (!(obj instanceof RegexModifiers)) return false;
    RegexModifiers other = (RegexModifiers) obj;
    return flags.equals(other.flags);
  }

  @Override
  public int hashCode() {
    return flags.hashCode();
  }

  @Override
  public String toString() {
    if (flags.isEmpty()) {
      return "[]";
    }
    StringBuilder sb = new StringBuilder("[");
    for (Flag flag : flags) {
      sb.append(flag.symbol);
    }
    sb.append("]");
    return sb.toString();
  }
}
