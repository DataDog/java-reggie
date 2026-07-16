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

/** Flags that control regex semantics for {@link Reggie#compile(String, int)}. */
public final class ReggieFlags {
  // Keep these separate from java.util.regex.Pattern's values so an accidental JDK flag is never
  // silently interpreted as a different Reggie flag.
  public static final int CASE_INSENSITIVE = 1 << 24;
  public static final int MULTILINE = 1 << 25;
  public static final int DOTALL = 1 << 26;
  public static final int LITERAL = 1 << 27;

  private static final int SUPPORTED = CASE_INSENSITIVE | MULTILINE | DOTALL | LITERAL;

  private ReggieFlags() {}

  /** Returns whether {@code flags} contains only Reggie-defined flag bits. */
  public static boolean areSupported(int flags) {
    return (flags & ~SUPPORTED) == 0;
  }
}
