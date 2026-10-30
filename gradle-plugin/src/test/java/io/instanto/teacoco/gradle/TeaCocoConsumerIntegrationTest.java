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

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class TeaCocoConsumerIntegrationTest {
  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

  @Test
  public void lineCoverageConsumerWithLibraryAndApplicationPlugins() throws Exception {
    Path projectDir = temporaryFolder.newFolder("line-consumer").toPath();
    copyDirectory(ROOT.resolve("example/src"), projectDir.resolve("src"));

    Files.writeString(
        projectDir.resolve("settings.gradle"),
        """
        pluginManagement {
            repositories {
                mavenLocal()
                gradlePluginPortal()
                mavenCentral()
            }
        }
        rootProject.name = 'independent-line-consumer'
        """);

    String buildGradle =
        """
        plugins {
            id 'java'
            id 'org.teavm.library' version '0.16.0'
            id 'io.instanto.teacoco'
        }
        repositories {
            mavenLocal()
            mavenCentral()
        }
        teaCoco { mode = 'lines' }
        dependencies {
            testImplementation 'junit:junit:4.13.2'
            testRuntimeOnly 'org.teavm:teavm-jso-impl:0.16.0'
        }
        test {
            useJUnit()
            include '**/RoutesTest.class'
            systemProperty 'teavm.junit.js', 'true'
            systemProperty 'teavm.junit.js.runner', 'browser-chrome'
            systemProperty 'teavm.junit.minified', 'true'
            systemProperty 'teavm.junit.optimized', 'true'
        }
        """;

    Files.writeString(projectDir.resolve("build.gradle"), buildGradle);

    GradleRunner runner =
        GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments("clean", "teaCocoReport", "--stacktrace");

    BuildResult result = runner.build();
    assertTrue(result.getOutput().contains("BUILD SUCCESSFUL"));

    Path coverageXml = projectDir.resolve("build/reports/teacoco/coverage.xml");
    assertTrue("Coverage XML must exist", Files.isRegularFile(coverageXml));
    checkLineCoverageReport(projectDir, coverageXml);

    // Verify application plugin compatibility
    Files.writeString(
        projectDir.resolve("build.gradle"),
        buildGradle.replace("'org.teavm.library'", "'org.teavm'"));
    BuildResult appResult = runner.withArguments("clean", "teaCocoReport").build();
    assertTrue(appResult.getOutput().contains("BUILD SUCCESSFUL"));
    checkLineCoverageReport(projectDir, coverageXml);

    // Verify disabled coverage opt-out
    runner.withArguments("clean", "test", "-PteaCoco.enabled=false").build();
    assertFalse(Files.exists(projectDir.resolve("build/teacoco/data")));
    assertFalse(Files.exists(projectDir.resolve("build/reports/teacoco/coverage.xml")));
  }

  @Test
  public void jacocoBranchCoverageConsumer() throws Exception {
    Path projectDir = temporaryFolder.newFolder("jacoco-consumer").toPath();
    copyDirectory(ROOT.resolve("jacoco-example/src"), projectDir.resolve("src"));
    Files.deleteIfExists(projectDir.resolve("src/test/java/example/JacocoReportIT.java"));

    Files.writeString(
        projectDir.resolve("settings.gradle"),
        """
        pluginManagement {
            repositories {
                mavenLocal()
                gradlePluginPortal()
                mavenCentral()
            }
        }
        rootProject.name = 'independent-jacoco-consumer'
        """);

    Files.writeString(
        projectDir.resolve("build.gradle"),
        """
        plugins {
            id 'java'
            id 'jacoco'
            id 'org.teavm.library' version '0.16.0'
            id 'io.instanto.teacoco'
        }
        repositories {
            mavenLocal()
            mavenCentral()
        }
        dependencies {
            testImplementation 'junit:junit:4.13.2'
            testRuntimeOnly 'org.teavm:teavm-jso-impl:0.16.0'
        }
        test {
            useJUnit()
            include '**/ProbesTest.class'
            systemProperty 'teavm.junit.js', 'true'
            systemProperty 'teavm.junit.js.runner', 'browser-chrome'
            systemProperty 'teavm.junit.minified', 'true'
            systemProperty 'teavm.junit.optimized', 'true'
        }
        """);

    GradleRunner runner =
        GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments("clean", "teaCocoReport", "--stacktrace");

    BuildResult result = runner.build();
    assertTrue(result.getOutput().contains("BUILD SUCCESSFUL"));

    Path coverageXml = projectDir.resolve("build/reports/teacoco/coverage.xml");
    assertTrue("JaCoCo coverage XML missing", Files.isRegularFile(coverageXml));
    assertTrue(Files.isRegularFile(projectDir.resolve("build/reports/teacoco/coverage.xml.exec")));
    assertTrue(Files.isRegularFile(projectDir.resolve("build/reports/teacoco/compiler-evidence.json")));

    Document doc = parseXml(coverageXml);
    assertEquals("report", doc.getDocumentElement().getTagName());

    for (String method : List.of("choose", "value", "staticValue")) {
      Element counter = findBranchCounter(doc, method);
      assertNotNull("Counter for " + method + " must exist", counter);
      assertEquals("2", counter.getAttribute("covered"));
    }
    Element missed = findBranchCounter(doc, "neverCalled");
    assertNotNull(missed);
    assertEquals("2", missed.getAttribute("missed"));

    // No clean: test execution must discard stale compilation IDs rather than use cached evidence
    Path manifest = projectDir.resolve("build/teacoco/data/jacoco/manifest.json");
    assertTrue("Manifest must exist", Files.isRegularFile(manifest));
    String initialManifest = Files.readString(manifest);
    BuildResult rerun = runner.withArguments("teaCocoReport").build();
    assertTrue(rerun.getOutput().contains("BUILD SUCCESSFUL"));
    String secondManifest = Files.readString(manifest);
    assertNotEquals(
        "Fresh compilation run ID must be generated on re-run", initialManifest, secondManifest);
  }

  @Test
  public void failingTestPreservesFailingExitCodeAndEmitsReport() throws Exception {
    Path projectDir = temporaryFolder.newFolder("failing-consumer").toPath();
    copyDirectory(ROOT.resolve("example/src"), projectDir.resolve("src"));

    // Introduce failing assertion into test
    Path testFile = projectDir.resolve("src/test/java/example/RoutesTest.java");
    Files.writeString(testFile, Files.readString(testFile).replace("assertEquals(13,", "assertEquals(99,"));

    Files.writeString(
        projectDir.resolve("settings.gradle"),
        "rootProject.name = 'failing-test-fixture'");

    Files.writeString(
        projectDir.resolve("build.gradle"),
        """
        plugins {
            id 'java'
            id 'org.teavm.library' version '0.16.0'
            id 'io.instanto.teacoco'
        }
        repositories {
            mavenLocal()
            mavenCentral()
        }
        teaCoco { mode = 'lines' }
        dependencies {
            testImplementation 'junit:junit:4.13.2'
            testRuntimeOnly 'org.teavm:teavm-jso-impl:0.16.0'
        }
        test {
            useJUnit()
            include '**/RoutesTest.class'
            systemProperty 'teavm.junit.js', 'true'
            systemProperty 'teavm.junit.js.runner', 'browser-chrome'
            systemProperty 'teavm.junit.minified', 'true'
            systemProperty 'teavm.junit.optimized', 'true'
        }
        """);

    BuildResult result =
        GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments("clean", "test")
            .buildAndFail();

    assertTrue(result.getOutput().contains("Execution failed for task ':test'"));
    // Verify report was still emitted by the test task finalizer
    assertTrue(Files.isRegularFile(projectDir.resolve("build/reports/teacoco/coverage.xml")));
  }

  private static void checkLineCoverageReport(Path projectDir, Path coverageXml) throws Exception {
    Document doc = parseXml(coverageXml);
    NodeList lines = doc.getElementsByTagName("lineToCover");
    Map<Integer, Boolean> hits = new HashMap<>();
    for (int i = 0; i < lines.getLength(); i++) {
      Element el = (Element) lines.item(i);
      hits.put(
          Integer.parseInt(el.getAttribute("lineNumber")),
          Boolean.parseBoolean(el.getAttribute("covered")));
    }
    List<String> sourceLines = Files.readAllLines(projectDir.resolve("src/main/java/example/Routes.java"));
    for (int n = 1; n <= sourceLines.size(); n++) {
      String line = sourceLines.get(n - 1);
      if (line.contains("// covered")) {
        assertEquals("Line " + n + " must be covered", Boolean.TRUE, hits.get(n));
      }
      if (line.contains("// uncovered")) {
        assertEquals("Line " + n + " must be uncovered", Boolean.FALSE, hits.get(n));
      }
      if (line.contains("// not emitted")) {
        assertFalse("Line " + n + " must not be emitted", hits.containsKey(n));
      }
    }
  }

  private static Document parseXml(Path path) throws Exception {
    var dbf = DocumentBuilderFactory.newInstance();
    dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
    dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    return dbf.newDocumentBuilder().parse(path.toFile());
  }

  private static Element findBranchCounter(Document doc, String methodName) {
    NodeList methods = doc.getElementsByTagName("method");
    for (int i = 0; i < methods.getLength(); i++) {
      Element method = (Element) methods.item(i);
      if (methodName.equals(method.getAttribute("name"))) {
        NodeList counters = method.getElementsByTagName("counter");
        for (int j = 0; j < counters.getLength(); j++) {
          Element counter = (Element) counters.item(j);
          if ("BRANCH".equals(counter.getAttribute("type"))) {
            return counter;
          }
        }
      }
    }
    return null;
  }

  private static void copyDirectory(Path source, Path target) throws IOException {
    try (var stream = Files.walk(source)) {
      for (Path path : stream.toList()) {
        Path dest = target.resolve(source.relativize(path));
        if (Files.isDirectory(path)) {
          Files.createDirectories(dest);
        } else {
          Files.createDirectories(dest.getParent());
          Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
  }
}
