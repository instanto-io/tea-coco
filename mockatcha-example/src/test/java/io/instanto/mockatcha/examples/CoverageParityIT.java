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
package io.instanto.mockatcha.examples;

import static org.junit.Assert.*;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Validates JVM/browser coverage merging, probe parity, and packaged bytecode integrity.
 */
public class CoverageParityIT {

  record Counter(int covered, int missed) {
    int total() {
      return covered + missed;
    }
  }

  @Test
  public void verifiesMergedParityAndUntouchedJarBytecode() throws Exception {
    Path target = Path.of("target");
    Path jvmPath = target.resolve("site/jacoco/jacoco.xml");
    Path browserPath = target.resolve("browser-coverage.xml");
    Path mergedPath = target.resolve("teavm-coverage.xml");

    assertTrue("JVM coverage XML missing", Files.isRegularFile(jvmPath));
    assertTrue("Browser coverage XML missing", Files.isRegularFile(browserPath));
    assertTrue("Merged coverage XML missing", Files.isRegularFile(mergedPath));

    var dbf = DocumentBuilderFactory.newInstance();
    dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
    dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    Document jvmDoc = dbf.newDocumentBuilder().parse(jvmPath.toFile());
    Document browserDoc = dbf.newDocumentBuilder().parse(browserPath.toFile());
    Document mergedDoc = dbf.newDocumentBuilder().parse(mergedPath.toFile());

    Map<String, Map<String, Counter>> counts = new LinkedHashMap<>();
    counts.put("JVM", extractTotals(jvmDoc));
    counts.put("Browser", extractTotals(browserDoc));
    counts.put("Merged", extractTotals(mergedDoc));

    for (String metric : List.of("LINE", "BRANCH")) {
      int jvmTotal = counts.get("JVM").get(metric).total();
      int browserTotal = counts.get("Browser").get(metric).total();
      int mergedTotal = counts.get("Merged").get(metric).total();

      assertEquals("Denominator changed for " + metric, jvmTotal, browserTotal);
      assertEquals("Denominator changed for " + metric, jvmTotal, mergedTotal);

      int mergedCovered = counts.get("Merged").get(metric).covered();
      int jvmCovered = counts.get("JVM").get(metric).covered();
      int browserCovered = counts.get("Browser").get(metric).covered();
      assertTrue(
          "Merged coverage must exceed both isolated executions for " + metric,
          mergedCovered > Math.max(jvmCovered, browserCovered));
    }

    Map<String, Document> reports = Map.of("JVM", jvmDoc, "Browser", browserDoc, "Merged", mergedDoc);
    Map<String, Integer> expectedSubmitBranchCovered = Map.of("JVM", 1, "Browser", 5, "Merged", 6);

    for (var entry : expectedSubmitBranchCovered.entrySet()) {
      String name = entry.getKey();
      int expectedCovered = entry.getValue();
      Counter submitCounter = findSubmitBranchCounter(reports.get(name));
      assertNotNull("Submit method counter missing in " + name, submitCounter);
      assertEquals(name + " covered branch mismatch", expectedCovered, submitCounter.covered());
      assertEquals(name + " missed branch mismatch", 6 - expectedCovered, submitCounter.missed());
    }

    // Verify application scope isolation (no framework/mock bytecode leaked into report)
    for (var entry : reports.entrySet()) {
      NodeList classes = entry.getValue().getElementsByTagName("class");
      for (int i = 0; i < classes.getLength(); i++) {
        Element cls = (Element) classes.item(i);
        String clsName = cls.getAttribute("name");
        assertTrue(
            "Mock/framework code entered application scope: " + clsName,
            clsName.startsWith("io/instanto/mockatcha/examples/"));
      }
    }

    // Verify packaged application JAR bytes remained completely unmodified
    String classFile = "io/instanto/mockatcha/examples/TimesheetService.class";
    byte[] original = Files.readAllBytes(target.resolve("classes").resolve(classFile));
    byte[] savedOriginal =
        Files.readAllBytes(target.resolve("teavm-coverage/jacoco/originals").resolve(classFile));
    byte[] instrumented =
        Files.readAllBytes(target.resolve("teavm-coverage/jacoco/classes").resolve(classFile));

    assertArrayEquals("Originals store diverged from compiled classes", original, savedOriginal);
    assertFalse("Instrumented classes must not match originals", Arrays.equals(original, instrumented));

    Path jarPath = target.resolve("mockatcha-teacoco-demo-0.1.0-SNAPSHOT.jar");
    assertTrue("Packaged demo JAR missing", Files.isRegularFile(jarPath));
    try (JarFile jar = new JarFile(jarPath.toFile())) {
      var entry = jar.getJarEntry(classFile);
      assertNotNull("Class missing from packaged JAR", entry);
      try (InputStream in = jar.getInputStream(entry)) {
        assertArrayEquals("Packaged JAR contained instrumented bytecode", original, in.readAllBytes());
      }
    }

    // Write markdown summary
    List<String> lines = new ArrayList<>();
    lines.add("# Timesheet coverage comparison");
    lines.add("");
    lines.add("| Runtime | Lines | Branch outcomes |");
    lines.add("| --- | ---: | ---: |");
    for (var e : counts.entrySet()) {
      Counter line = e.getValue().get("LINE");
      Counter branch = e.getValue().get("BRANCH");
      lines.add(
          String.format(
              "| %s | %d / %d | %d / %d |",
              e.getKey(), line.covered(), line.total(), branch.covered(), branch.total()));
    }
    lines.add("");
    lines.add(
        "The JVM covers the missing-draft path; browser tests cover ordinary weeks and overtime.");
    lines.add(
        "The merged report ORs compatible raw probes. Application JAR bytes remain unchanged.");
    lines.add("");

    String summary = String.join("\n", lines);
    Files.writeString(target.resolve("coverage-comparison.md"), summary);
    System.out.println(summary);
  }

  private static Map<String, Counter> extractTotals(Document doc) {
    Map<String, Counter> result = new HashMap<>();
    Element report = doc.getDocumentElement();
    NodeList children = report.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      if (children.item(i) instanceof Element el && "counter".equals(el.getTagName())) {
        String type = el.getAttribute("type");
        int covered = Integer.parseInt(el.getAttribute("covered"));
        int missed = Integer.parseInt(el.getAttribute("missed"));
        result.put(type, new Counter(covered, missed));
      }
    }
    return result;
  }

  private static Counter findSubmitBranchCounter(Document doc) {
    NodeList classes = doc.getElementsByTagName("class");
    for (int i = 0; i < classes.getLength(); i++) {
      Element cls = (Element) classes.item(i);
      if ("io/instanto/mockatcha/examples/TimesheetService".equals(cls.getAttribute("name"))) {
        NodeList methods = cls.getElementsByTagName("method");
        for (int j = 0; j < methods.getLength(); j++) {
          Element method = (Element) methods.item(j);
          if ("submit".equals(method.getAttribute("name"))) {
            NodeList counters = method.getElementsByTagName("counter");
            for (int k = 0; k < counters.getLength(); k++) {
              Element counter = (Element) counters.item(k);
              if ("BRANCH".equals(counter.getAttribute("type"))) {
                return new Counter(
                    Integer.parseInt(counter.getAttribute("covered")),
                    Integer.parseInt(counter.getAttribute("missed")));
              }
            }
          }
        }
      }
    }
    return null;
  }
}
