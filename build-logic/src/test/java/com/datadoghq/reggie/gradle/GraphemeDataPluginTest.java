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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraphemeDataPluginTest {
  @TempDir Path project;
  private Path target;

  @BeforeEach
  void setUp() throws Exception {
    Files.writeString(
        project.resolve("settings.gradle"), "rootProject.name = 'grapheme-plugin-test'\n");
    String classpath =
        Arrays.stream(System.getProperty("graphemeGeneratorClasspath").split(File.pathSeparator))
            .map(path -> "'" + path.replace("\\", "\\\\").replace("'", "\\'") + "'")
            .collect(Collectors.joining(", "));
    Files.writeString(
        project.resolve("build.gradle"),
        """
        plugins {
            id 'java'
            id 'reggie.grapheme.data'
        }

        tasks.named('generateGraphemeData') {
            generatorClasspath.setFrom(files(%s))
            // Validation fails before extraction, so these tests need only the executing JDK.
            def jdk = javaToolchains.compilerFor {
                languageVersion = JavaLanguageVersion.of(JavaVersion.current().majorVersion)
            }
            profileJdks.set([])
            [21, 25, 26, 27].each { profileJdks.add(jdk) }
        }
        """
            .formatted(classpath));
    target = project.resolve("src/main/java/com/datadoghq/reggie/runtime/GraphemeData.java");
    Files.createDirectories(target.getParent());
    Files.writeString(
        target, "package com.datadoghq.reggie.runtime; final class GraphemeData {}\n");
  }

  @Test
  void compilationUsesCommittedDataWithoutGeneration() {
    var result = runner("classes", "--configuration-cache").build();
    assertNull(result.task(":generateGraphemeData"));
    assertFalse(result.getOutput().contains(":generateGraphemeData"));
  }

  @Test
  void generationRequiresUnicodeInput() {
    var result = runner("generateGraphemeData").buildAndFail();
    assertTrue(result.getOutput().contains("unicodeData"), result.getOutput());
    assertTrue(result.getOutput().contains("doesn't have a configured value"), result.getOutput());
  }

  @Test
  void checksumFailurePreservesSourceWithConfigurationCache() throws Exception {
    Path unicode = project.resolve("DerivedCoreProperties.txt");
    Files.writeString(unicode, "invalid Unicode data\n");
    String source = Files.readString(target);
    var runner =
        runner("generateGraphemeData", "-PgraphemeUnicodeData=" + unicode, "--configuration-cache");
    var first = runner.buildAndFail();
    assertTrue(
        first.getOutput().contains("Unexpected JDK 22 DerivedCoreProperties.txt checksum"),
        first.getOutput());
    assertEquals(source, Files.readString(target));

    var second = runner.buildAndFail();
    assertTrue(second.getOutput().contains("Reusing configuration cache"), second.getOutput());
    assertTrue(
        second.getOutput().contains("Unexpected JDK 22 DerivedCoreProperties.txt checksum"),
        second.getOutput());
    assertEquals(source, Files.readString(target));
  }

  private GradleRunner runner(String... arguments) {
    return GradleRunner.create()
        .withProjectDir(project.toFile())
        .withPluginClasspath()
        .withArguments(arguments);
  }
}
