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
 * Marker interface for Reggie pattern provider classes. Classes annotated with
 * {@code @RegexPattern} should extend an abstract class that serves as the pattern provider, and
 * the generated implementation will implement this interface.
 *
 * <p>Use {@link Reggie#patterns(Class)} to obtain instances of pattern providers.
 *
 * <p>Example:
 *
 * <pre>
 * public abstract class MyPatterns implements ReggiePatterns {
 *     {@literal @}RegexPattern("\\d+")
 *     public abstract ReggieMatcher digits();
 * }
 *
 * // Usage:
 * MyPatterns patterns = Reggie.patterns(MyPatterns.class);
 * boolean matches = patterns.digits().matches("123");
 * </pre>
 */
public interface ReggiePatterns {}
