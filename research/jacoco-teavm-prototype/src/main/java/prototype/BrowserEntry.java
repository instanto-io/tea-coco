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

import org.teavm.jso.JSBody;

public final class BrowserEntry {
  private BrowserEntry() {}

  public static void main(String[] args) {
    Inputs.run(mode());
    publish(ProbeRuntime.dump());
  }

  @JSBody(script = "return globalThis.testMode;")
  private static native String mode();

  @JSBody(
      params = {"data"},
      script = "globalThis.probeDump = data;")
  private static native void publish(String data);
}
