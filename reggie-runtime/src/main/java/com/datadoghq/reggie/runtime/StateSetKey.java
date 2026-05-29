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

import java.util.Arrays;

final class StateSetKey {
  private final int[] states;
  private final int hash;

  StateSetKey(int[] sortedStates) {
    this.states = sortedStates;
    this.hash = Arrays.hashCode(sortedStates);
  }

  int[] getStates() {
    return states;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof StateSetKey)) return false;
    return Arrays.equals(states, ((StateSetKey) o).states);
  }

  @Override
  public int hashCode() {
    return hash;
  }
}
