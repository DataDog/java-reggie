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

/**
 * Interning key for a Laurikari DFA state: an active NFA state subset together with each state's
 * per-candidate register vector (see {@link LaurikariNfaStep} for what the registers hold).
 *
 * <p>Unlike {@link StateSetKey}, which hashes and compares only the state-id array, this key also
 * hashes and compares the parallel register array: two Laurikari DFA states are the same only if
 * both the subset and every state's register vector match, since a given subset with different
 * registers represents different match-priority candidates.
 *
 * <p><b>Deliberately order-sensitive.</b> {@code states}/{@code regs} are compared with plain
 * {@link Arrays#equals}/{@link Arrays#deepEquals} — no sorting, here or in the constructor. This
 * matters for {@link LaurikariNfaStep}'s priority-order tie-break discipline (see its class
 * javadoc): two different priority orderings of the same underlying subset are genuinely different
 * DFA states there, since order-of-arrival is exactly what tag-vector comparison alone cannot
 * recover. Phase 0's value-based ("larger age wins") driver instead canonicalizes to ascending
 * state-id order before construction, since order carries no meaning for it — either convention
 * works with this key as long as the caller who feeds it is consistent with itself.
 */
final class LaurikariStateSetKey {
  private final int[] states;
  private final int[][] regs;
  private final int hash;

  LaurikariStateSetKey(int[] states, int[][] regs) {
    this.states = states;
    this.regs = regs;
    this.hash = 31 * Arrays.hashCode(states) + Arrays.deepHashCode(regs);
  }

  int[] getStates() {
    return states;
  }

  int[][] getRegs() {
    return regs;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof LaurikariStateSetKey)) return false;
    LaurikariStateSetKey other = (LaurikariStateSetKey) o;
    return Arrays.equals(states, other.states) && Arrays.deepEquals(regs, other.regs);
  }

  @Override
  public int hashCode() {
    return hash;
  }
}
