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
package example;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.Test;
import org.junit.runner.JUnitCore;

public class FailureCollectionIT {
  @Test
  public void reportsCoverageAndPreservesTheBrowserFailure() {
    System.setProperty(
        "teavm.junit.target", Path.of("target/failure-probe").toAbsolutePath().toString());
    System.setProperty("teavm.junit.js", "true");
    System.setProperty("teavm.junit.wasm-gc", "false");
    System.setProperty("teavm.junit.c", "false");
    System.setProperty("teavm.junit.minified", "false");
    System.setProperty("teavm.junit.optimized", "false");
    System.setProperty("teavm.junit.js.runner", "browser-chrome");
    System.setProperty("teavm.coverage.enabled", "true");
    System.setProperty(
        "teavm.coverage.sourceRoots", Path.of("src/main/java").toAbsolutePath().toString());
    System.setProperty(
        "teavm.coverage.dataDirectory",
        Path.of("target/failure-probe-data").toAbsolutePath().toString());
    var original = System.out;
    var captured = new ByteArrayOutputStream();
    try (var stream = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
      System.setOut(stream);
      var result = JUnitCore.runClasses(FailureScenario.class);
      assertEquals(1, result.getFailureCount());
      assertTrue(result.getFailures().get(0).getTrace().contains("expected coverage failure"));
    } finally {
      System.setOut(original);
    }
    assertTrue(captured.toString(StandardCharsets.UTF_8).contains("__TEAVM_COVERAGE_V1__:"));
  }
}
