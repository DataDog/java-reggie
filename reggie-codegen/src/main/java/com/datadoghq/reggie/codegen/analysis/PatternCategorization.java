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
package com.datadoghq.reggie.codegen.analysis;

import java.util.List;

/** Result produced by {@link PatternCategorizer}. */
public record PatternCategorization(
    Category category, List<PatternAtom> atoms, List<String> notes) {

  public enum Category {
    /** A deterministic sequence of reusable delimited/log-template atoms. */
    LINEAR_TEMPLATE,

    /** A pure literal sequence. */
    LITERAL_SEQUENCE,

    /** The pattern is valid but not yet represented by a reusable category. */
    GENERAL_REGEX
  }

  public boolean isLinearTemplate() {
    return category == Category.LINEAR_TEMPLATE || category == Category.LITERAL_SEQUENCE;
  }
}
