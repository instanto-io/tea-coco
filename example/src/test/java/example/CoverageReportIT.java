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

import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;

/** Verifies reporting against actual browser execution, not fabricated hit arrays. */
public class CoverageReportIT {
  @Test
  public void distinguishesIncludedAndExcludedJavaLines() throws Exception {
    var doc =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(Path.of("target/teavm-coverage.xml").toFile());
    var entries = doc.getElementsByTagName("lineToCover");
    var hits = new java.util.HashMap<Integer, Boolean>();
    for (int i = 0; i < entries.getLength(); ++i) {
      var element = (org.w3c.dom.Element) entries.item(i);
      hits.put(
          Integer.parseInt(element.getAttribute("lineNumber")),
          Boolean.parseBoolean(element.getAttribute("covered")));
    }
    var source = Files.readAllLines(Path.of("src/main/java/example/Routes.java"));
    for (int i = 0; i < source.size(); ++i) {
      var line = source.get(i);
      if (line.contains("// uncovered"))
        assertEquals("line " + (i + 1), Boolean.FALSE, hits.get(i + 1));
      if (line.contains("// covered"))
        assertEquals("line " + (i + 1), Boolean.TRUE, hits.get(i + 1));
      if (line.contains("// not emitted")) assertFalse(hits.containsKey(i + 1));
    }
    try (var compiled = Files.walk(Path.of("target/teavm-tests"))) {
      for (var file : compiled.filter(p -> p.toString().endsWith(".js")).toList()) {
        assertFalse("Unused method kept alive: " + file, Files.readString(file).contains("987654"));
      }
    }
    assertTrue(Files.exists(Path.of("target/not-emitted.json")));
  }
}
