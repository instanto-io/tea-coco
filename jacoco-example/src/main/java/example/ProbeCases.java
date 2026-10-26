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

public final class ProbeCases {
  public static final int INITIALIZED = choose(true);

  private ProbeCases() {}

  public static int choose(boolean first) {
    return first ? 1 : 2;
  }

  public static int neverCalled(boolean first) {
    return first ? 3 : 4;
  }

  public interface Choice {
    default int value(boolean first) {
      return first ? 5 : 6;
    }

    static int staticValue(boolean first) {
      return first ? 7 : 8;
    }
  }

  public static final class Implementation implements Choice {}
}
