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
package prototype;

import static org.junit.Assert.*;

import com.microsoft.playwright.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.jacoco.core.analysis.*;
import org.jacoco.core.data.*;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.tools.ExecFileLoader;
import org.jacoco.report.DirectorySourceFileLocator;
import org.jacoco.report.xml.XMLFormatter;
import org.junit.Test;
import org.objectweb.asm.Opcodes;
import org.teavm.tooling.*;
import org.teavm.vm.TeaVMOptimizationLevel;

/** Runs identical JaCoCo-instrumented bytecode on the JVM and through stock TeaVM. */
public class JaCoCoTeaVMTest {
  private static final Path OUTPUT = Path.of("target/proof");
  private static final String CLASS = "reference/Decisions.class";
  private final byte[] original;
  private final byte[] instrumented;

  public JaCoCoTeaVMTest() throws Exception {
    original = getClass().getClassLoader().getResourceAsStream(CLASS).readAllBytes();
    instrumented =
        new Instrumenter(
                (id, name, size, mv) -> {
                  // String transport preserves all 64 class-ID bits in JavaScript.
                  mv.visitLdcInsn(Long.toUnsignedString(id, 16));
                  mv.visitLdcInsn(name);
                  mv.visitLdcInsn(size);
                  mv.visitMethodInsn(
                      Opcodes.INVOKESTATIC,
                      "prototype/ProbeRuntime",
                      "get",
                      "(Ljava/lang/String;Ljava/lang/String;I)[Z",
                      false);
                  return 3;
                })
            .instrument(original, CLASS);
    Files.createDirectories(OUTPUT.resolve("input/reference"));
    Files.write(OUTPUT.resolve("input").resolve(CLASS), ProbeAccessAdapter.lower(instrumented));
  }

  @Test
  public void browserProbesMatchJvmAndMergeExactly() throws Exception {
    Files.createDirectories(OUTPUT);
    Map<String, ExecutionDataStore> jvm = new LinkedHashMap<>();
    for (String mode : List.of("A", "B", "UNION")) {
      ByteLoader loader = new ByteLoader(true);
      loader.loadClass("prototype.Inputs").getMethod("run", String.class).invoke(null, mode);
      String dump =
          (String) loader.loadClass("prototype.ProbeRuntime").getMethod("dump").invoke(null);
      jvm.put(mode, parse(dump));
      report("jvm-" + mode, jvm.get(mode));
      String agentDirectory = System.getProperty("prototype.agentData");
      assertNotNull(
          "Run scripts/check_jacoco_teavm.py to provide the genuine agent reference",
          agentDirectory);
      ExecFileLoader agent = new ExecFileLoader();
      agent.load(Path.of(agentDirectory, mode + ".exec").toFile());
      for (ExecutionData data : jvm.get(mode).getContents()) {
        ExecutionData agentData = agent.getExecutionDataStore().get(data.getId());
        assertNotNull("Original fixture class must match agent reference", agentData);
        assertArrayEquals(
            "Standard JaCoCo agent: " + mode, agentData.getProbes(), data.getProbes());
      }
    }
    assertEquals(1, method(jvm.get("A"), "simple").getBranchCounter().getCoveredCount());
    assertEquals(1, method(jvm.get("B"), "simple").getBranchCounter().getCoveredCount());
    assertEquals(2, method(jvm.get("UNION"), "simple").getBranchCounter().getCoveredCount());
    assertEquals(3, method(jvm.get("UNION"), "stringSwitch").getBranchCounter().getTotalCount());
    assertEquals(2, method(jvm.get("UNION"), "unreachable").getBranchCounter().getMissedCount());
    try (Playwright playwright = Playwright.create();
        Browser browser =
            playwright
                .chromium()
                .launch(new BrowserType.LaunchOptions().setChannel("chrome").setHeadless(true))) {
      for (TeaVMOptimizationLevel level : TeaVMOptimizationLevel.values()) {
        Path folder = OUTPUT.resolve(level.name());
        TeaVMTool tool = compile(folder, level, true);
        writeReachability(folder, tool);
        for (String mode : List.of("A", "B", "UNION")) {
          try (Page page = browser.newPage()) {
            page.addScriptTag(new Page.AddScriptTagOptions().setPath(folder.resolve("app.js")));
            page.evaluate("mode => { globalThis.testMode = mode; main(); }", mode);
            page.waitForFunction("typeof globalThis.probeDump === 'string'");
            ExecutionDataStore browserData = parse((String) page.evaluate("globalThis.probeDump"));
            assertProbesEqual(jvm.get(mode), browserData);
            report(level + "-browser-" + mode, browserData);
            if (mode.equals("B")) {
              ExecutionDataStore merged = parse((String) page.evaluate("globalThis.probeDump"));
              for (ExecutionData data : jvm.get("A").getContents()) merged.put(data);
              assertProbesEqual(jvm.get("UNION"), merged);
              report(level + "-merged-jvm-A-browser-B", merged);
            }
          }
        }
        // Reachability must be checked independently of instrumentation effects.
        TeaVMTool plain = compile(OUTPUT.resolve(level + "-uninstrumented"), level, false);
        assertEquals(reachable(plain), reachable(tool));
        writeReachability(OUTPUT.resolve(level + "-uninstrumented"), plain);
        int omittedLine = method(new ExecutionDataStore(), "unreachable").getFirstLine();
        String emitted = Files.readString(OUTPUT.resolve(level + "-uninstrumented/emission.tsv"));
        assertFalse(emitted.contains("reference/Decisions.java\t" + omittedLine + "\t"));
        assertTrue(
            "Read-only renderer listener must capture actual locations",
            emitted.contains("reference/Decisions.java\t"));
        assertFalse(reachable(plain).stream().anyMatch(m -> m.startsWith("unreachable(")));
      }
    }
  }

