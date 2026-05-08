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

/**
 * Marker interface for {@link MatchResult} instances returned by {@link MatchCursor} when the
 * pattern contains named capturing groups.
 *
 * <p>All {@code MatchResult} instances support {@link MatchResult#group(String)}, {@link
 * MatchResult#start(String)}, and {@link MatchResult#end(String)}, but the default implementations
 * throw {@link IllegalArgumentException} when the pattern has no named groups. Instances of this
 * type are guaranteed to carry a populated name-to-index mapping so those calls succeed.
 */
public interface NamedGroupResult extends MatchResult {}
