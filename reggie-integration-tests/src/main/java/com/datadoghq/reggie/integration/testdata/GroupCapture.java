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
package com.datadoghq.reggie.integration.testdata;

/**
 * Represents expected capture for a capturing group. Used to validate that regex groups capture the
 * correct substrings.
 */
public record GroupCapture(
    int groupNumber,
    String expectedValue,
    int startPos, // -1 if position not specified
    int endPos // -1 if position not specified
    ) {
  public GroupCapture(int groupNumber, String expectedValue) {
    this(groupNumber, expectedValue, -1, -1);
  }

  public GroupCapture(int groupNumber, int startPos, int endPos) {
    this(groupNumber, null, startPos, endPos);
  }

  public boolean hasPosition() {
    return startPos >= 0 && endPos >= 0;
  }

  public boolean hasValue() {
    return expectedValue != null;
  }
}
