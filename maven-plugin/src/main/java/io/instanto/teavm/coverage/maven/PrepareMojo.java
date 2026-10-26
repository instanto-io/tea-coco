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

import io.instanto.teavm.coverage.JacocoPreparer;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;

/**
 * Prepare test-only copies; configure Surefire classesDirectory to dataDirectory/jacoco/classes.
 */
@Mojo(
    name = "prepare",
    defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES,
    requiresDependencyResolution = ResolutionScope.TEST,
    threadSafe = true)
public final class PrepareMojo extends AbstractMojo {
  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  @Parameter(property = "tea-coco.mode", defaultValue = "lines")
  private String mode;

  @Parameter(defaultValue = "${project.build.directory}/teavm-coverage", required = true)
  private File dataDirectory;

  @Parameter(defaultValue = "${project.build.directory}/teavm-coverage.xml", required = true)
  private File outputFile;

  @Parameter(defaultValue = "${project.compileSourceRoots}", required = true)
  private List<String> sourceRoots;

  @Parameter private List<File> classRoots;
  @Parameter private List<String> dependencyIncludes;

  @Override
  public void execute() throws MojoExecutionException {
    if ("lines".equals(mode)) return;
    if (!"jacoco".equals(mode)) throw new MojoExecutionException("Unknown tea-coco mode: " + mode);
    try {
      Path build = Path.of(project.getBuild().getDirectory()).toAbsolutePath().normalize();
      Path directory = dataDirectory.toPath().toAbsolutePath().normalize();
      if (directory.equals(build) || !directory.startsWith(build))
        throw new IOException("Use a dedicated dataDirectory below the project build directory");
      List<Path> inputs = new ArrayList<>();
      inputs.add(Path.of(project.getBuild().getOutputDirectory()));
      if (classRoots != null) classRoots.stream().map(File::toPath).forEach(inputs::add);
      Set<String> missing =
          new HashSet<>(dependencyIncludes == null ? List.of() : dependencyIncludes);
      for (var artifact : project.getArtifacts()) {
        String key = artifact.getGroupId() + ":" + artifact.getArtifactId();
        if (missing.remove(key)) inputs.add(artifact.getFile().toPath());
      }
      if (!missing.isEmpty()) throw new IOException("Unresolved coverage dependencies: " + missing);
      Files.deleteIfExists(outputFile.toPath());
      Files.deleteIfExists(outputFile.toPath().resolveSibling(outputFile.getName() + ".exec"));
      Files.deleteIfExists(outputFile.toPath().resolveSibling("compiler-evidence.json"));
      var manifest =
          new JacocoPreparer()
              .prepare(directory, sourceRoots.stream().map(Path::of).toList(), inputs);
      getLog()
          .info(
              "Prepared "
                  + manifest.classes().size()
                  + " JaCoCo classes in "
                  + directory.resolve("jacoco/classes"));
    } catch (IOException | RuntimeException e) {
      throw new MojoExecutionException("Could not prepare browser coverage: " + e.getMessage(), e);
    }
  }
}
