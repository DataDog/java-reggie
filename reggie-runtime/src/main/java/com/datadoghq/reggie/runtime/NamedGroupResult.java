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

/**
 * Extension of {@link MatchResult} for results that support named capture groups.
 *
 * <p>Use {@link MatchResult#hasNamedGroups()} to check at runtime whether a result supports this
 * interface before casting.
 */
public interface NamedGroupResult extends MatchResult {
  /**
   * Returns the captured group by name.
   *
   * @param name the group name
   * @return the captured string, or null if the group didn't participate in the match
   * @throws IllegalArgumentException if no group with the given name exists
   */
  String group(String name);

  @Override
  default boolean hasNamedGroups() {
    return true;
  }
}
