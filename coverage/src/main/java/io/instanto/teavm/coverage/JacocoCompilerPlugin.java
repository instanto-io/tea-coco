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
import org.teavm.backend.javascript.TeaVMJavaScriptHost;
import org.teavm.backend.javascript.rendering.RenderingManager;
import org.teavm.dependency.*;
import org.teavm.model.*;
import org.teavm.model.instructions.*;
import org.teavm.vm.BuildTarget;
import org.teavm.vm.spi.*;
import tools.jackson.databind.ObjectMapper;

/** Stock runner collection plus read-only compiler evidence for prepared JaCoCo bytecode. */
final class JacocoCompilerPlugin {
  private static final MethodReference FLUSH =
      new MethodReference(JacocoRuntime.class, "flush", String.class, void.class);

  record Location(String file, int line) {}

  record Evidence(String run, Set<String> reachableMethods, Set<Location> emittedLocations) {}

  void install(TeaVMHost host) {
    var js = host.getExtension(TeaVMJavaScriptHost.class);
    if (js == null) return;
    String directory =
        System.getProperty(
            "teavm.coverage.dataDirectory",
            host.getProperties().getProperty("teavm.coverage.dataDirectory"));
    if (directory == null)
      throw new IllegalArgumentException("Missing teavm.coverage.dataDirectory");
    Path data = Path.of(directory).resolve("jacoco");
    ObjectMapper json = new ObjectMapper();
    JacocoPreparer.Manifest manifest;
    try {
      manifest =
          json.readValue(data.resolve("manifest.json").toFile(), JacocoPreparer.Manifest.class);
    } catch (RuntimeException e) {
      throw new IllegalStateException("Run tea-coco prepare before browser compilation", e);
    }
    if (manifest.version() != 1 || manifest.run() == null || manifest.classes() == null)
      throw new IllegalArgumentException("Invalid JaCoCo baseline");
    Map<String, JacocoPreparer.Entry> selected = new HashMap<>();
    manifest.classes().forEach(e -> selected.put(e.name().replace('/', '.'), e));
    Set<String> reachable = new TreeSet<>();
    host.add(
        new AbstractDependencyListener() {
          @Override
          public void started(DependencyAgent agent) {
            agent.linkMethod(FLUSH).use();
          }

          @Override
          public void classReached(DependencyAgent agent, String name) {
            var entry = selected.get(name);
            if (entry == null) return;
            var resource = host.getResourceProvider().getResource(entry.name() + ".class");
            if (resource == null) throw new IllegalStateException("Missing prepared class " + name);
            try (InputStream in = resource.open()) {
              if (!JacocoPreparer.digest(in.readAllBytes()).equals(entry.preparedDigest()))
                throw new IllegalStateException(
                    "Prepared class must precede the original on the test classpath: " + name);
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          }

          @Override
          public void methodReached(DependencyAgent agent, MethodDependency method) {
            var ref = method.getReference();
            if (selected.containsKey(ref.getClassName()) && !ref.getName().startsWith("$jacoco"))
              reachable.add(ref.getClassName().replace('.', '/') + "." + ref.getDescriptor());
          }
        });
    host.add(
        (cls, context) -> {
          if (!cls.getName().equals("org.teavm.junit.TestJsEntryPoint")) return;
          for (var method : cls.getMethods()) {
            if (!method.getName().equals("main") || method.getProgram() == null) continue;
            var program = method.getProgram();
            for (var block : program.getBasicBlocks()) {
              for (var instruction : block) {
                if (!(instruction instanceof ExitInstruction)
                    && !(instruction instanceof RaiseInstruction)) continue;
                StringConstantInstruction run = new StringConstantInstruction();
                run.setConstant(manifest.run());
                run.setReceiver(program.createVariable());
                InvokeInstruction call = new InvokeInstruction();
                call.setType(InvocationType.SPECIAL);
                call.setMethod(FLUSH);
                call.setArguments(run.getReceiver());
                instruction.insertPrevious(run);
                instruction.insertPrevious(call);
              }
            }
          }
        });
    js.add(
        new RendererListener() {
          @Override
          public void begin(RenderingManager manager, BuildTarget target) {
            Set<String> sources = new HashSet<>();
            manifest.classes().forEach(e -> sources.add(e.source()));
            Set<Location> emitted = new HashSet<>();
            var reader =
                new AbstractInstructionReader() {
                  @Override
                  public void location(TextLocation loc) {
                    if (loc == null) return;
                    if (sources.contains(loc.getFileName()) && loc.getLine() > 0)
                      emitted.add(new Location(loc.getFileName(), loc.getLine()));
                    for (var inline : loc.getInliningPath()) {
                      if (sources.contains(inline.getFileName()) && inline.getLine() > 0)
                        emitted.add(new Location(inline.getFileName(), inline.getLine()));
                    }
                  }
                };
            for (var name : manager.getClassSource().getClassNames()) {
              for (var method : manager.getClassSource().get(name).getMethods()) {
                if (method.getProgram() != null)
                  for (var block : method.getProgram().getBasicBlocks())
                    block.readAllInstructions(reader);
              }
            }
            try {
              Path evidence = data.resolve("evidence");
              Files.createDirectories(evidence);
              json.writeValue(
                  evidence.resolve(UUID.randomUUID() + ".json").toFile(),
                  new Evidence(manifest.run(), reachable, emitted));
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          }

          @Override
          public void complete() {}
        });
  }
}
