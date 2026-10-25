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
package io.instanto.teavm.coverage;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class CoverageReporterTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private Path root;
  private Path data;
  private Path reports;
  private Path output;

  @Before
  public void prepare() throws IOException {
    root = temporary.newFolder("src").toPath();
    data = temporary.newFolder("data").toPath();
    reports = temporary.newFolder("reports").toPath();
    output = temporary.getRoot().toPath().resolve("output/coverage.xml");
    Files.writeString(root.resolve("A&B.java"), "class Example {}\n");
    Files.writeString(
        data.resolve("build.json"),
        """
        {"version":1,"id":"build","lines":[{"file":"A&B.java","line":1},{"file":"A&B.java","line":2}],
         "notEmitted":[{"file":"A&B.java","line":3}]}
        """);
  }

  private void snapshot(String hits) throws IOException {
    Files.writeString(
        reports.resolve("TEST-one.xml"),
        """
        <testsuite><testcase><system-out><![CDATA[
        __TEAVM_COVERAGE_V1__:{"build":{"hits":%s}}
        ]]></system-out></testcase></testsuite>
        """
            .formatted(hits));
  }

  private void generate() throws IOException {
    new CoverageReporter(List.of(root)).generate(data, List.of(reports), output);
  }

  @Test
  public void exportsMissesAndSeparatesExcludedLines() throws IOException {
    snapshot("[1,0]");
    generate();
    var xml = Files.readString(output);
    assertTrue(xml.contains("A&amp;B.java"));
    assertTrue(xml.contains("lineNumber=\"1\" covered=\"true\""));
    assertTrue(xml.contains("lineNumber=\"2\" covered=\"false\""));
    assertFalse(xml.contains("lineNumber=\"3\""));
    assertFalse(xml.contains("branchesToCover"));
    assertTrue(Files.readString(output.resolveSibling("not-emitted.json")).contains("3"));
  }

  @Test
  public void preservesHitsAcrossCumulativeAndSeparateReports() throws IOException {
    snapshot("[1,0]");
    Files.copy(reports.resolve("TEST-one.xml"), reports.resolve("TEST-two.xml"));
    snapshot("[0,1]");
    generate();
    assertFalse(Files.readString(output).contains("covered=\"false\""));
  }

  @Test
  public void refusesMissingBrowserEvidence() throws IOException {
    Files.createDirectories(output.getParent());
    Files.writeString(output, "stale successful report");
    Files.writeString(output.resolveSibling("not-emitted.json"), "{}");
    assertThrows(IOException.class, this::generate);
    assertFalse(Files.exists(output));
    assertFalse(Files.exists(output.resolveSibling("not-emitted.json")));
  }

  @Test
  public void refusesMismatchedAndInvalidProbeValues() throws IOException {
    for (String hits : List.of("[1]", "[1,2]", "[1,true]", "[1,4294967297]")) {
      snapshot(hits);
      assertThrows(IOException.class, this::generate);
    }
  }

  @Test
  public void refusesUnknownBuild() throws IOException {
    snapshot("[1,0]");
    var path = reports.resolve("TEST-one.xml");
    Files.writeString(path, Files.readString(path).replace("\"build\"", "\"other\""));
    assertThrows(IOException.class, this::generate);
  }

  @Test
  public void rejectsAmbiguousSources() throws IOException {
    snapshot("[1,0]");
    var other = temporary.newFolder("other").toPath();
    Files.writeString(other.resolve("A&B.java"), "");
    assertThrows(
        IOException.class,
        () -> new CoverageReporter(List.of(root, other)).generate(data, List.of(reports), output));
  }

  @Test
  public void rejectsExternalEntities() throws IOException {
    Files.writeString(
        reports.resolve("TEST-xxe.xml"),
        """
        <!DOCTYPE testsuite [<!ENTITY e SYSTEM "file:///etc/passwd">]>
        <testsuite><system-out>&e;</system-out></testsuite>
        """);
    assertThrows(IOException.class, this::generate);
  }
}
