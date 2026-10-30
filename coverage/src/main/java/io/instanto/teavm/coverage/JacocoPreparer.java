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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.JarFile;
import org.jacoco.core.instr.Instrumenter;
import org.objectweb.asm.*;
import tools.jackson.databind.ObjectMapper;

/** Creates a test-classpath overlay and preserves the exact original reporting baseline. */
public final class JacocoPreparer {
  /** Original JaCoCo identity and checksums of both original and test-only class bytes. */
  public record Entry(
      String name, String id, int probes, String digest, String source, String preparedDigest) {}

  /** Fresh preparation identity; browser evidence from a different run must never be merged. */
  @SuppressFBWarnings("EI_EXPOSE_REP")
  public record Manifest(int version, String run, List<Entry> classes) {}

  private static final String TEACOCO_OWNED = ".teacoco-owned";
  private final ObjectMapper json = new ObjectMapper();
  private final Map<String, byte[]> inputs = new TreeMap<>();
  private final List<Entry> entries = new ArrayList<>();
  private List<Path> roots = List.of();

  public static void main(String[] args) throws IOException {
    if (args.length < 3)
      throw new IllegalArgumentException(
          "Usage: JacocoPreparer dataDirectory sourceRoots classRoot...");
    new JacocoPreparer()
        .prepare(
            Path.of(args[0]), paths(args[1]), Arrays.stream(args).skip(2).map(Path::of).toList());
  }

  public static List<Path> paths(String value) {
    return Arrays.stream(value.split(java.util.regex.Pattern.quote(File.pathSeparator)))
        .filter(s -> !s.isBlank())
        .map(Path::of)
        .toList();
  }

  public Manifest prepare(Path data, List<Path> sourceRoots, List<Path> classRoots)
      throws IOException {
    Path output = data.toAbsolutePath().normalize().resolve("jacoco");
    if (Files.exists(output)) {
      if (!Files.isRegularFile(output.resolve(TEACOCO_OWNED))) {
        throw new IOException("Refusing to replace unowned output directory " + output);
      }
      // Even a failed preparation invalidates the previous run. Gradle finalizers or a manual
      // Maven report must not turn an old successful test execution into a fresh report.
      Files.deleteIfExists(output.resolve("manifest.json"));
    }
    roots = sourceRoots.stream().map(p -> p.toAbsolutePath().normalize()).distinct().toList();
    if (roots.isEmpty() || classRoots.isEmpty())
      throw new IOException("Source roots and class roots are required");
    inputs.clear();
    entries.clear();
    for (Path input : classRoots) {
      input = input.toAbsolutePath().normalize();
      if (output.startsWith(input) || input.startsWith(output))
        throw new IOException("Coverage output overlaps a class root: " + input);
      if (!Files.exists(input)) continue; // Test-only Maven modules have no main output directory.
      if (Files.isDirectory(input)) {
        try (var files = Files.walk(input)) {
          for (Path file : files.filter(Files::isRegularFile).toList())
            add(
                input.relativize(file).toString().replace(File.separatorChar, '/'),
                Files.readAllBytes(file));
        }
      } else {
        try (JarFile jar = new JarFile(input.toFile())) {
          for (var entry :
              jar.stream()
                  .filter(e -> !e.isDirectory() && e.getName().endsWith(".class"))
                  .toList()) {
            if (entry.getName().startsWith("META-INF/versions/"))
              throw new IOException(
                  "Multi-release class roots require an explicitly selected release: " + input);
            try (var in = jar.getInputStream(entry)) {
              add(entry.getName(), in.readAllBytes());
            }
          }
        }
      }
    }
    if (Files.exists(output)) {
      if (!Files.isRegularFile(output.resolve(TEACOCO_OWNED)))
        throw new IOException("Refusing to replace unowned output directory " + output);
      try (var files = Files.walk(output)) {
        for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
      }
    }
    Files.createDirectories(output);
    Files.writeString(output.resolve(TEACOCO_OWNED), "teacoco JaCoCo preparation\n");
    String run = UUID.randomUUID().toString();
    for (var input : inputs.entrySet()) {
      String name = input.getKey();
      byte[] bytes = input.getValue();
      byte[] prepared = bytes;
      if (name.endsWith(".class") && !name.equals("module-info.class")) {
        ClassReader reader = new ClassReader(bytes);
        String source = source(reader);
        if (source != null && !reader.getClassName().startsWith("io/instanto/teavm/coverage/")) {
          List<Entry> recorded = new ArrayList<>();
          prepared =
              new Instrumenter(
                      (id, className, count, mv) -> {
                        String hex = Long.toUnsignedString(id, 16);
                        recorded.add(new Entry(className, hex, count, digest(bytes), source, ""));
                        mv.visitLdcInsn(run);
                        mv.visitLdcInsn(hex);
                        mv.visitLdcInsn(className);
                        mv.visitLdcInsn(count);
                        mv.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            "io/instanto/teavm/coverage/JacocoRuntime",
                            "get",
                            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)[Z",
                            false);
                        return 4;
                      })
                  .instrument(bytes, name);
          // Classes without executable methods have no probe accessor and need no execution record.
          if (!recorded.isEmpty()) {
            Entry first = recorded.get(0);
            if (recorded.stream().anyMatch(e -> !e.equals(first)))
              throw new IOException("Inconsistent instrumentation: " + name);
            write(output.resolve("originals"), name, bytes);
            prepared = ProbeAccessAdapter.lower(prepared);
            entries.add(
                new Entry(
                    first.name(),
                    first.id(),
                    first.probes(),
                    first.digest(),
                    first.source(),
                    digest(prepared)));
          }
        }
      }
      write(output.resolve("classes"), name, prepared);
    }
    if (entries.isEmpty())
      throw new IOException("No executable classes matched the configured source roots");
    Manifest manifest = new Manifest(1, run, List.copyOf(entries));
    json.writerWithDefaultPrettyPrinter()
        .writeValue(output.resolve("manifest.json").toFile(), manifest);
    return manifest;
  }

  private void add(String name, byte[] bytes) throws IOException {
    Path path = Path.of(name);
    if (path.isAbsolute() || path.startsWith("..") || !path.normalize().equals(path))
      throw new IOException("Invalid classpath entry " + name);
    byte[] old = inputs.putIfAbsent(name, bytes);
    if (old != null && !Arrays.equals(old, bytes))
      throw new IOException("Conflicting classpath entries: " + name);
  }

  @SuppressFBWarnings("SIC_INNER_SHOULD_BE_STATIC_ANON")
  private String source(ClassReader reader) throws IOException {
    String[] file = new String[1];
    reader.accept(
        new ClassVisitor(Opcodes.ASM9) {
          @Override
          public void visitSource(String source, String debug) {
            file[0] = source;
          }
        },
        ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
    if (file[0] == null) return null;
    int end = reader.getClassName().lastIndexOf('/');
    String relative = reader.getClassName().substring(0, end + 1) + file[0];
    Path name = Path.of(relative);
    if (name.isAbsolute() || name.startsWith("..") || !name.normalize().equals(name))
      throw new IOException("Invalid source location " + relative);
    var matches = roots.stream().filter(root -> Files.isRegularFile(root.resolve(name))).toList();
    if (matches.size() > 1) throw new IOException("Ambiguous source: " + relative);
    return matches.isEmpty() ? null : relative;
  }

  private static void write(Path directory, String name, byte[] bytes) throws IOException {
    Path file = directory.resolve(name);
    Path parent = file.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.write(file, bytes);
  }

  static String digest(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
