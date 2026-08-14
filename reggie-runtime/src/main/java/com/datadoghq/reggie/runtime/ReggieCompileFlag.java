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

import com.datadoghq.reggie.ReggieFlags;

/** Flags supported by the native full-capture linear-token-sequence compilation profile. */
public enum ReggieCompileFlag {
  NONE(ReggieFlags.NONE),
  DOTALL(ReggieFlags.DOTALL);

  private final int reggieFlags;

  ReggieCompileFlag(int reggieFlags) {
    this.reggieFlags = reggieFlags;
  }

  int reggieFlags() {
    return reggieFlags;
  }
}
