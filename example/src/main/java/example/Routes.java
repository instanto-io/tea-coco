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

/** Small application fixture with deliberately unexecuted and eliminated code. */
public final class Routes {
  private static int calls = initialize();

  private Routes() {}

  private static int initialize() {
    return 1;
  }

  public static int travel(boolean drive) throws InterruptedException {
    int distance;
    if (drive) {
      distance = 12; // covered
    } else {
      distance = 40; // uncovered
    }
    boolean ready = drive || uncalledSideEffect();
    Thread.sleep(1);
    return ready ? distance + calls : -1; // covered after asynchronous suspension
  }

  private static boolean uncalledSideEffect() {
    calls += 100; // uncovered short-circuited code
    return true;
  }

  public static int notIncluded() {
    return 987654; // not emitted
  }
}
