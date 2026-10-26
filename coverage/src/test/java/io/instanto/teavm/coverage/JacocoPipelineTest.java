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

import java.io.*;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import javax.xml.parsers.DocumentBuilderFactory;
import org.jacoco.core.data.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tools.jackson.databind.ObjectMapper;

public class JacocoPipelineTest {
  @Rule public TemporaryFolder directory = new TemporaryFolder();

  @Test
  public void mergesComplementaryRealProbesAndRejectsDifferentJvmClasses() throws Exception {
    for (String release : List.of("8", "17")) {
      Path root = directory.newFolder("release" + release).toPath();
      Path sources = root.resolve("src"),
          classes = root.resolve("classes"),
          data = root.resolve("data");
      Files.createDirectories(sources.resolve("fixture"));
      Files.createDirectories(classes);
      Path source = sources.resolve("fixture/Sample.java");
      Files.writeString(
          source,
          "package fixture; public class Sample { public static int choose(boolean a) { return a ?"
              + " 1 : 2; } }");
      assertEquals(
          0,
          ToolProvider.getSystemJavaCompiler()
              .run(
                  null,
                  null,
                  null,
                  "--release",
                  release,
                  "-g",
                  "-d",
                  classes.toString(),
                  source.toString()));
      byte[] original = Files.readAllBytes(classes.resolve("fixture/Sample.class"));
      var manifest = new JacocoPreparer().prepare(data, List.of(sources), List.of(classes));
      assertArrayEquals(original, Files.readAllBytes(classes.resolve("fixture/Sample.class")));
      assertArrayEquals(
          original, Files.readAllBytes(data.resolve("jacoco/originals/fixture/Sample.class")));
      String a = execute(data, manifest.run(), true), b = execute(data, manifest.run(), false);
      Path reports = root.resolve("reports");
      Files.createDirectories(reports);
      Files.writeString(
          reports.resolve("TEST-browser.xml"),
          "<testsuite><system-out><![CDATA[" + a + "]]></system-out></testsuite>");
      Files.createDirectories(data.resolve("jacoco/evidence"));
      new ObjectMapper()
          .writeValue(
              data.resolve("jacoco/evidence/test.json").toFile(),
              new JacocoCompilerPlugin.Evidence(
                  manifest.run(), Set.of("fixture/Sample.choose(Z)I"), Set.of()));
      Path output = root.resolve("coverage.xml");
      new JacocoReporter().generate(data, List.of(reports), output, List.of(sources), List.of());
      assertEquals("1", branches(output, "covered"));
      assertEquals("1", branches(output, "missed"));
      String[] parts = b.substring(b.indexOf('|') + 1).trim().split(";")[0].split(",");
      boolean[] hits = new boolean[parts[2].length()];
      for (int i = 0; i < hits.length; i++) hits[i] = parts[2].charAt(i) == '1';
      Path jvm = root.resolve("jvm.exec");
      long id = Long.parseUnsignedLong(parts[0], 16);
      writeExec(jvm, new ExecutionData(id, parts[1], hits));
      new JacocoReporter().generate(data, List.of(reports), output, List.of(sources), List.of(jvm));
      assertEquals("2", branches(output, "covered"));
      assertEquals("0", branches(output, "missed"));
      writeExec(jvm, new ExecutionData(id ^ 1, parts[1], hits));
      assertThrows(
          IOException.class,
          () ->
              new JacocoReporter()
                  .generate(data, List.of(reports), output, List.of(sources), List.of(jvm)));
      assertFalse(Files.exists(output));
      new JacocoPreparer().prepare(data, List.of(sources), List.of(classes));
      assertThrows(
          IOException.class,
          () ->
              new JacocoReporter()
                  .generate(data, List.of(reports), output, List.of(sources), List.of()));
      assertThrows(
          IOException.class,
          () ->
              new JacocoPreparer()
                  .prepare(data, List.of(sources), List.of(data.resolve("jacoco/classes"))));
      assertFalse(Files.exists(data.resolve("jacoco/manifest.json")));
    }
  }

  @Test
  public void refusesToReplaceAnUnownedPreparationDirectory() throws Exception {
    Path root = directory.newFolder().toPath();
    Path data = root.resolve("data");
    Files.createDirectories(data.resolve("jacoco"));
    Path important = data.resolve("jacoco/keep.txt");
    Files.writeString(important, "keep");
    assertThrows(
        IOException.class,
        () -> new JacocoPreparer().prepare(data, List.of(root), List.of(root.resolve("missing"))));
    assertEquals("keep", Files.readString(important));
  }

  private static String execute(Path data, String run, boolean input) throws Exception {
    var runtime = JacocoRuntime.class.getProtectionDomain().getCodeSource().getLocation();
    try (var loader =
        new URLClassLoader(
            new java.net.URL[] {data.resolve("jacoco/classes").toUri().toURL(), runtime},
            ClassLoader.getPlatformClassLoader())) {
      assertEquals(
          input ? 1 : 2,
          loader
              .loadClass("fixture.Sample")
              .getMethod("choose", boolean.class)
              .invoke(null, input));
      var original = System.out;
      var buffer = new ByteArrayOutputStream();
      try (var out = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
        System.setOut(out);
        loader
            .loadClass(JacocoRuntime.class.getName())
            .getMethod("flush", String.class)
            .invoke(null, run);
      } finally {
        System.setOut(original);
      }
      return buffer.toString(StandardCharsets.UTF_8);
    }
  }

  private static void writeExec(Path file, ExecutionData data) throws IOException {
    try (var out = Files.newOutputStream(file)) {
      new ExecutionDataWriter(out).visitClassExecution(data);
    }
  }

  private static String branches(Path output, String attribute) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    var document = factory.newDocumentBuilder().parse(output.toFile());
    return javax.xml.xpath.XPathFactory.newInstance()
        .newXPath()
        .evaluate("//method[@name='choose']/counter[@type='BRANCH']/@" + attribute, document);
  }
}
