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
package reference;

/** Small original fixtures for measuring JaCoCo's actual branch semantics. */
public final class Decisions {
  private Decisions() {}

  public static int simple(boolean condition) {
    if (condition) {
      return 1;
    }
    return 2;
  }

  public static int and(boolean first, boolean second) {
    return first && second ? 1 : 2;
  }

  public static int or(boolean first, boolean second) {
    return first || second ? 1 : 2;
  }

  public static int ternary(boolean condition) {
    return condition ? 1 : 2;
  }

  public static int loop(int limit) {
    int count = 0;
    for (int i = 0; i < limit; i++) {
      count++;
    }
    return count;
  }

  public static int sharedSwitch(int value) {
    switch (value) {
      case 1:
      case 2:
        return 10;
      case 3:
        return 20;
      default:
        return 30;
    }
  }

  public static int singleDestinationSwitch(int value) {
    switch (value) {
      case 1:
      default:
        return 10;
    }
  }

  public static int fallThrough(int value) {
    int result = 0;
    switch (value) {
      case 1:
        result++;
      case 2:
        result += 2;
        break;
      default:
        result = 4;
    }
    return result;
  }

  public static int stringSwitch(String value) {
    // FB and Ea have the same Java hashCode, exercising javac's collision scaffolding.
    switch (value) {
      case "FB":
        return 1;
      case "Ea":
        return 2;
      default:
        return 3;
    }
  }

  public static int exceptions(String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  public static int eliminatedByJavac() {
    if (false) {
      return 999;
    }
    return 7;
  }

  public static int unreachable(boolean condition) {
    return condition ? 1 : 2;
  }
}
