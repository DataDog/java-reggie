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
package com.datadoghq.reggie;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class ReggieOptionTest {
  @Test
  void enumHasCaptureAndFallbackFlags() {
    EnumSet<ReggieOption> all = EnumSet.allOf(ReggieOption.class);
    assertEquals(true, all.contains(ReggieOption.CAPTURE_NAMED_ONLY));
    assertEquals(true, all.contains(ReggieOption.ALLOW_JDK_FALLBACK));
  }
}
