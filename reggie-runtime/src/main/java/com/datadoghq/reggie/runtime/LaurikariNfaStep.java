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
 * One tagged-NFA step: given active (state, register-vector) pairs and a character, returns the
 * next active state ids together with each one's winning register vector.
 *
 * <p>Generalized from Phase 0's single-scalar "age" register (design doc §4/§5) to a fixed-size
 * {@code int[]} register file per state, per impl-plan Task 0.5.1: {@code curRegs[i]}/the returned
 * {@code regs[i]} is now a vector, not a bare {@code int}. {@link LaurikariDFACache} itself is
 * agnostic to what the vector holds or how ties are resolved — it only interns whatever {@code
 * (states, regs)} pairs a step implementation returns. Two tie-break disciplines coexist across
 * this codebase's step implementations, and each is free to pick whichever fits its own semantics:
 *
 * <ul>
 *   <li><b>Value-based ("larger wins"):</b> Phase 0's find()-localization driver (see {@code
 *       LaurikariDFACacheTest}/{@code LaurikariPhase0SpikeTest}/{@code LaurikariPhase0Benchmark})
 *       wraps its scalar age in a length-1 vector and keeps comparing values directly — correct
 *       because age is a genuine total order and doesn't need positional information. Callers using
 *       this discipline may freely canonicalize {@code states}/{@code regs} order (e.g. sort
 *       ascending by state id) since order carries no meaning for them.
 *   <li><b>Priority-order ("whole-mapping discard"):</b> Phase 0.5's multi-tag capture checkpoint
 *       (design §4) has no single scalar to compare when two arrivals reach the same target NFA
 *       state, so it instead tracks explicit priority — mirroring {@code PikeVMMatcher.addThread}'s
 *       insertion-order-through-epsilons DFS (first visit wins, later arrivals' entire register
 *       mapping is discarded rather than merged tag-by-tag). For this discipline, {@code
 *       curStates}'/{@code states}' array ORDER *is* the priority (index 0 = highest priority) and
 *       must be preserved verbatim across steps — {@code LaurikariStateSetKey}'s order-sensitive
 *       {@code equals}/{@code hashCode} (plain {@link java.util.Arrays#equals}, no sorting) exists
 *       specifically so two different priority orderings of the same underlying subset intern as
 *       distinct DFA states, matching classical tagged-DFA determinization.
 * </ul>
 *
 * <p>Kept as a separate interface from {@link NfaStep} rather than changing NfaStep's shape,
 * matching how {@link RejectDfaFactory}/{@link PikeVMMatcher} already keep multiple independent
 * NfaStep-shaped lambdas per purpose.
 *
 * <p><b>{@code input}/{@code pos}/{@code regionEnd}:</b> added to support end-anchor ({@code $},
 * {@code \Z}, {@code \z}, {@code (?m)$}) and {@code \b} eligibility — those anchor types need the
 * true input and absolute position to evaluate via {@link PikeVMMatcher#checkAnchor}, which
 * ages-based registers alone don't supply. {@code pos} is the absolute position of the closure
 * being built, i.e. the caller's "characters consumed so far" count <em>after</em> consuming {@code
 * c} (see implementations for the exact convention). Step functions/drivers with no
 * anchor-sensitive states (every Phase 0/0.5 driver today) simply ignore these three parameters.
 */
@FunctionalInterface
interface LaurikariNfaStep {
  /**
   * @param curStates active NFA state ids (subset); order is meaningful or not, per the
   *     implementation's tie-break discipline (see class javadoc)
   * @param curRegs curStates[i]'s register vector
   * @param c the character being consumed
   * @param input the full input being scanned, for end-anchor/{@code \b} evaluation; may be ignored
   *     by implementations with no anchor-sensitive states
   * @param pos absolute position of the closure being built (after consuming {@code c})
   * @param regionEnd the true end of the scan region (anchor-context {@code regionEnd}, never a
   *     scan-bound optimization's narrower limit)
   * @return next active state ids and their per-state winning register vectors, after
   *     epsilon-closure and this step's merge/tie-break rule
   */
  LaurikariStepResult apply(
      int[] curStates, int[][] curRegs, int c, String input, int pos, int regionEnd);
}
