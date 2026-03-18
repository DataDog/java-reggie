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
package com.datadoghq.reggie.debug;

import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import com.datadoghq.reggie.runtime.ReggieMatcher;
import com.datadoghq.reggie.runtime.RuntimeCompiler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Debug utility for inspecting bytecode generation for regex patterns.
 *
 * <p>Usage: java com.datadoghq.reggie.debug.BytecodeDebugger "pattern" [output-dir]
 *
 * <p>Examples: java com.datadoghq.reggie.debug.BytecodeDebugger "a{30,}" java
 * com.datadoghq.reggie.debug.BytecodeDebugger "\d+" /tmp/debug java
 * com.datadoghq.reggie.debug.BytecodeDebugger "(\d{3})-(\d{3})-(\d{4})"
 */
public class BytecodeDebugger {

  public static void main(String[] args) {
    if (args.length < 1) {
      System.err.println("Usage: BytecodeDebugger <pattern> [output-dir]");
      System.err.println();
      System.err.println("Examples:");
      System.err.println("  BytecodeDebugger \"a{30,}\"");
      System.err.println("  BytecodeDebugger \"\\d+\" /tmp/debug");
      System.err.println("  BytecodeDebugger \"(\\d{3})-(\\d{3})-(\\d{4})\"");
      System.exit(1);
    }

    String pattern = args[0];
    String outputDir = args.length > 1 ? args[1] : null;

    System.out.println("=".repeat(80));
    System.out.println("Bytecode Debug Report for Pattern: " + pattern);
    System.out.println("=".repeat(80));
    System.out.println();

    try {
      // Parse pattern
      System.out.println("1. PARSING PATTERN");
      System.out.println("-".repeat(40));
      RegexParser parser = new RegexParser();
      RegexNode ast = parser.parse(pattern);
      System.out.println("   AST: " + ast.getClass().getSimpleName());
      System.out.println("   Structure: " + describeAST(ast));
      System.out.println();

      // Count groups
      int groupCount = countGroups(ast);

      // Check if pattern requires recursive descent (context-free features)
      // Do this early to avoid unnecessary NFA building
      System.out.println("2. CHECKING PATTERN TYPE");
      System.out.println("-".repeat(40));
      boolean requiresRecursiveDescent = PatternAnalyzer.requiresRecursiveDescent(ast);
      System.out.println("   Requires Recursive Descent: " + requiresRecursiveDescent);
      System.out.println("   Group Count: " + groupCount);
      System.out.println();

      // Build NFA only if not using recursive descent
      NFA nfa = null;
      if (!requiresRecursiveDescent) {
        System.out.println("3. BUILDING NFA");
        System.out.println("-".repeat(40));
        ThompsonBuilder builder = new ThompsonBuilder();
        nfa = builder.build(ast, groupCount);
        System.out.println("   States: " + nfa.getStates().size());
        System.out.println("   Groups: " + nfa.getGroupCount());
        System.out.println();
      } else {
        System.out.println("3. BUILDING NFA");
        System.out.println("-".repeat(40));
        System.out.println("   Skipped (recursive descent pattern)");
        System.out.println();
      }

      // Analyze pattern and select strategy
      System.out.println("4. STRATEGY SELECTION");
      System.out.println("-".repeat(40));
      PatternAnalyzer analyzer = new PatternAnalyzer(ast, nfa);
      PatternAnalyzer.MatchingStrategyResult result = analyzer.analyzeAndRecommend();
      System.out.println("   Strategy: " + result.strategy);
      if (result.dfa != null) {
        System.out.println("   DFA States: " + result.dfa.getStateCount());
      }
      if (result.patternInfo != null) {
        System.out.println("   Pattern Info: " + result.patternInfo.getClass().getSimpleName());
      }
      System.out.println();

      // Compile pattern
      System.out.println("5. COMPILING PATTERN");
      System.out.println("-".repeat(40));
      ReggieMatcher matcher = RuntimeCompiler.compile(pattern);
      String className = matcher.getClass().getName();
      System.out.println("   Generated Class: " + className);
      System.out.println();

      // List generated methods
      System.out.println("6. GENERATED METHODS");
      System.out.println("-".repeat(40));
      java.lang.reflect.Method[] methods = matcher.getClass().getDeclaredMethods();
      for (java.lang.reflect.Method method : methods) {
        if (method.getDeclaringClass() == matcher.getClass()) {
          System.out.printf(
              "   %s(%s)%n", method.getName(), formatParameters(method.getParameterTypes()));
        }
      }
      System.out.println();

      // Test basic functionality
      System.out.println("7. FUNCTIONALITY TEST");
      System.out.println("-".repeat(40));
      testMatcher(matcher, pattern);
      System.out.println();

      // Save class file if output directory specified
      if (outputDir != null) {
        System.out.println("8. SAVING CLASS FILE");
        System.out.println("-".repeat(40));
        saveClassFile(matcher.getClass(), outputDir);
        System.out.println();
      }

      System.out.println("=".repeat(80));
      System.out.println("Debug report completed successfully");
      System.out.println("=".repeat(80));

    } catch (Exception e) {
      System.err.println("ERROR: " + e.getMessage());
      e.printStackTrace();
      System.exit(1);
    }
  }

