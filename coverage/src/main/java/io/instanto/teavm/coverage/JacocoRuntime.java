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

import java.util.ArrayList;
import java.util.List;

/** Portable runtime for JaCoCo probes; no agent or browser API is required. */
public final class JacocoRuntime {
  private static final List<String> runs = new ArrayList<>();
  private static final List<String> ids = new ArrayList<>();
  private static final List<String> names = new ArrayList<>();
  private static final List<boolean[]> arrays = new ArrayList<>();

  private JacocoRuntime() {}

  public static boolean[] get(String run, String id, String name, int size) {
    for (int i = 0; i < ids.size(); i++) {
      if (ids.get(i).equals(id) && runs.get(i).equals(run)) {
        if (!names.get(i).equals(name) || arrays.get(i).length != size)
          throw new IllegalStateException("Incompatible coverage probe inventory");
        return arrays.get(i);
      }
    }
    boolean[] result = new boolean[size];
    runs.add(run);
    ids.add(id);
    names.add(name);
    arrays.add(result);
    return result;
  }

  /** Call after custom harness work completes; stock TeaVMTestRunner is flushed automatically. */
  public static void flush(String run) {
    StringBuilder result = new StringBuilder("__TEA_COCO_JACOCO_V1__:").append(run).append('|');
    for (int i = 0; i < ids.size(); i++) {
      if (!runs.get(i).equals(run)) continue;
      result.append(ids.get(i)).append(',').append(names.get(i)).append(',');
      for (boolean hit : arrays.get(i)) result.append(hit ? '1' : '0');
      result.append(';');
    }
    System.out.println(result.toString());
  }
}