  private TeaVMTool compile(Path folder, TeaVMOptimizationLevel level, boolean probes)
      throws Exception {
    Files.createDirectories(folder);
    TeaVMTool tool = new TeaVMTool();
    tool.setClassLoader(new ByteLoader(probes));
    tool.setMainClass("prototype.BrowserEntry");
    tool.setTargetType(TeaVMTargetType.JAVASCRIPT);
    tool.setTargetDirectory(folder.toFile());
    tool.setTargetFileName("app.js");
    tool.getProperties()
        .setProperty(
            "prototype.emissionReport", folder.resolve("emission.tsv").toAbsolutePath().toString());
    tool.setOptimizationLevel(level);
    tool.setObfuscated(level == TeaVMOptimizationLevel.FULL);
    tool.generate();
    assertTrue(
        tool.getProblemProvider().getSevereProblems().stream()
            .map(p -> p.getText() + Arrays.toString(p.getParams()))
            .toList()
            .toString(),
        tool.getProblemProvider().getSevereProblems().isEmpty());
    return tool;
  }

  private Set<String> reachable(TeaVMTool tool) {
    Set<String> result = new TreeSet<>();
    for (var m : tool.getDependencyInfo().getReachableMethods()) {
      if (m.getClassName().equals("reference.Decisions") && !m.getName().startsWith("$jacoco")) {
        result.add(m.getDescriptor().toString());
      }
    }
    return result;
  }

  private void writeReachability(Path folder, TeaVMTool tool) throws Exception {
    Set<String> reachable = reachable(tool);
    StringBuilder diagnostics = new StringBuilder("method\tstatus\tsource\tline\tbranches\n");
    for (IMethodCoverage m : analyze(new ExecutionDataStore()).getMethods()) {
      boolean retained = reachable.contains(m.getName() + m.getDesc());
      for (int line = m.getFirstLine(); line <= m.getLastLine(); line++) {
        if (m.getLine(line).getInstructionCounter().getTotalCount() > 0) {
          diagnostics
              .append(m.getName())
              .append(m.getDesc())
              .append('\t')
              .append(retained ? "reachable" : "unreachable-by-dependency-analysis")
              .append("\treference/Decisions.java\t")
              .append(line)
              .append('\t')
              .append(m.getLine(line).getBranchCounter().getTotalCount())
              .append('\n');
        }
      }
    }
    Files.writeString(folder.resolve("reachability.tsv"), diagnostics);
  }

