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
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.teavm.backend.javascript.TeaVMJavaScriptHost;
import org.teavm.backend.javascript.rendering.RenderingManager;
import org.teavm.dependency.AbstractDependencyListener;
import org.teavm.dependency.DependencyAgent;
import org.teavm.model.ClassReader;
import org.teavm.model.MethodReference;
import org.teavm.model.Program;
import org.teavm.model.TextLocation;
import org.teavm.model.instructions.AbstractInstructionReader;
import org.teavm.model.instructions.ExitInstruction;
import org.teavm.model.instructions.IntegerConstantInstruction;
import org.teavm.model.instructions.InvocationType;
import org.teavm.model.instructions.InvokeInstruction;
import org.teavm.model.instructions.RaiseInstruction;
import org.teavm.vm.BuildTarget;
import org.teavm.vm.spi.RendererListener;
import org.teavm.vm.spi.TeaVMHost;
import org.teavm.vm.spi.TeaVMPlugin;
import tools.jackson.databind.ObjectMapper;

/** Public TeaVM plugin SPI only: no compiler replacement, reflection or patched runner. */
public final class CoveragePlugin implements TeaVMPlugin {
  private static final MethodReference HIT =
      new MethodReference(CoverageRuntime.class, "hit", int.class, void.class);
  private static final MethodReference FLUSH =
      new MethodReference(CoverageRuntime.class, "flush", void.class);
  private final ObjectMapper json = new ObjectMapper();
  private final List<Line> emitted = new ArrayList<>();
  private final Set<Line> candidates = new HashSet<>();
  private List<Path> roots;
  private Path dataDirectory;
  private final String id = UUID.randomUUID().toString();

  @Override
  public void install(TeaVMHost host) {
    if (!Boolean.parseBoolean(
        System.getProperty(
            "teavm.coverage.enabled",
            host.getProperties().getProperty("teavm.coverage.enabled", "false")))) {
      return;
    }
    String mode =
        System.getProperty(
            "teavm.coverage.mode",
            host.getProperties().getProperty("teavm.coverage.mode", "lines"));
    if (mode.equals("jacoco")) {
      new JacocoCompilerPlugin().install(host);
      return;
    }
    if (!mode.equals("lines")) throw new IllegalArgumentException("Unknown tea-coco mode: " + mode);
    var javascript = host.getExtension(TeaVMJavaScriptHost.class);
    if (javascript == null) {
      return;
    }
    var sourceRoots = property(host, "teavm.coverage.sourceRoots");
    roots =
        Pattern.compile(Pattern.quote(File.pathSeparator))
            .splitAsStream(sourceRoots)
            .filter(s -> !s.isBlank())
            .map(s -> Path.of(s).toAbsolutePath().normalize())
            .toList();
    if (roots.isEmpty()) {
      throw new IllegalArgumentException("Coverage source roots must not be empty");
    }
    dataDirectory = Path.of(property(host, "teavm.coverage.dataDirectory"));
    host.add(
        new AbstractDependencyListener() {
          @Override
          public void started(DependencyAgent agent) {
            agent.linkMethod(HIT).use();
          }

          @Override
          public void classReached(DependencyAgent agent, String className) {
            examine(agent.getClassSource().get(className));
          }
        });
    host.add(
        (cls, context) -> {
          // Stock TeaVM 0.16 JUnit entry point already catches Throwable. Add a flush at both
          // exits;
          // do this before dependency analysis so all runtime dependencies are linked normally.
          if (!cls.getName().equals("org.teavm.junit.TestJsEntryPoint")) {
            return;
          }
          for (var method : cls.getMethods()) {
            if (!method.getName().equals("main") || method.getProgram() == null) {
              continue;
            }
            for (var block : method.getProgram().getBasicBlocks()) {
              for (var instruction : block) {
                if (instruction instanceof ExitInstruction
                    || instruction instanceof RaiseInstruction) {
                  instruction.insertPrevious(call(FLUSH));
                }
              }
            }
          }
        });
    javascript.add(
        HIT,
        (context, method) -> {
          var writer = context.getWriter();
          writer.append("globalThis.__teavmCoverageV1[\"").append(id).append("\"].hits[");
          context.writeExpr(context.getArgument(0));
          writer.append("]=1");
        });
    javascript.add(
        new RendererListener() {
          @Override
          public void begin(RenderingManager manager, BuildTarget target) {
            for (var name : manager.getClassSource().getClassNames()) {
              var cls = manager.getClassSource().get(name);
              if (name.startsWith("io.instanto.teavm.coverage.")) {
                continue;
              }
              for (var method : cls.getMethods()) {
                if (method.getProgram() == null) {
                  continue;
                }
                if (!(method.getProgram() instanceof Program program)) {
                  throw new IllegalStateException(
                      "Coverage requires TeaVM's mutable post-optimization IR");
                }
                instrument(program);
              }
            }
            writeInventory();
            var writer = manager.getWriter();
            writer.append("globalThis.__teavmCoverageV1 ??= Object.create(null);").softNewLine();
            writer
                .append("globalThis.__teavmCoverageV1[\"")
                .append(id)
                .append("\"] = {hits:new Array(")
                .append(emitted.size())
                .append(").fill(0)};")
                .softNewLine();
          }

          @Override
          public void complete() {}
        });
  }

