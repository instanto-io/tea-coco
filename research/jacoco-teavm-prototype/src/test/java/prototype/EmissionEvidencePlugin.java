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

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.teavm.backend.javascript.TeaVMJavaScriptHost;
import org.teavm.backend.javascript.rendering.*;
import org.teavm.model.TextLocation;
import org.teavm.model.instructions.AbstractInstructionReader;
import org.teavm.vm.BuildTarget;
import org.teavm.vm.spi.*;

/** Read-only compiler evidence; absence of a location is not proof that a branch was eliminated. */
public final class EmissionEvidencePlugin implements TeaVMPlugin {
  @Override
  public void install(TeaVMHost host) {
    String output = host.getProperties().getProperty("prototype.emissionReport");
    if (output == null) return;
    TeaVMJavaScriptHost js = host.getExtension(TeaVMJavaScriptHost.class);
    if (js == null) return;
    js.add(
        new RendererListener() {
          @Override
          public void begin(RenderingManager manager, BuildTarget target) {
            Set<String> evidence = new TreeSet<>();
            var reader =
                new AbstractInstructionReader() {
                  @Override
                  public void location(TextLocation location) {
                    if (location == null) return;
                    record(location.getFileName(), location.getLine(), "instruction");
                    for (var inline : location.getInliningPath())
                      record(inline.getFileName(), inline.getLine(), "inline-callsite");
                  }

                  private void record(String file, int line, String kind) {
                    if ("reference/Decisions.java".equals(file) && line > 0)
                      evidence.add(file + "\t" + line + "\t" + kind);
                  }
                };
            for (String name : manager.getClassSource().getClassNames()) {
              for (var method : manager.getClassSource().get(name).getMethods()) {
                if (method.getProgram() != null)
                  for (var block : method.getProgram().getBasicBlocks())
                    block.readAllInstructions(reader);
              }
            }
            try {
              Files.writeString(
                  Path.of(output), "source\tline\tevidence\n" + String.join("\n", evidence) + "\n");
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          }

          @Override
          public void complete() {}
        });
  }
}
