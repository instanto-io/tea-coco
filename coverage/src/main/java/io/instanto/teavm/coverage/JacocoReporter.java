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

import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.jacoco.core.analysis.*;
import org.jacoco.core.data.*;
import org.jacoco.core.tools.ExecFileLoader;
import org.jacoco.report.*;
import org.jacoco.report.xml.XMLFormatter;
import tools.jackson.databind.ObjectMapper;

/** Identity-aware JVM/browser union using JaCoCo's original-bytecode analysis. */
public final class JacocoReporter {
  public static final String PREFIX = "__TEA_COCO_JACOCO_V1__:";
  private final Map<String, JacocoPreparer.Entry> entries = new HashMap<>();
  private ExecutionDataStore current;
  private String run;
  private int snapshots;

  public static void main(String[] args) throws IOException {
    if (args.length < 4)
      throw new IllegalArgumentException(
          "Usage: JacocoReporter dataDirectory outputFile sourceRoots [--exec=coverage.exec]"
              + " reportDirectory...");
    List<Path> reports = new ArrayList<>(), jvm = new ArrayList<>();
    for (String arg : Arrays.copyOfRange(args, 3, args.length)) {
      if (arg.startsWith("--exec=")) jvm.add(Path.of(arg.substring(7)));
      else reports.add(Path.of(arg));
    }
    new JacocoReporter()
        .generate(Path.of(args[0]), reports, Path.of(args[1]), JacocoPreparer.paths(args[2]), jvm);
  }