  private String property(TeaVMHost host, String key) {
    var value = System.getProperty(key, host.getProperties().getProperty(key));
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Missing coverage property: " + key);
    }
    return value;
  }

  private boolean accepts(TextLocation location) {
    if (location == null || location.getFileName() == null || location.getLine() < 1) {
      return false;
    }
    var source = Path.of(location.getFileName());
    if (source.isAbsolute() || source.startsWith("..") || !source.normalize().equals(source)) {
      return false;
    }
    return roots.stream().anyMatch(root -> Files.isRegularFile(root.resolve(source)));
  }

  private void examine(ClassReader cls) {
    if (cls == null) {
      return;
    }
    var reader =
        new AbstractInstructionReader() {
          @Override
          public void location(TextLocation location) {
            if (accepts(location)) {
              candidates.add(new Line(location.getFileName(), location.getLine()));
            }
          }
        };
    for (var method : cls.getMethods()) {
      if (method.getProgram() != null) {
        method.getProgram().getBasicBlocks().forEach(block -> block.readAllInstructions(reader));
      }
    }
  }

  private void instrument(Program program) {
    for (var block : program.getBasicBlocks()) {
      TextLocation previous = null;
      for (var instruction = block.getFirstInstruction(); instruction != null; ) {
        var next = instruction.getNext();
        var location = instruction.getLocation();
        if (accepts(location) && !location.equals(previous)) {
          int probe = emitted.size();
          emitted.add(new Line(location.getFileName(), location.getLine()));
          var constant = new IntegerConstantInstruction();
          constant.setConstant(probe);
          constant.setReceiver(program.createVariable());
          var hit = call(HIT);
          hit.setArguments(constant.getReceiver());
          instruction.insertPrevious(constant);
          instruction.insertPrevious(hit);
        }
        previous = location;
        instruction = next;
      }
    }
  }

  private static InvokeInstruction call(MethodReference method) {
    var call = new InvokeInstruction();
    call.setType(InvocationType.SPECIAL);
    call.setMethod(method);
    return call;
  }

  private void writeInventory() {
    var notEmitted = new HashSet<>(candidates);
    notEmitted.removeAll(emitted);
    var ordered =
        notEmitted.stream()
            .sorted(java.util.Comparator.comparing(Line::file).thenComparingInt(Line::line))
            .toList();
    try {
      Files.createDirectories(dataDirectory);
      json.writeValue(
          dataDirectory.resolve(id + ".json").toFile(), new Inventory(1, id, emitted, ordered));
    } catch (IOException e) {
      throw new UncheckedIOException("Could not persist coverage baseline", e);
    }
  }
}
