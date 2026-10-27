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
package example;

import static org.junit.Assert.*;

import io.instanto.teavm.coverage.JacocoReporter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.jacoco.core.tools.ExecFileLoader;
import org.junit.Test;
import org.junit.runner.JUnitCore;

public class JacocoReportIT {
  @Test
  public void reportsComplementaryBranchesAndRetainsUnreachableMethods() throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    var document =
        factory.newDocumentBuilder().parse(Path.of("target/teavm-coverage.xml").toFile());
    var xpath = javax.xml.xpath.XPathFactory.newInstance().newXPath();
    assertEquals(
        "2", xpath.evaluate("//method[@name='choose']/counter[@type='BRANCH']/@covered", document));
    assertEquals(
        "2",
        xpath.evaluate("//method[@name='neverCalled']/counter[@type='BRANCH']/@missed", document));
    assertEquals(
        "2", xpath.evaluate("//method[@name='value']/counter[@type='BRANCH']/@covered", document));
    assertEquals(
        "2",
        xpath.evaluate("//method[@name='staticValue']/counter[@type='BRANCH']/@covered", document));
    byte[] original = Files.readAllBytes(Path.of("target/classes/example/ProbeCases.class"));
    assertArrayEquals(
        original,
        Files.readAllBytes(
            Path.of("target/teavm-coverage/jacoco/originals/example/ProbeCases.class")));
    assertFalse(
        Arrays.equals(
            original,
            Files.readAllBytes(
                Path.of("target/teavm-coverage/jacoco/classes/example/ProbeCases.class"))));
    try (var jar = new java.util.jar.JarFile("target/teacoco-jacoco-example-0.1.0-SNAPSHOT.jar");
        var bytes = jar.getInputStream(jar.getJarEntry("example/ProbeCases.class"))) {
      assertArrayEquals(original, bytes.readAllBytes());
    }
    ExecFileLoader loader = new ExecFileLoader();
    loader.load(Path.of("target/teavm-coverage.xml.exec").toFile());
    assertFalse(loader.getExecutionDataStore().getContents().isEmpty());
  }

  @Test
  public void collectsWhenTheBrowserTestFailsAndRejectsStaleData() throws Exception {
    var original = System.out;
    var captured = new ByteArrayOutputStream();
    try (var stream = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
      System.setOut(stream);
      var result = JUnitCore.runClasses(ExpectedFailureScenario.class);
      assertEquals(1, result.getFailureCount());
      assertTrue(result.getFailures().get(0).getTrace().contains("intentional browser failure"));
    } finally {
      System.setOut(original);
    }
    String text = captured.toString(StandardCharsets.UTF_8);
    assertTrue(text.contains(JacocoReporter.PREFIX));
    Path reports = Path.of("target/expected-failure-reports");
    Files.createDirectories(reports);
    Path junit = reports.resolve("TEST-expected.xml");
    Files.writeString(
        junit, "<testsuite><system-out><![CDATA[" + text + "]]></system-out></testsuite>");
    Path result = Path.of("target/expected-failure-coverage.xml");
    new JacocoReporter()
        .generate(
            Path.of("target/teavm-coverage"),
            List.of(reports),
            result,
            List.of(Path.of("src/main/java")),
            List.of());
    assertTrue(Files.isRegularFile(result));
    Files.writeString(
        junit,
        "<testsuite><system-out>" + JacocoReporter.PREFIX + "stale|</system-out></testsuite>");
    assertThrows(
        IOException.class,
        () ->
            new JacocoReporter()
                .generate(
                    Path.of("target/teavm-coverage"),
                    List.of(reports),
                    result,
                    List.of(),
                    List.of()));
    assertFalse(Files.exists(result));
  }
}
