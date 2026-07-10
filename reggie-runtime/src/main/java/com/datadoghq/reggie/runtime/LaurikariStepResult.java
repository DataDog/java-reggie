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
 * Result of one {@link LaurikariNfaStep} application: the next active NFA state subset together
 * with each state's per-candidate register vector (see {@link LaurikariNfaStep} for what the
 * registers hold and, critically, whether {@code states}' array order carries meaning for a given
 * step implementation).
 *
 * <p>{@code states} and {@code regs} are parallel arrays: {@code regs[i]} is the winning register
 * vector for {@code states[i]}, after whatever closure/merge tie-break the producing {@link
 * LaurikariNfaStep} implements. No defensive copy is taken, matching {@link StateSetKey}'s
 * convention of trusting the caller to hand over arrays it will not mutate afterward.
 */
final class LaurikariStepResult {
  final int[] states;
  final int[][] regs; // regs[i] is states[i]'s register vector

  LaurikariStepResult(int[] states, int[][] regs) {
    this.states = states;
    this.regs = regs;
  }
}
