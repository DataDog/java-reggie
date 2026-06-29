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
package com.datadoghq.reggie.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datadoghq.reggie.integration.fuzz.FuzzRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link AlgorithmicFuzzTest} configuration helpers. */
class AlgorithmicFuzzTestConfigTest {

  @BeforeEach
  void clearProps() {
    System.clearProperty("reggie.fuzz.skip");
  }

  @AfterEach
  void restoreProps() {
    System.clearProperty("reggie.fuzz.skip");
  }

  @Test
  void skipZeroIsAccepted() {
    System.setProperty("reggie.fuzz.skip", "0");
    FuzzRunner.Config cfg = AlgorithmicFuzzTest.largeSweepConfig();
    assertEquals(0, cfg.patternSkip, "-Dreggie.fuzz.skip=0 must set patternSkip to 0");
  }

  @Test
  void skipDefaultWhenAbsent() {
    FuzzRunner.Config cfg = AlgorithmicFuzzTest.largeSweepConfig();
    assertEquals(25_000, cfg.patternSkip, "patternSkip default must be 25_000");
  }

  @Test
  void skipPositiveIsAccepted() {
    System.setProperty("reggie.fuzz.skip", "50000");
    FuzzRunner.Config cfg = AlgorithmicFuzzTest.largeSweepConfig();
    assertEquals(50_000, cfg.patternSkip, "-Dreggie.fuzz.skip=50000 must set patternSkip to 50000");
  }

  @Test
  void skipNegativeFallsBackToDefault() {
    System.setProperty("reggie.fuzz.skip", "-1");
    FuzzRunner.Config cfg = AlgorithmicFuzzTest.largeSweepConfig();
    assertEquals(25_000, cfg.patternSkip, "-Dreggie.fuzz.skip=-1 must fall back to default 25_000");
  }
}
