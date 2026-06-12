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
package com.datadoghq.reggie.processor;

import com.datadoghq.reggie.ReggieOption;
import com.datadoghq.reggie.annotations.RegexPattern;
import com.google.auto.service.AutoService;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.*;
import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

/**
 * Annotation processor that generates optimized regex matcher classes from @RegexPattern
 * annotations. Processes abstract methods annotated with @RegexPattern and generates: 1.
 * Specialized matcher classes extending ReggieMatcher 2. Implementation classes with lazy
 * initialization
 */
@AutoService(Processor.class)
@SupportedAnnotationTypes("com.datadoghq.reggie.annotations.RegexPattern")
public class RegexPatternProcessor extends AbstractProcessor {

  private Elements elementUtils;
  private Types typeUtils;
  private Messager messager;

  @Override
  public synchronized void init(ProcessingEnvironment processingEnv) {
    super.init(processingEnv);
    this.elementUtils = processingEnv.getElementUtils();
    this.typeUtils = processingEnv.getTypeUtils();
    this.messager = processingEnv.getMessager();
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    // Group methods by their containing class
    Map<TypeElement, List<ExecutableElement>> methodsByClass = new HashMap<>();

    for (Element element : roundEnv.getElementsAnnotatedWith(RegexPattern.class)) {
      if (element.getKind() != ElementKind.METHOD) {
        messager.printMessage(
            Diagnostic.Kind.ERROR, "@RegexPattern can only be applied to methods", element);
        continue;
      }

      ExecutableElement method = (ExecutableElement) element;

      // Validate method signature
      if (!isValidAnnotatedMethod(method)) {
        continue;
      }

      TypeElement containingClass = (TypeElement) method.getEnclosingElement();
      methodsByClass.computeIfAbsent(containingClass, k -> new ArrayList<>()).add(method);
    }

    // Process each class
    for (Map.Entry<TypeElement, List<ExecutableElement>> entry : methodsByClass.entrySet()) {
      try {
        processClass(entry.getKey(), entry.getValue());
      } catch (Exception e) {
        messager.printMessage(
            Diagnostic.Kind.ERROR, "Failed to process class: " + e.getMessage(), entry.getKey());
        e.printStackTrace();
      }
    }

    return true;
  }

  private boolean isValidAnnotatedMethod(ExecutableElement method) {
    // Check if method is abstract
    if (!method.getModifiers().contains(Modifier.ABSTRACT)) {
      messager.printMessage(
          Diagnostic.Kind.ERROR, "@RegexPattern must be on abstract method", method);
      return false;
    }

    // Check if method has no parameters
    if (!method.getParameters().isEmpty()) {
      messager.printMessage(
          Diagnostic.Kind.ERROR, "@RegexPattern method must have no parameters", method);
      return false;
    }

    // Check if method returns ReggieMatcher
    TypeMirror returnType = method.getReturnType();
    TypeElement reggieMatcherElement =
        elementUtils.getTypeElement("com.datadoghq.reggie.runtime.ReggieMatcher");

    if (reggieMatcherElement == null) {
      messager.printMessage(Diagnostic.Kind.ERROR, "Cannot find ReggieMatcher class", method);
      return false;
    }

    if (!typeUtils.isAssignable(returnType, reggieMatcherElement.asType())) {
      messager.printMessage(
          Diagnostic.Kind.ERROR, "@RegexPattern method must return ReggieMatcher", method);
      return false;
    }

    return true;
  }

  private void processClass(TypeElement containingClass, List<ExecutableElement> methods)
      throws Exception {
    // Validate that the enclosing element is an abstract class, not an interface
    if (containingClass.getKind() != ElementKind.CLASS
        || !containingClass.getModifiers().contains(Modifier.ABSTRACT)) {
      messager.printMessage(
          Diagnostic.Kind.ERROR,
          "@RegexPattern methods must be declared in an abstract class, not an interface or"
              + " concrete class",
          containingClass);
      return;
    }

    String packageName = elementUtils.getPackageOf(containingClass).getQualifiedName().toString();
    // For nested classes, use binary name (OuterClass$InnerClass) not simple name
    String qualifiedName = containingClass.getQualifiedName().toString();
    String simpleClassName = qualifiedName.substring(packageName.length() + 1).replace('.', '$');

    messager.printMessage(
        Diagnostic.Kind.NOTE,
        "Processing class " + simpleClassName + " with " + methods.size() + " annotated methods");

    // Generate matcher classes for each method; track which methods are NATIVE (have a .class)
    List<ExecutableElement> nativeMethods = new ArrayList<>();
    for (ExecutableElement method : methods) {
      ReggieMatcherBytecodeGenerator.Realization realization =
          generateMatcherClass(packageName, simpleClassName, method);
      if (realization == ReggieMatcherBytecodeGenerator.Realization.NATIVE) {
        nativeMethods.add(method);
      }
      // TODO(Task 4): collect DELEGATE_PIKEVM and DELEGATE_FALLBACK methods for stub emission
    }

    // Generate implementation class — only NATIVE methods have matcher .class files to wire up
    generateImplementationClass(packageName, simpleClassName, nativeMethods);
  }

