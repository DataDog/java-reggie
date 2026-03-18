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

import java.util.List;
import java.util.Set;

/** Represents a test case with expected capturing group values. */
public record CaptureGroupTest(
    String pattern,
    String input,
    boolean shouldMatch,
    List<GroupCapture> expectedCaptures,
    String source,
    Set<String> features) {
  public boolean isSupported() {
    // Backreferences ARE supported (via NFA)
    return !features.contains("conditional")
        && !features.contains("atomic_group")
        && !features.contains("possessive_quantifier")
        && !features.contains("unicode_property")
        && !features.contains("inline_flags")
        && !features.contains("named_groups")
        && // Named groups not yet supported
        !features.contains("pcre_verb"); // PCRE verbs (*ACCEPT), (*COMMIT), etc.
  }

  public int getGroupCount() {
    return expectedCaptures.size();
  }
}
