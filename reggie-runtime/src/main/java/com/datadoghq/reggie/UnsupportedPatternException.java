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

/**
 * Thrown when a pattern is syntactically valid regular-expression input but uses a construct that
 * Reggie does not support.
 *
 * <p>This public exception type lets callers implement precise fallback logic without catching all
 * exceptions from {@link Reggie#compile(String)}.
 */
public class UnsupportedPatternException extends RuntimeException {
  public UnsupportedPatternException(String message) {
    super(message);
  }

  public UnsupportedPatternException(String message, Throwable cause) {
    super(message, cause);
  }
}