  private String generateMatcherClassName(String providerClassName, String methodName) {
    // Qualify with provider class name to avoid collisions across different provider classes.
    // e.g., ExamplePatterns.phone() -> ExamplePatterns_PhoneMatcher
    return providerClassName
        + "_"
        + Character.toUpperCase(methodName.charAt(0))
        + methodName.substring(1)
        + "Matcher";
  }

  private ReggieMatcherBytecodeGenerator.Realization generateMatcherClass(
      String packageName, String providerClassName, ExecutableElement method) throws Exception {
    RegexPattern annotation = method.getAnnotation(RegexPattern.class);
    String pattern = annotation.value();
    String methodName = method.getSimpleName().toString();
    String matcherClassName = generateMatcherClassName(providerClassName, methodName);

    messager.printMessage(
        Diagnostic.Kind.NOTE,
        "Generating bytecode for matcher " + matcherClassName + " for pattern: " + pattern);

    // Determine whether ALLOW_JDK_FALLBACK is set in options()
    boolean allowJdkFallback = false;
    for (ReggieOption opt : annotation.options()) {
      if (opt == ReggieOption.ALLOW_JDK_FALLBACK) {
        allowJdkFallback = true;
        break;
      }
    }

    ReggieMatcherBytecodeGenerator generator =
        new ReggieMatcherBytecodeGenerator(packageName, matcherClassName, pattern);
    ReggieMatcherBytecodeGenerator.Realization realization;
    try {
      realization = generator.resolveRealization(allowJdkFallback);
    } catch (UnsupportedOperationException e) {
      messager.printMessage(Diagnostic.Kind.ERROR, e.getMessage(), method);
      // Return NATIVE as a sentinel — processClass won't emit a MethodInfo for errored methods
      // since the build will already fail. Using NATIVE here avoids NPE in the caller.
      return ReggieMatcherBytecodeGenerator.Realization.NATIVE;
    }

    if (realization == ReggieMatcherBytecodeGenerator.Realization.NATIVE) {
      byte[] bytecode = generator.generate();

      // Loud compile-time warning for RICH_API_HYBRID patterns: native boolean matching, but the
      // rich MatchResult API (match/findMatch/findMatchFrom/matchBounded) delegates to
      // java.util.regex. These are fully correct at compile time (native correct booleans +
      // JDK-correct group extraction via the base defaults), so we still generate.
      com.datadoghq.reggie.codegen.analysis.PatternAnalyzer.MatchingStrategy strategy =
          generator.resolvedStrategy();
      com.datadoghq.reggie.codegen.analysis.StrategyJdkClassifier.StrategyJdkClass jdkClass =
          com.datadoghq.reggie.codegen.analysis.StrategyJdkClassifier.classifyJdkDependency(
              strategy);
      if (jdkClass
          == com.datadoghq.reggie.codegen.analysis.StrategyJdkClassifier.StrategyJdkClass
              .RICH_API_HYBRID) {
        messager.printMessage(
            Diagnostic.Kind.MANDATORY_WARNING,
            "@RegexPattern '"
                + pattern
                + "' compiles to a HYBRID matcher: native boolean matching but group extraction"
                + " (match/findMatch) delegates to java.util.regex (strategy "
                + strategy
                + ").",
            method);
      }

      // Write bytecode to .class file
      String qualifiedName = packageName + "." + matcherClassName;
      FileObject classFile = processingEnv.getFiler().createClassFile(qualifiedName);
      try (OutputStream os = classFile.openOutputStream()) {
        os.write(bytecode);
      }
    } else if (realization == ReggieMatcherBytecodeGenerator.Realization.DELEGATE_PIKEVM) {
      messager.printMessage(
          Diagnostic.Kind.NOTE,
          "@RegexPattern '"
              + pattern
              + "' delegates to runtime PikeVM (native, not bakeable at compile time).",
          method);
      // TODO(Task 4): emit PikeVM delegating stub
    } else {
      // DELEGATE_FALLBACK
      messager.printMessage(
          Diagnostic.Kind.MANDATORY_WARNING,
          "@RegexPattern '"
              + pattern
              + "' compiles to a JDK-delegating stub (java.util.regex at runtime) because"
              + " ALLOW_JDK_FALLBACK is set.",
          method);
      // TODO(Task 4): emit JDK fallback delegating stub
    }
    return realization;
  }

