/*
 * Copyright 2026 Instanto contributors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0.
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.instanto.teacoco.gradle;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.stream.Collectors;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.testing.Test;

/** Adds coverage to existing JUnit/TeaVM test execution without changing the selected browser. */
public final class TeaCocoPlugin implements Plugin<Project> {
  @Override
  public void apply(Project project) {
    project.getPluginManager().apply(JavaPlugin.class);
    var extension = project.getExtensions().create("teaCoco", TeaCocoExtension.class);
    extension
        .getEnabled()
        .convention(
            project
                .getProviders()
                .gradleProperty("teaCoco.enabled")
                .map(Boolean::parseBoolean)
                .orElse(true));
    extension.getVersion().convention("0.1.0-SNAPSHOT");
    extension.getMode().convention("lines");
    extension
        .getClassRoots()
        .from(
            project
                .getExtensions()
                .getByType(SourceSetContainer.class)
                .getByName("main")
                .getOutput()
                .getClassesDirs());
    extension.getTestTask().convention("test");
    extension
        .getSourceRoots()
        .from(
            project
                .getExtensions()
                .getByType(SourceSetContainer.class)
                .getByName("main")
                .getAllJava()
                .getSourceDirectories());
    extension
        .getDataDirectory()
        .convention(project.getLayout().getBuildDirectory().dir("tea-coco/data"));
    extension
        .getReportFile()
        .convention(project.getLayout().getBuildDirectory().file("reports/tea-coco/coverage.xml"));
    var runtime = project.getConfigurations().create("teaCocoRuntime");
    runtime.setCanBeConsumed(false);
    runtime.setCanBeResolved(true);
    runtime.setDescription("TeaCoCo compiler extension and isolated reporter runtime");
    var report =
        project
            .getTasks()
            .register(
                "teaCocoCollect",
                JavaExec.class,
                task -> {
                  task.setGroup("verification");
                  task.setDescription("Collect TeaVM browser coverage as XML");
                  task.getMainClass().set("io.instanto.teavm.coverage.CoverageReporter");
                  task.setClasspath(runtime);
                  task.onlyIf("TeaCoCo is enabled", ignored -> extension.getEnabled().get());
                  task.notCompatibleWithConfigurationCache(
                      "Coverage configures the selected test task after DSL evaluation");
                });
    var lifecycle =
        project
            .getTasks()
            .register(
                "teaCocoReport",
                task -> {
                  task.setGroup("verification");
                  task.setDescription("Run browser tests and generate TeaCoCo coverage");
                  task.dependsOn(report);
                  task.onlyIf("TeaCoCo is enabled", ignored -> extension.getEnabled().get());
                });
    // TeaVM's own Gradle plugins configure the same public Test API. Resolve the chosen
    // task after the consumer has set its DSL, without depending on TeaVM plugin internals.
    project.afterEvaluate(
        ignored -> {
          var dependency = "io.instanto:tea-coco:" + extension.getVersion().get();
          project.getDependencies().add(runtime.getName(), dependency);
          if (!extension.getEnabled().get()) {
            return;
          }
          var buildDirectory =
              project
                  .getLayout()
                  .getBuildDirectory()
                  .get()
                  .getAsFile()
                  .toPath()
                  .toAbsolutePath()
                  .normalize();
          var dataDirectory =
              extension.getDataDirectory().get().getAsFile().toPath().toAbsolutePath().normalize();
          if (dataDirectory.equals(buildDirectory) || !dataDirectory.startsWith(buildDirectory)) {
            throw new IllegalArgumentException(
                "teaCoco.dataDirectory must be a dedicated directory below buildDirectory");
          }
          String mode = extension.getMode().get();
          if (!mode.equals("lines") && !mode.equals("jacoco"))
            throw new IllegalArgumentException("Unknown TeaCoCo mode: " + mode);
          boolean jacoco = mode.equals("jacoco");
          var prepare =
              project
                  .getTasks()
                  .register(
                      "teaCocoPrepare",
                      JavaExec.class,
                      task -> {
                        task.setGroup("verification");
                        task.setDescription("Prepare test-only JaCoCo bytecode copies");
                        task.getMainClass().set("io.instanto.teavm.coverage.JacocoPreparer");
                        task.setClasspath(runtime);
                        task.dependsOn(extension.getClassRoots());
                        task.onlyIf("JaCoCo mode is enabled", t -> jacoco);
                        task.getOutputs().dir(extension.getDataDirectory());
                        task.getOutputs().upToDateWhen(t -> false);
                        task.getOutputs()
                            .cacheIf("Coverage must prepare fresh run IDs", t -> false);
                        task.notCompatibleWithConfigurationCache(
                            "Coverage prepares a fresh test invocation");
                        task.doFirst(
                            t -> {
                              var arguments = new ArrayList<String>();
                              var previous = extension.getReportFile().get().getAsFile().toPath();
                              try {
                                Files.deleteIfExists(previous);
                                Files.deleteIfExists(
                                    previous.resolveSibling(previous.getFileName() + ".exec"));
                                Files.deleteIfExists(
                                    previous.resolveSibling("compiler-evidence.json"));
                              } catch (IOException e) {
                                throw new UncheckedIOException(e);
                              }
                              arguments.add(dataDirectory.toString());
                              arguments.add(roots(extension));
                              extension.getClassRoots().getFiles().stream()
                                  .map(File::getAbsolutePath)
                                  .sorted()
                                  .forEach(arguments::add);
                              task.setArgs(arguments);
                            });
                      });
          var tests = project.getTasks().named(extension.getTestTask().get(), Test.class);
          tests.configure(
              task -> {
                task.notCompatibleWithConfigurationCache(
                    "Coverage snapshots belong to this browser invocation");
                task.setClasspath(
                    jacoco
                        ? project
                            .files(dataDirectory.resolve("jacoco/classes").toFile())
                            .plus(task.getClasspath())
                            .plus(runtime)
                        : task.getClasspath().plus(runtime));
                if (jacoco) {
                  task.dependsOn(prepare);
                  // This task executes prepared bytecode. JVM coverage belongs to a separate task
                  // against original classes, whose exec files can be supplied to the reporter.
                  var agent =
                      task.getExtensions()
                          .findByType(org.gradle.testing.jacoco.plugins.JacocoTaskExtension.class);
                  if (agent != null) agent.setEnabled(false);
                }
                task.getReports().getJunitXml().getRequired().set(true);
                task.getReports().getJunitXml().getIncludeSystemOutLog().set(true);
                // Fresh compilation IDs must never be combined with a cached test execution.
                task.getOutputs().upToDateWhen(t -> false);
                task.getOutputs().cacheIf("Browser coverage must execute", t -> false);
                if (!jacoco) task.getOutputs().dir(extension.getDataDirectory());
                task.systemProperty("teavm.coverage.enabled", "true");
                task.systemProperty("teavm.coverage.mode", mode);
                task.systemProperty("teavm.coverage.sourceRoots", roots(extension));
                task.systemProperty(
                    "teavm.coverage.dataDirectory", extension.getDataDirectory().get().getAsFile());
                task.doFirst(
                    t -> {
                      if (!jacoco) clear(extension.getDataDirectory().get().getAsFile().toPath());
                      var output = extension.getReportFile().get().getAsFile().toPath();
                      try {
                        Files.deleteIfExists(output);
                        Files.deleteIfExists(output.resolveSibling("not-emitted.json"));
                        Files.deleteIfExists(output.resolveSibling("compiler-evidence.json"));
                        Files.deleteIfExists(output.resolveSibling(output.getFileName() + ".exec"));
                      } catch (IOException e) {
                        throw new UncheckedIOException(e);
                      }
                    });
                task.finalizedBy(report);
              });
          lifecycle.configure(task -> task.dependsOn(tests));
          report.configure(
              task -> {
                // A finalizer must not depend on the failed task, or Gradle skips the collection.
                task.mustRunAfter(tests);
                if (jacoco) task.getMainClass().set("io.instanto.teavm.coverage.JacocoReporter");
                task.doFirst(
                    t -> {
                      var arguments = new ArrayList<String>();
                      arguments.add(
                          extension.getDataDirectory().get().getAsFile().getAbsolutePath());
                      arguments.add(extension.getReportFile().get().getAsFile().getAbsolutePath());
                      arguments.add(roots(extension));
                      arguments.add(
                          tests
                              .get()
                              .getReports()
                              .getJunitXml()
                              .getOutputLocation()
                              .get()
                              .getAsFile()
                              .getAbsolutePath());
                      if (jacoco)
                        extension.getExecutionData().getFiles().stream()
                            .map(f -> "--exec=" + f.getAbsolutePath())
                            .sorted()
                            .forEach(arguments::add);
                      task.setArgs(arguments);
                    });
              });
        });
  }

  private static String roots(TeaCocoExtension extension) {
    return extension.getSourceRoots().getFiles().stream()
        .map(File::getAbsolutePath)
        .sorted()
        .collect(Collectors.joining(File.pathSeparator));
  }

  private static void clear(Path path) {
    if (!Files.exists(path)) {
      return;
    }
    try (var files = Files.walk(path)) {
      for (var file : files.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(file);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Could not clear previous coverage baselines", e);
    }
  }
}
