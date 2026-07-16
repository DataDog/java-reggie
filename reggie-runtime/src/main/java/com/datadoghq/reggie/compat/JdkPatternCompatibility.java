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
package com.datadoghq.reggie.compat;

import com.datadoghq.reggie.ReggieFlags;

/** Explicit adapter that maps supported JDK flag values onto Reggie flag values. */
public final class JdkPatternCompatibility {
  private JdkPatternCompatibility() {}

  /** Converts supported JDK pattern flags to {@link ReggieFlags}. */
  public static int toReggieFlags(int jdkFlags) {
    int remaining = jdkFlags;
    int result = 0;
    if ((remaining & java.util.regex.Pattern.CASE_INSENSITIVE) != 0) {
      throw new IllegalArgumentException(
          "JDK CASE_INSENSITIVE is not compatible with Reggie's Unicode case folding");
    }
    if ((remaining & java.util.regex.Pattern.MULTILINE) != 0) {
      result |= ReggieFlags.MULTILINE;
      remaining &= ~java.util.regex.Pattern.MULTILINE;
    }
    if ((remaining & java.util.regex.Pattern.DOTALL) != 0) {
      result |= ReggieFlags.DOTALL;
      remaining &= ~java.util.regex.Pattern.DOTALL;
    }
    if ((remaining & java.util.regex.Pattern.LITERAL) != 0) {
      result |= ReggieFlags.LITERAL;
      remaining &= ~java.util.regex.Pattern.LITERAL;
    }
    if (remaining != 0) {
      throw new IllegalArgumentException("Unsupported JDK regex flags: " + remaining);
    }
    return result;
  }
}
