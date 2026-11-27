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

import java.util.ArrayList;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.toolchain.JavaCompiler;
import org.gradle.process.ExecOperations;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(
    because = "Updates checked-in source and verifies every JDK profile on each invocation")
public abstract class GenerateGraphemeData extends DefaultTask {
  @Classpath
  public abstract ConfigurableFileCollection getGeneratorClasspath();

  @InputFile
  @PathSensitive(PathSensitivity.NONE)
  public abstract RegularFileProperty getUnicodeData();

  @InputFile
  @PathSensitive(PathSensitivity.NONE)
  public abstract RegularFileProperty getTargetFile();

  @Nested
  public abstract ListProperty<JavaCompiler> getProfileJdks();

  @Inject
  protected abstract ExecOperations getExecOperations();

  @TaskAction
  public void generate() {
    var jdks = getProfileJdks().get();
    var arguments = new ArrayList<String>();
    for (var jdk : jdks) {
      arguments.add(jdk.getMetadata().getInstallationPath().getAsFile().getAbsolutePath());
    }
    arguments.add(getUnicodeData().get().getAsFile().getAbsolutePath());
    arguments.add(getTargetFile().get().getAsFile().getAbsolutePath());
    arguments.add(getTemporaryDir().getAbsolutePath());

    getExecOperations()
        .javaexec(
            spec -> {
              spec.setExecutable(
                  jdks.getFirst().getMetadata().getInstallationPath().file("bin/java").getAsFile());
              spec.classpath(getGeneratorClasspath());
              spec.getMainClass().set("com.datadoghq.reggie.gradle.GraphemeDataGenerator");
              spec.args(arguments);
            })
        .assertNormalExitValue();
  }
}
