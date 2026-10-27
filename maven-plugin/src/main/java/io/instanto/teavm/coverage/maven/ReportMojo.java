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
package io.instanto.teavm.coverage.maven;

import io.instanto.teavm.coverage.CoverageReporter;
import io.instanto.teavm.coverage.JacocoReporter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Converts compiler data and standard Surefire/Failsafe output to line-only generic or JaCoCo
 * coverage XML.
 */
@Mojo(name = "report", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public final class ReportMojo extends AbstractMojo {
  @Parameter(defaultValue = "${project.build.directory}/teavm-coverage", required = true)
  private File dataDirectory;

  @Parameter(defaultValue = "${project.build.directory}/surefire-reports", required = true)
  private File surefireReports;

  @Parameter(defaultValue = "${project.build.directory}/failsafe-reports", required = true)
  private File failsafeReports;

  @Parameter(defaultValue = "${project.build.directory}/teavm-coverage.xml", required = true)
  private File outputFile;

  @Parameter(defaultValue = "${project.compileSourceRoots}", required = true)
  private List<String> sourceRoots;

  @Parameter(property = "teacoco.mode", defaultValue = "jacoco")
  private String mode;

  /** Additional compatible raw JaCoCo data, from JVM runs or other TeaCoCo reports. */
  @Parameter private List<File> executionData;

  @Parameter(property = "teacoco.skip", defaultValue = "false")
  private boolean skip;

  @Override
  public void execute() throws MojoExecutionException {
    if (skip) return;
    try {
      if ("jacoco".equals(mode)) {
        new JacocoReporter()
            .generate(
                dataDirectory.toPath(),
                List.of(surefireReports.toPath(), failsafeReports.toPath()),
                outputFile.toPath(),
                sourceRoots.stream().map(Path::of).toList(),
                executionData == null
                    ? List.of()
                    : executionData.stream().map(File::toPath).toList());
        getLog().info("TeaVM JaCoCo coverage: " + outputFile);
        return;
      }
      if (!"lines".equals(mode))
        throw new IllegalArgumentException("Unknown TeaCoCo mode: " + mode);
      new CoverageReporter(sourceRoots.stream().map(Path::of).toList())
          .generate(
              dataDirectory.toPath(),
              List.of(surefireReports.toPath(), failsafeReports.toPath()),
              outputFile.toPath());
      getLog().info("TeaVM Java line coverage: " + outputFile);
    } catch (IOException | RuntimeException e) {
      throw new MojoExecutionException("Could not report browser coverage: " + e.getMessage(), e);
    }
  }
}
