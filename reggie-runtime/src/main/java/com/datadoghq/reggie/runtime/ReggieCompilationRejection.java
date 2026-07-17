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

/** Reason the native full-capture linear-token-sequence profile did not admit a request. */
public enum ReggieCompilationRejection {
  UNSUPPORTED_FLAGS,
  SOURCE_TOO_LONG,
  SOURCE_INLINE_MODIFIER,
  PARSE_FAILURE,
  PLAN_UNAVAILABLE,
  MISSING_NAMED_CAPTURE,
  MISSING_CAPTURE,
  PROFILE_INELIGIBLE
}
