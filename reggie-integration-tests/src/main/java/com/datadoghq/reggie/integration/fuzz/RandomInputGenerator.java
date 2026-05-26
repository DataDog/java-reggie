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
package com.datadoghq.reggie.integration.fuzz;

import java.util.Random;

/**
 * Generates input strings to feed to a pattern. Uses the same small alphabet as {@link
 * RandomRegexGenerator} so generated patterns and inputs have a chance of interacting.
 */
public final class RandomInputGenerator {

  private static final char[] ALPHABET = {'a', 'b', 'c', '0', '1', '-', '_', '\n'};

  private final Random rnd;
  private final int maxLength;

  /**
   * @param rnd random source.
   * @param maxLength inclusive maximum input length. Strings up to this length are sampled; the
   *     empty string is included.
   */
  public RandomInputGenerator(Random rnd, int maxLength) {
    this.rnd = rnd;
    this.maxLength = maxLength;
  }

  public String generate() {
    int len = rnd.nextInt(maxLength + 1);
    StringBuilder sb = new StringBuilder(len);
    for (int i = 0; i < len; i++) {
      sb.append(ALPHABET[rnd.nextInt(ALPHABET.length)]);
    }
    return sb.toString();
  }
}
