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
package com.datadoghq.reggie.codegen.ast;

/**
 * The empty (epsilon) regex node: matches the empty string. Produced by empty concatenations, empty
 * {@code \Q\E} quotes, {@code (?#...)} comments and standalone global modifiers such as {@code
 * (?i)}.
 *
 * <p>Historically the parser represented epsilon as {@code new LiteralNode((char) 0)}, which
 * collided with a genuine NUL literal character: a pattern containing a raw NUL (or a {@code
 * \x00}/{@code \0} escape) parsed to {@code LiteralNode((char) 0)} and was then silently dropped by
 * every {@code ch == 0} epsilon check — e.g. the pattern {@code "\0"} compiled to a matcher that
 * matched everything. {@code EpsilonNode} is a distinct type so epsilon checks can be exact ({@code
 * instanceof EpsilonNode}) and real NUL literals remain plain {@link LiteralNode}s that match the
 * NUL character.
 */
public final class EpsilonNode extends LiteralNode {

  public static final EpsilonNode INSTANCE = new EpsilonNode();

  private EpsilonNode() {
    super((char) 0);
  }

  @Override
  public String toString() {
    return "Epsilon";
  }
}
