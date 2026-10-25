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

import io.instanto.teavm.coverage.CoverageData.Inventory;
import io.instanto.teavm.coverage.CoverageData.Line;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Merges immutable compiler baselines and stock JUnit stdout records into Sonar generic XML. */
public final class CoverageReporter {
  public static final String PREFIX = "__TEAVM_COVERAGE_V1__:";
  private final ObjectMapper json = new ObjectMapper();
  private final List<Path> roots;
  private final Map<String, Inventory> inventories = new HashMap<>();
  private final Map<String, Map<Integer, Boolean>> coverage = new TreeMap<>();
  private final Map<String, java.util.Set<Integer>> excluded = new TreeMap<>();
  private int snapshots;

  /** CLI for Gradle/other build tools: dataDirectory outputFile sourceRoots reportDirectory... */
  public static void main(String[] args) throws IOException {
    if (args.length < 4) {
      throw new IllegalArgumentException(
          "Usage: CoverageReporter dataDirectory outputFile sourceRoots reportDirectory...");
    }
    var sourceRoots =
        java.util.Arrays.stream(
                args[2].split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
            .map(Path::of)
            .toList();
    var reportDirectories = java.util.Arrays.stream(args).skip(3).map(Path::of).toList();
    new CoverageReporter(sourceRoots)
        .generate(Path.of(args[0]), reportDirectories, Path.of(args[1]));
  }

  public CoverageReporter(List<Path> roots) {
    this.roots = roots.stream().map(p -> p.toAbsolutePath().normalize()).distinct().toList();
  }

  public void generate(Path data, List<Path> junitReports, Path output) throws IOException {
    // A failed report attempt must not leave a previous run's green report available to a scanner.
    Files.deleteIfExists(output);
    Files.deleteIfExists(output.resolveSibling("not-emitted.json"));
    inventories.clear();
    coverage.clear();
    excluded.clear();
    snapshots = 0;
    if (!Files.isDirectory(data)) {
      throw new IOException("No compiler coverage baselines in " + data);
    }
    try (var files = Files.list(data)) {
      for (var path : files.filter(p -> p.toString().endsWith(".json")).toList()) {
        addInventory(json.readValue(path.toFile(), Inventory.class));
      }
    }
    for (var directory : junitReports) {
      if (!Files.isDirectory(directory)) {
        continue;
      }
      try (var files = Files.list(directory)) {
        for (var path :
            files
                .filter(
                    p ->
                        p.getFileName().toString().startsWith("TEST-")
                            && p.toString().endsWith(".xml"))
                .toList()) {
          readJUnit(path);
        }
      }
    }
    if (coverage.isEmpty() || snapshots == 0) {
      throw new IOException(
          "No browser coverage received. Enable the extension and preserve JUnit stdout reports.");
    }
    // A line emitted by any compilation is included in the combined build scope.
    coverage.forEach(
        (file, lines) -> {
          var omitted = excluded.get(file);
          if (omitted != null) {
            omitted.removeAll(lines.keySet());
          }
        });
    write(output);
    json.writerWithDefaultPrettyPrinter()
        .writeValue(output.resolveSibling("not-emitted.json").toFile(), excluded);
  }

  private void addInventory(Inventory inventory) throws IOException {
    if (inventory.version() != 1
        || inventory.id() == null
        || inventory.lines() == null
        || inventory.notEmitted() == null
        || inventories.putIfAbsent(inventory.id(), inventory) != null) {
      throw new IOException("Invalid or duplicate coverage baseline");
    }
    for (var line : inventory.lines()) {
      coverage
          .computeIfAbsent(resolve(line), key -> new TreeMap<>())
          .putIfAbsent(line.line(), false);
    }
    for (var line : inventory.notEmitted()) {
      excluded.computeIfAbsent(resolve(line), key -> new java.util.TreeSet<>()).add(line.line());
    }
  }

  private String resolve(Line line) throws IOException {
    if (line.file() == null || line.line() <= 0) {
      throw new IOException("Invalid source location in coverage baseline");
    }
    var relative = Path.of(line.file());
    if (relative.isAbsolute()
        || relative.startsWith("..")
        || !relative.normalize().equals(relative)) {
      throw new IOException("Invalid coverage source path: " + relative);
    }
    Path result = null;
    for (var root : roots) {
      var candidate = root.resolve(relative).normalize();
      if (candidate.startsWith(root) && Files.isRegularFile(candidate)) {
        if (result != null && !result.equals(candidate)) {
          throw new IOException("Ambiguous coverage source: " + relative);
        }
        result = candidate;
      }
    }
    if (result == null) {
      throw new IOException("Coverage source was not found in configured roots: " + relative);
    }
    return result.toString();
  }

  private void readJUnit(Path path) throws IOException {
    try {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      var document = factory.newDocumentBuilder().parse(path.toFile());
      var outputs = document.getElementsByTagName("system-out");
      for (int i = 0; i < outputs.getLength(); ++i) {
        for (var line : outputs.item(i).getTextContent().split("\\R")) {
          if (line.startsWith(PREFIX)) {
            merge(json.readTree(line.substring(PREFIX.length())));
          }
        }
      }
    } catch (javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException e) {
      throw new IOException("Invalid JUnit report: " + path, e);
    }
  }

  private void merge(JsonNode snapshot) throws IOException {
    if (snapshot == null || !snapshot.isObject()) {
      throw new IOException("Invalid browser coverage snapshot");
    }
    var fields = snapshot.properties().iterator();
    while (fields.hasNext()) {
      var entry = fields.next();
      var baseline = inventories.get(entry.getKey());
      if (baseline == null) {
        throw new IOException("Browser snapshot has no matching baseline: " + entry.getKey());
      }
      var hits = entry.getValue().path("hits");
      if (!hits.isArray() || hits.size() != baseline.lines().size()) {
        throw new IOException("Coverage probe count does not match the compiled build");
      }
      for (int i = 0; i < hits.size(); ++i) {
        var hit = hits.get(i);
        if (!hit.isIntegralNumber()
            || !hit.canConvertToInt()
            || (hit.intValue() != 0 && hit.intValue() != 1)) {
          throw new IOException("Invalid coverage hit value");
        }
        var line = baseline.lines().get(i);
        coverage.get(resolve(line)).merge(line.line(), hit.intValue() == 1, Boolean::logicalOr);
      }
      snapshots++;
    }
  }

  private void write(Path output) throws IOException {
    output = output.toAbsolutePath();
    Files.createDirectories(output.getParent());
    var temp = Files.createTempFile(output.getParent(), "coverage-", ".xml.tmp");
    try {
      try (var writer = Files.newBufferedWriter(temp)) {
        var xml = XMLOutputFactory.newFactory().createXMLStreamWriter(writer);
        xml.writeStartElement("coverage");
        xml.writeAttribute("version", "1");
        for (var file : coverage.entrySet()) {
          xml.writeStartElement("file");
          xml.writeAttribute("path", file.getKey());
          for (var line : file.getValue().entrySet()) {
            xml.writeEmptyElement("lineToCover");
            xml.writeAttribute("lineNumber", line.getKey().toString());
            xml.writeAttribute("covered", line.getValue().toString());
          }
          xml.writeEndElement();
        }
        xml.writeEndElement();
        xml.flush();
      }
      Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
    } catch (XMLStreamException e) {
      throw new IOException("Could not write coverage XML", e);
    } finally {
      Files.deleteIfExists(temp);
    }
  }
}
