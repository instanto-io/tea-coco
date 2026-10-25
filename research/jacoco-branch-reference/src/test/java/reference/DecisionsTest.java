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

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class DecisionsTest {
  @Test
  public void selectedInputs() {
    String mode = System.getProperty("reference.mode");
    if (mode.equals("A") || mode.equals("UNION")) {
      assertEquals(1, Decisions.simple(true));
      assertEquals(1, Decisions.and(true, true));
      assertEquals(2, Decisions.or(false, false));
      assertEquals(1, Decisions.ternary(true));
      assertEquals(1, Decisions.loop(1));
      assertEquals(10, Decisions.sharedSwitch(1));
      assertEquals(3, Decisions.fallThrough(1));
      assertEquals(1, Decisions.stringSwitch("FB"));
      assertEquals(42, Decisions.exceptions("42"));
    }
    if (mode.equals("B") || mode.equals("UNION")) {
      assertEquals(2, Decisions.simple(false));
      assertEquals(2, Decisions.and(false, false));
      assertEquals(2, Decisions.and(true, false));
      assertEquals(1, Decisions.or(true, false));
      assertEquals(1, Decisions.or(false, true));
      assertEquals(2, Decisions.ternary(false));
      assertEquals(0, Decisions.loop(0));
      assertEquals(20, Decisions.sharedSwitch(3));
      assertEquals(30, Decisions.sharedSwitch(0));
      assertEquals(2, Decisions.fallThrough(2));
      assertEquals(4, Decisions.fallThrough(0));
      assertEquals(2, Decisions.stringSwitch("Ea"));
      assertEquals(3, Decisions.stringSwitch("other"));
      assertEquals(-1, Decisions.exceptions("invalid"));
    }
    assertEquals(10, Decisions.singleDestinationSwitch(1));
    assertEquals(7, Decisions.eliminatedByJavac());
    // unreachable is deliberately never invoked: JaCoCo still counts its branches.
  }
}
