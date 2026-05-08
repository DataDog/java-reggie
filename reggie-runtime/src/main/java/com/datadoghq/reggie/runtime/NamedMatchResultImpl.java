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

import java.util.Map;
import java.util.Objects;

/**
 * Extension of {@link MatchResultImpl} that implements {@link NamedGroupResult}. Only instantiated
 * when the pattern contains named capturing groups.
 */
final class NamedMatchResultImpl extends MatchResultImpl implements NamedGroupResult {

  private final Map<String, Integer> nameIndex;

  NamedMatchResultImpl(
      String input, int[] starts, int[] ends, int groupCount, Map<String, Integer> nameIndex) {
    super(input, starts, ends, groupCount);
    this.nameIndex = Objects.requireNonNull(nameIndex, "nameIndex");
  }

  @Override
  public boolean hasNamedGroups() {
    return !nameIndex.isEmpty();
  }

  @Override
  public String group(String name) {
    Integer idx = nameIndex.get(name);
    if (idx == null) {
      throw new IllegalArgumentException("No group with name <" + name + ">");
    }
    return group(idx);
  }
}
