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

import java.util.ArrayList;
import java.util.List;

/** Minimal portable probe storage; neither a JVM agent nor a browser API dependency. */
public final class ProbeRuntime {
  private static final List<String> ids = new ArrayList<>();
  private static final List<String> names = new ArrayList<>();
  private static final List<boolean[]> arrays = new ArrayList<>();

  private ProbeRuntime() {}

  public static boolean[] get(String id, String name, int size) {
    int index = ids.indexOf(id);
    if (index >= 0) {
      if (!names.get(index).equals(name) || arrays.get(index).length != size) {
        throw new IllegalStateException("Incompatible probe inventory");
      }
      return arrays.get(index);
    }
    boolean[] result = new boolean[size];
    ids.add(id);
    names.add(name);
    arrays.add(result);
    return result;
  }

  public static String dump() {
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < ids.size(); ++i) {
      result.append(ids.get(i)).append(',').append(names.get(i)).append(',');
      for (boolean hit : arrays.get(i)) {
        result.append(hit ? '1' : '0');
      }
      result.append('\n');
    }
    return result.toString();
  }
}
