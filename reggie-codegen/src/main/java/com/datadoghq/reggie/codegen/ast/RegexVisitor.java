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

/** Visitor interface for traversing regex AST. */
public interface RegexVisitor<T> {

  T visitLiteral(LiteralNode node);

  T visitCharClass(CharClassNode node);

  T visitConcat(ConcatNode node);

  T visitAlternation(AlternationNode node);

  T visitQuantifier(QuantifierNode node);

  T visitGroup(GroupNode node);

  T visitAnchor(AnchorNode node);

  T visitBackreference(BackreferenceNode node);

  T visitAssertion(AssertionNode node);

  T visitSubroutine(SubroutineNode node);

  T visitConditional(ConditionalNode node);

  T visitBranchReset(BranchResetNode node);
}
