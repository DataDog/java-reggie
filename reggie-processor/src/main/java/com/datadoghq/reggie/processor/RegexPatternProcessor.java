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
@SupportedSourceVersion(SourceVersion.RELEASE_21)
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
    String packageName = elementUtils.getPackageOf(containingClass).getQualifiedName().toString();
    // For nested classes, use binary name (OuterClass$InnerClass) not simple name
    String qualifiedName = containingClass.getQualifiedName().toString();
    String simpleClassName = qualifiedName.substring(packageName.length() + 1).replace('.', '$');

    messager.printMessage(
        Diagnostic.Kind.NOTE,
        "Processing class " + simpleClassName + " with " + methods.size() + " annotated methods");

    // Generate matcher classes for each method
    for (ExecutableElement method : methods) {
      generateMatcherClass(packageName, method);
    }

    // Generate implementation class
    generateImplementationClass(packageName, simpleClassName, methods);
  }

  private String generateMatcherClassName(String methodName) {
    // Convert method name to matcher class name: phone -> PhoneMatcher
    return Character.toUpperCase(methodName.charAt(0)) + methodName.substring(1) + "Matcher";
  }

  private void generateMatcherClass(String packageName, ExecutableElement method) throws Exception {
    RegexPattern annotation = method.getAnnotation(RegexPattern.class);
    String pattern = annotation.value();
    String methodName = method.getSimpleName().toString();
    String matcherClassName = generateMatcherClassName(methodName);

    messager.printMessage(
        Diagnostic.Kind.NOTE,
        "Generating bytecode for matcher " + matcherClassName + " for pattern: " + pattern);

    // Use ASM to generate bytecode
    ReggiMatcherBytecodeGenerator generator =
        new ReggiMatcherBytecodeGenerator(packageName, matcherClassName, pattern);

    byte[] bytecode = generator.generate();

    // Write bytecode to .class file
    String qualifiedName = packageName + "." + matcherClassName;
    FileObject classFile = processingEnv.getFiler().createClassFile(qualifiedName);

    try (OutputStream os = classFile.openOutputStream()) {
      os.write(bytecode);
    }
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
      String matcherClassName = generateMatcherClassName(methodName);
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