  private static String describeAST(RegexNode node) {
    // Return simple type name - detailed inspection would require public fields
    return node.getClass().getSimpleName();
  }

  private static int countGroups(RegexNode node) {
    // Recursively count capturing groups in the AST
    if (node instanceof com.datadoghq.reggie.codegen.ast.GroupNode) {
      com.datadoghq.reggie.codegen.ast.GroupNode group =
          (com.datadoghq.reggie.codegen.ast.GroupNode) node;
      int count = group.capturing ? 1 : 0;
      return count + countGroups(group.child);
    } else if (node instanceof com.datadoghq.reggie.codegen.ast.ConcatNode) {
      com.datadoghq.reggie.codegen.ast.ConcatNode concat =
          (com.datadoghq.reggie.codegen.ast.ConcatNode) node;
      int count = 0;
      for (com.datadoghq.reggie.codegen.ast.RegexNode child : concat.children) {
        count += countGroups(child);
      }
      return count;
    } else if (node instanceof com.datadoghq.reggie.codegen.ast.AlternationNode) {
      com.datadoghq.reggie.codegen.ast.AlternationNode alt =
          (com.datadoghq.reggie.codegen.ast.AlternationNode) node;
      int count = 0;
      for (com.datadoghq.reggie.codegen.ast.RegexNode alternative : alt.alternatives) {
        count += countGroups(alternative);
      }
      return count;
    } else if (node instanceof com.datadoghq.reggie.codegen.ast.QuantifierNode) {
      com.datadoghq.reggie.codegen.ast.QuantifierNode quant =
          (com.datadoghq.reggie.codegen.ast.QuantifierNode) node;
      return countGroups(quant.child);
    } else if (node instanceof com.datadoghq.reggie.codegen.ast.AssertionNode) {
      com.datadoghq.reggie.codegen.ast.AssertionNode assertion =
          (com.datadoghq.reggie.codegen.ast.AssertionNode) node;
      return countGroups(assertion.subPattern);
    }
    // Leaf nodes (LiteralNode, CharClassNode, AnchorNode, BackreferenceNode) have no groups
    return 0;
  }

  private static String formatParameters(Class<?>[] params) {
    if (params.length == 0) return "";
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < params.length; i++) {
      if (i > 0) sb.append(", ");
      sb.append(params[i].getSimpleName());
    }
    return sb.toString();
  }

  private static void testMatcher(ReggieMatcher matcher, String pattern) {
    // Test with simple inputs
    String[] testInputs = {
      "test123test",
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", // 30 a's
      "no match here",
      "123-456-7890",
      "aa", // Simple test for subroutines
      "aaaa", // Another subroutine test
      "ab", // Recursive pattern test
      "aabb" // Recursive pattern test
    };

    for (String input : testInputs) {
      try {
        boolean matches = matcher.matches(input);
        System.out.printf("   Input: %-35s matches=%b%n", truncate(input, 30), matches);
      } catch (UnsupportedOperationException e) {
        System.out.printf("   Input: %-35s [method not yet implemented]%n", truncate(input, 30));
      }
    }
  }

  private static String truncate(String s, int maxLen) {
    if (s.length() <= maxLen) return "\"" + s + "\"";
    return "\"" + s.substring(0, maxLen - 3) + "...\"";
  }

  private static void saveClassFile(Class<?> clazz, String outputDir) throws IOException {
    // Get the class bytes using reflection (hidden class)
    String className = clazz.getName();
    String simpleClassName = className.substring(className.lastIndexOf('$') + 1);

    System.out.println("   Class: " + className);
    System.out.println("   Output Directory: " + outputDir);

    // Create output directory
    Path dir = Paths.get(outputDir);
    Files.createDirectories(dir);

    // Try to get bytecode from hidden class
    // Note: Hidden classes (created by MethodHandles.Lookup.defineHiddenClass)
    // don't expose their bytecode easily. This is a best-effort attempt.

    System.out.println("   WARNING: Cannot extract bytecode from hidden classes directly");
    System.out.println("   Suggestion: Use -XX:+UnlockDiagnosticVMOptions -XX:+ShowHiddenFrames");
    System.out.println("              or modify RuntimeCompiler to optionally write class files");

    // Alternative: Show disassembly command
    String hexClassName = className.substring(className.lastIndexOf('/') + 1);
    System.out.println();
    System.out.println("   To disassemble this class, you can:");
    System.out.println("   1. Add debug output to RuntimeCompiler to save class bytes");
    System.out.println("   2. Run: javap -c -v <classfile>");
  }
}
