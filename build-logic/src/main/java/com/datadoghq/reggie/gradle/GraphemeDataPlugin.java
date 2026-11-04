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
package com.datadoghq.reggie.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.attributes.Usage;
import org.gradle.api.plugins.JvmToolchainsPlugin;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainService;

public class GraphemeDataPlugin implements Plugin<Project> {
  @Override
  public void apply(Project project) {
    project.getPluginManager().apply(JvmToolchainsPlugin.class);
    var toolchains = project.getExtensions().getByType(JavaToolchainService.class);
    var objects = project.getObjects();
    var dependencies = project.getConfigurations().dependencyScope("graphemeDataGenerator");
    var classpath =
        project
            .getConfigurations()
            .resolvable(
                "graphemeDataGeneratorClasspath",
                configuration -> {
                  configuration.extendsFrom(dependencies.get());
                  configuration.attributes(
                      attributes -> {
                        attributes.attribute(
                            Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, Usage.JAVA_RUNTIME));
                        attributes.attribute(
                            Category.CATEGORY_ATTRIBUTE,
                            objects.named(Category.class, Category.LIBRARY));
                        attributes.attribute(
                            LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                            objects.named(LibraryElements.class, LibraryElements.JAR));
                      });
                });
    var generator =
        project.getDependencyFactory().create("com.datadoghq.reggie.build:reggie-build-logic");
    generator.capabilities(
        capabilities ->
            capabilities.requireCapability(
                "com.datadoghq.reggie.build:reggie-build-logic-grapheme-generator"));
    dependencies.configure(configuration -> configuration.getDependencies().add(generator));

    project
        .getTasks()
        .register(
            "generateGraphemeData",
            GenerateGraphemeData.class,
            task -> {
              task.setGroup("build setup");
              task.setDescription(
                  "Regenerates and verifies the checked-in grapheme property tables");
              task.getGeneratorClasspath().from(classpath);
              task.getTargetFile()
                  .convention(
                      project
                          .getLayout()
                          .getProjectDirectory()
                          .file("src/main/java/com/datadoghq/reggie/runtime/GraphemeData.java"));
              task.getUnicodeData()
                  .convention(
                      project
                          .getLayout()
                          .getProjectDirectory()
                          .file(project.getProviders().gradleProperty("graphemeUnicodeData")));
              for (int feature : new int[] {21, 25, 26, 27}) {
                task.getProfileJdks()
                    .add(
                        toolchains.compilerFor(
                            spec ->
                                spec.getLanguageVersion().set(JavaLanguageVersion.of(feature))));
              }
            });
  }
}