  private ExecutionDataStore parse(String dump) {
    ExecutionDataStore store = new ExecutionDataStore();
    for (String row : dump.split("\\n")) {
      String[] fields = row.split(",", -1);
      assertEquals(3, fields.length);
      boolean[] probes = new boolean[fields[2].length()];
      for (int i = 0; i < probes.length; i++) {
        assertTrue(fields[2].charAt(i) == '0' || fields[2].charAt(i) == '1');
        probes[i] = fields[2].charAt(i) == '1';
      }
      store.put(new ExecutionData(Long.parseUnsignedLong(fields[0], 16), fields[1], probes));
    }
    return store;
  }

  private void assertProbesEqual(ExecutionDataStore expected, ExecutionDataStore actual) {
    assertEquals(expected.getContents().size(), actual.getContents().size());
    for (ExecutionData data : expected.getContents()) {
      assertNotNull(actual.get(data.getId()));
      assertEquals(data.getName(), actual.get(data.getId()).getName());
      assertArrayEquals(data.getProbes(), actual.get(data.getId()).getProbes());
    }
  }

  private IClassCoverage analyze(ExecutionDataStore data) throws Exception {
    CoverageBuilder coverage = new CoverageBuilder();
    new Analyzer(data, coverage).analyzeClass(original, CLASS);
    return coverage.getClasses().iterator().next();
  }

  private IMethodCoverage method(ExecutionDataStore data, String name) throws Exception {
    return analyze(data).getMethods().stream()
        .filter(m -> m.getName().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private void report(String name, ExecutionDataStore data) throws Exception {
    try (OutputStream out = Files.newOutputStream(OUTPUT.resolve(name + ".exec"))) {
      ExecutionDataWriter writer = new ExecutionDataWriter(out);
      data.accept(writer);
    }
    ExecFileLoader readback = new ExecFileLoader();
    readback.load(OUTPUT.resolve(name + ".exec").toFile());
    assertProbesEqual(data, readback.getExecutionDataStore());
    CoverageBuilder coverage = new CoverageBuilder();
    new Analyzer(readback.getExecutionDataStore(), coverage).analyzeClass(original, CLASS);
    try (OutputStream out = Files.newOutputStream(OUTPUT.resolve(name + ".xml"))) {
      var visitor = new XMLFormatter().createVisitor(out);
      visitor.visitInfo(List.of(), data.getContents());
      visitor.visitBundle(
          coverage.getBundle(name),
          new DirectorySourceFileLocator(
              Path.of("../jacoco-branch-reference/src/main/java").toFile(), "UTF-8", 2));
      visitor.visitEnd();
    }
  }

  private class ByteLoader extends ClassLoader {
    private final boolean probes;

    ByteLoader(boolean probes) {
      super(JaCoCoTeaVMTest.class.getClassLoader());
      this.probes = probes;
    }

    @Override
    public Enumeration<java.net.URL> getResources(String name) throws IOException {
      if (probes && name.equals(CLASS))
        return Collections.enumeration(
            List.of(OUTPUT.resolve("input").resolve(CLASS).toUri().toURL()));
      return super.getResources(name);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
      if (name.equals(CLASS)) return new ByteArrayInputStream(probes ? instrumented : original);
      return super.getResourceAsStream(name);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (!name.equals("reference.Decisions")
          && !name.equals("prototype.Inputs")
          && !name.equals("prototype.ProbeRuntime")) return super.loadClass(name, resolve);
      synchronized (getClassLoadingLock(name)) {
        Class<?> found = findLoadedClass(name);
        if (found == null) {
          try (InputStream input = getResourceAsStream(name.replace('.', '/') + ".class")) {
            byte[] bytes = input.readAllBytes();
            found = defineClass(name, bytes, 0, bytes.length);
          } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
          }
        }
        if (resolve) resolveClass(found);
        return found;
      }
    }
  }
}
