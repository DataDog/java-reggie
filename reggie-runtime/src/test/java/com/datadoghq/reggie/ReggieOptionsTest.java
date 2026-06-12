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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReggieOptionsTest {
  @Test
  void defaultHasNoFlags() {
    assertFalse(ReggieOptions.DEFAULT.has(ReggieOption.CAPTURE_NAMED_ONLY));
    assertFalse(ReggieOptions.DEFAULT.has(ReggieOption.ALLOW_JDK_FALLBACK));
  }

  @Test
  void enableSetsFlag() {
    ReggieOptions o = ReggieOptions.builder().enable(ReggieOption.ALLOW_JDK_FALLBACK).build();
    assertTrue(o.has(ReggieOption.ALLOW_JDK_FALLBACK));
    assertFalse(o.has(ReggieOption.CAPTURE_NAMED_ONLY));
  }

  @Test
  void shortcutsCompose() {
    ReggieOptions o = ReggieOptions.builder().namedOnly().allowJdkFallback().build();
    assertTrue(o.has(ReggieOption.CAPTURE_NAMED_ONLY));
    assertTrue(o.has(ReggieOption.ALLOW_JDK_FALLBACK));
  }
}
