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
package com.datadoghq.reggie.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an abstract method returning ReggieMatcher to have a specialized matcher generated at
 * compile time. The method MUST be abstract, take no parameters, and return {@code
 * com.datadoghq.reggie.runtime.ReggieMatcher}.
 *
 * <p>The annotation processor will generate: 1. A specialized matcher class (e.g., PhoneMatcher)
 * extending ReggieMatcher 2. An implementation of the containing class (e.g., MyPatterns$Impl) with
 * lazy-initialized, cached matcher instances
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * public abstract class MyPatterns {
 *     @RegexPattern("\\d{3}-\\d{3}-\\d{4}")
 *     public abstract ReggieMatcher phone();
 * }
 *
 * // Usage
 * MyPatterns pts = Reggie.patterns(MyPatterns.class);
 * boolean match = pts.phone().matches("123-456-7890");
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface RegexPattern {
  /** The regular expression pattern. */
  String value();
}
