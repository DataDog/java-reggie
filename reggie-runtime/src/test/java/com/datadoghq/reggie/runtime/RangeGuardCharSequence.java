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

/** CharSequence test double that fails any charAt outside an allowed region. */
final class RangeGuardCharSequence implements CharSequence {
  private final String value;
  private final int allowedStart;
  private final int allowedEnd;

  RangeGuardCharSequence(String value, int allowedStart, int allowedEnd) {
    this.value = value;
    this.allowedStart = allowedStart;
    this.allowedEnd = allowedEnd;
  }

  @Override
  public int length() {
    return value.length();
  }

  @Override
  public char charAt(int index) {
    if (index < allowedStart || index >= allowedEnd) {
      throw new AssertionError("read outside bounded region: " + index);
    }
    return value.charAt(index);
  }

  @Override
  public CharSequence subSequence(int start, int end) {
    throw new AssertionError("subSequence must not be called while matching");
  }

  @Override
  public String toString() {
    throw new AssertionError("toString must not be called while matching");
  }
}