  private void generateImplementationClass(
      String packageName, String className, List<ExecutableElement> methods) throws IOException {
    String implClassName = className + "$Impl";

    messager.printMessage(
        Diagnostic.Kind.NOTE, "Generating bytecode for implementation class " + implClassName);

    // Prepare method info for bytecode generator
    java.util.List<ImplClassBytecodeGenerator.MethodInfo> methodInfos = new java.util.ArrayList<>();
    for (ExecutableElement method : methods) {
      String methodName = method.getSimpleName().toString();
      String matcherClassName = generateMatcherClassName(className, methodName);
      methodInfos.add(new ImplClassBytecodeGenerator.MethodInfo(methodName, matcherClassName));
    }

    // Use ASM to generate bytecode
    ImplClassBytecodeGenerator generator =
        new ImplClassBytecodeGenerator(packageName, className, methodInfos);

    byte[] bytecode = generator.generate();

    // Write bytecode to .class file
    String qualifiedName = packageName + "." + implClassName;
    FileObject classFile = processingEnv.getFiler().createClassFile(qualifiedName);

    try (OutputStream os = classFile.openOutputStream()) {
      os.write(bytecode);
    }

    // Generate service provider registration for ServiceLoader
    generateServiceProviderFile(packageName, className, implClassName);
  }

  /**
   * Generate META-INF/services file for ServiceLoader discovery. Creates a service provider
   * registration mapping the base class to its implementation.
   */
  private void generateServiceProviderFile(
      String packageName, String className, String implClassName) {
    try {
      String serviceInterface = packageName + "." + className;
      String serviceProvider = packageName + "." + implClassName;

      // Create META-INF/services/{serviceInterface} file
      FileObject resource =
          processingEnv
              .getFiler()
              .createResource(
                  StandardLocation.CLASS_OUTPUT, "", "META-INF/services/" + serviceInterface);

      try (PrintWriter writer = new PrintWriter(resource.openOutputStream())) {
        writer.println(serviceProvider);
      }

      messager.printMessage(
          Diagnostic.Kind.NOTE,
          "Generated service provider registration: "
              + serviceInterface
              + " -> "
              + serviceProvider);
    } catch (IOException e) {
      messager.printMessage(
          Diagnostic.Kind.ERROR, "Failed to generate service provider file: " + e.getMessage());
    }
  }

  private void generateSimpleMatching(PrintWriter out, String pattern) {
    // For PoC: Handle simple patterns like \d{3}-\d{3}-\d{4} (phone number)
    if (pattern.matches("\\\\d\\{\\d+\\}(-\\\\d\\{\\d+\\})*")) {
      // Parse the pattern to extract digit counts
      String[] parts = pattern.split("-");
      out.println("        int pos = 0;");

      for (int i = 0; i < parts.length; i++) {
        String part = parts[i];
        // Extract digit count from \d{n}
        int count = Integer.parseInt(part.replaceAll("\\D", ""));

        if (i > 0) {
          out.println(
              "        if (pos >= input.length() || input.charAt(pos) != '-') return false;");
          out.println("        pos++;");
        }

        out.println("        if (pos + " + count + " > input.length()) return false;");
        out.println("        for (int i = 0; i < " + count + "; i++) {");
        out.println("            if (!Character.isDigit(input.charAt(pos++))) return false;");
        out.println("        }");
      }

      out.println("        return pos == input.length();");
    } else {
      // Fallback
      out.println("        return java.util.regex.Pattern.matches(PATTERN, input);");
    }
  }

  private String toJavaString(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private String escapeJavaDoc(String s) {
    return s.replace("*/", "*\\/");
  }
}
