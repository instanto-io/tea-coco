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

import java.util.List;

/** Versioned exchange format; independent of compiler classes for use in build tools. */
public final class CoverageData {
  private CoverageData() {}

  public record Line(String file, int line) {}

  public record Inventory(int version, String id, List<Line> lines, List<Line> notEmitted) {}
}