  public void generate(
      Path directory, List<Path> reports, Path output, List<Path> roots, List<Path> jvm)
      throws IOException {
    Files.deleteIfExists(output);
    Path execution = output.resolveSibling(output.getFileName() + ".exec");
    Path diagnostics = output.resolveSibling("compiler-evidence.json");
    Files.deleteIfExists(execution);
    Files.deleteIfExists(diagnostics);
    entries.clear();
    current = new ExecutionDataStore();
    snapshots = 0;
    Path baseline = directory.resolve("jacoco");
    ObjectMapper json = new ObjectMapper();
    var manifest =
        json.readValue(baseline.resolve("manifest.json").toFile(), JacocoPreparer.Manifest.class);
    if (manifest.version() != 1
        || manifest.run() == null
        || manifest.classes() == null
        || manifest.classes().isEmpty()) throw new IOException("Invalid JaCoCo baseline");
    run = manifest.run();
    for (var entry : manifest.classes()) {
      if (entry.name() == null
          || entry.id() == null
          || entry.probes() < 0
          || entries.putIfAbsent(entry.name(), entry) != null)
        throw new IOException("Invalid or duplicate class baseline");
      Path classPath = Path.of(entry.name() + ".class");
      if (classPath.isAbsolute()
          || classPath.startsWith("..")
          || !classPath.normalize().equals(classPath)) throw new IOException("Invalid class name");
      byte[] bytes = Files.readAllBytes(baseline.resolve("originals").resolve(classPath));
      if (!JacocoPreparer.digest(bytes).equals(entry.digest()))
        throw new IOException("Original class changed: " + entry.name());
      current.put(new ExecutionData(id(entry.id()), entry.name(), entry.probes()));
    }
    for (Path reportDir : reports) {
      if (!Files.isDirectory(reportDir)) continue;
      try (var files = Files.list(reportDir)) {
        for (Path file :
            files
                .filter(
                    p ->
                        p.getFileName().toString().startsWith("TEST-")
                            && p.toString().endsWith(".xml"))
                .toList()) readJUnit(file);
      }
    }
    if (snapshots == 0)
      throw new IOException(
          "No JaCoCo browser snapshots received; preserve test stdout and enable jacoco mode");
    for (Path file : jvm) {
      ExecFileLoader loader = new ExecFileLoader();
      loader.load(file.toFile());
      for (var hit : loader.getExecutionDataStore().getContents()) {
        var entry = entries.get(hit.getName());
        if (entry == null) continue;
        if (id(entry.id()) != hit.getId() || entry.probes() != hit.getProbes().length)
          throw new IOException("Additional execution data class mismatch: " + hit.getName());
        current.put(hit);
      }
    }
    CoverageBuilder coverage = new CoverageBuilder();
    Analyzer analyzer = new Analyzer(current, coverage);
    for (var entry : entries.values())
      analyzer.analyzeAll(baseline.resolve("originals").resolve(entry.name() + ".class").toFile());
    if (!coverage.getNoMatchClasses().isEmpty())
      throw new IOException("Execution data does not match original class files");
    for (var cls : coverage.getClasses()) {
      if (cls.getId() != id(entries.get(cls.getName()).id()))
        throw new IOException("Baseline class identity mismatch: " + cls.getName());
    }
    Files.createDirectories(output.toAbsolutePath().getParent());
    Path temp =
        Files.createTempFile(output.toAbsolutePath().getParent(), "teacoco-jacoco-", ".xml");
    try {
      try (OutputStream stream = Files.newOutputStream(temp)) {
        var visitor = new XMLFormatter().createVisitor(stream);
        visitor.visitInfo(List.of(), current.getContents());
        MultiSourceFileLocator sources = new MultiSourceFileLocator(2);
        for (Path root : roots)
          sources.add(new DirectorySourceFileLocator(root.toFile(), "UTF-8", 2));
        visitor.visitBundle(coverage.getBundle("TeaCoCo JVM and browser"), sources);
        visitor.visitEnd();
      }
      writeEvidence(baseline, diagnostics, coverage, json);
      try (OutputStream stream = Files.newOutputStream(execution)) {
        current.accept(new ExecutionDataWriter(stream));
      }
      Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  private static long id(String hex) throws IOException {
    try {
      return Long.parseUnsignedLong(hex, 16);
    } catch (NumberFormatException e) {
      throw new IOException("Invalid class identifier", e);
    }
  }

  private void readJUnit(Path file) throws IOException {
    try {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      var nodes =
          factory.newDocumentBuilder().parse(file.toFile()).getElementsByTagName("system-out");
      for (int i = 0; i < nodes.getLength(); i++)
        for (String line : nodes.item(i).getTextContent().split("\\R")) {
          if (line.startsWith(PREFIX)) merge(line.substring(PREFIX.length()));
        }
    } catch (javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException e) {
      throw new IOException("Invalid JUnit report " + file, e);
    }
  }

  private void merge(String snapshot) throws IOException {
    int separator = snapshot.indexOf('|');
    if (separator < 0 || !snapshot.substring(0, separator).equals(run))
      throw new IOException("Stale or incompatible browser run ID");
    String records = snapshot.substring(separator + 1);
    if (!records.isEmpty())
      for (String row : records.split(";")) {
        String[] fields = row.split(",", -1);
        if (fields.length != 3) throw new IOException("Malformed browser probe record");
        var entry = entries.get(fields[1]);
        if (entry == null || !entry.id().equals(fields[0]) || entry.probes() != fields[2].length())
          throw new IOException("Browser class/probe mismatch: " + fields[1]);
        boolean[] probes = new boolean[entry.probes()];
        for (int i = 0; i < probes.length; i++) {
          char bit = fields[2].charAt(i);
          if (bit != '0' && bit != '1') throw new IOException("Invalid probe bit");
          probes[i] = bit == '1';
        }
        current.put(new ExecutionData(id(entry.id()), entry.name(), probes));
      }
    snapshots++;
  }

  private void writeEvidence(
      Path baseline, Path output, CoverageBuilder coverage, ObjectMapper json) throws IOException {
    Set<String> reachable = new TreeSet<>();
    Set<JacocoCompilerPlugin.Location> locations =
        new TreeSet<>(
            Comparator.comparing(JacocoCompilerPlugin.Location::file)
                .thenComparingInt(JacocoCompilerPlugin.Location::line));
    Path evidence = baseline.resolve("evidence");
    if (!Files.isDirectory(evidence)) throw new IOException("Missing compiler evidence");
    try (var files = Files.list(evidence)) {
      var evidenceFiles = files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
      if (evidenceFiles.isEmpty()) throw new IOException("Missing compiler evidence");
      for (var file : evidenceFiles) {
        var item = json.readValue(file.toFile(), JacocoCompilerPlugin.Evidence.class);
        if (!run.equals(item.run())
            || item.reachableMethods() == null
            || item.emittedLocations() == null)
          throw new IOException("Stale or malformed compiler evidence");
        reachable.addAll(item.reachableMethods());
        locations.addAll(item.emittedLocations());
      }
    }
    List<Map<String, Object>> methods = new ArrayList<>();
    for (var cls : coverage.getClasses())
      for (var method : cls.getMethods()) {
        if (method.getInstructionCounter().getTotalCount() == 0) continue;
        String key = cls.getName() + "." + method.getName() + method.getDesc();
        methods.add(
            Map.of(
                "method",
                key,
                "source",
                entries.get(cls.getName()).source(),
                "firstLine",
                method.getFirstLine(),
                "lastLine",
                method.getLastLine(),
                "reachable",
                reachable.contains(key),
                "branches",
                method.getBranchCounter().getTotalCount()));
      }
    methods.sort(Comparator.comparing(m -> (String) m.get("method")));
    json.writerWithDefaultPrettyPrinter()
        .writeValue(
            output.toFile(),
            Map.of(
                "run",
                run,
                "scope",
                "instrumented-build",
                "methods",
                methods,
                "emittedLocations",
                locations,
                "note",
                "Missing locations do not prove branch elimination; original-bytecode report"
                    + " denominators are unchanged."));
  }
}
