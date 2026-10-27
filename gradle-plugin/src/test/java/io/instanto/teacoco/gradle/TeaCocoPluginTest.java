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

import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TeaCocoPluginTest {
  @Rule public TemporaryFolder directory = new TemporaryFolder();

  private GradleRunner project(String configuration) throws Exception {
    Files.writeString(
        directory.getRoot().toPath().resolve("settings.gradle"), "rootProject.name='fixture'");
    Files.writeString(
        directory.getRoot().toPath().resolve("build.gradle"),
        "plugins { id 'io.instanto.teacoco' }\n" + configuration);
    return GradleRunner.create().withProjectDir(directory.getRoot()).withPluginClasspath();
  }

  @Test
  public void rejectsDestructiveDataDirectoryBeforeExecution() throws Exception {
    var result =
        project("teaCoco { dataDirectory = layout.projectDirectory }")
            .withArguments("teaCocoReport")
            .buildAndFail();
    assertTrue(result.getOutput().contains("must be a dedicated directory below buildDirectory"));
    assertTrue(Files.exists(directory.getRoot().toPath().resolve("build.gradle")));
  }

  @Test
  public void disabledCoverageNeedsNoRepositoryOrBrowser() throws Exception {
    var result = project("teaCoco { enabled = false }").withArguments("teaCocoReport").build();
    assertTrue(result.getOutput().contains(":teaCocoCollect SKIPPED"));
  }

  @Test
  public void kotlinDslExposesTheDocumentedSettings() throws Exception {
    var runner = project("");
    Files.delete(directory.getRoot().toPath().resolve("build.gradle"));
    Files.writeString(
        directory.getRoot().toPath().resolve("build.gradle.kts"),
        """
        plugins { id("io.instanto.teacoco") }
        teaCoco {
            enabled.set(false)
            testTask.set("test")
            sourceRoots.setFrom(layout.projectDirectory.dir("src/main/java"))
        }
        """);
    var result = runner.withArguments("teaCocoReport").build();
    assertTrue(result.getOutput().contains(":teaCocoCollect SKIPPED"));
  }
}
