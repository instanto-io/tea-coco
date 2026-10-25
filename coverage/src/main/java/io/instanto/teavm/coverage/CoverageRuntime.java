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

import org.teavm.jso.JSBody;

/** Browser-side runtime; only linked when the compiler extension is enabled. */
public final class CoverageRuntime {
  private CoverageRuntime() {}

  public static native void hit(int probe);

  /** May also be called explicitly by an application-specific test harness. */
  public static void flush() {
    System.out.println("__TEAVM_COVERAGE_V1__:" + snapshot());
  }

  @JSBody(script = "return JSON.stringify(globalThis.__teavmCoverageV1 || {});")
  private static native String snapshot();
}
