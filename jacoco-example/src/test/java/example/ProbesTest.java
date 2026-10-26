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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.teavm.junit.*;

@RunWith(TeaVMTestRunner.class)
@OnlyPlatform(TestPlatform.JAVASCRIPT)
@SkipJVM
public class ProbesTest {
  @Test
  public void initializationAndFirstOutcomes() {
    assertEquals(1, ProbeCases.INITIALIZED);
    assertEquals(1, ProbeCases.choose(true));
    assertEquals(5, new ProbeCases.Implementation().value(true));
    assertEquals(7, ProbeCases.Choice.staticValue(true));
  }

  @Test
  public void complementaryOutcomesAfterAsyncWork() throws InterruptedException {
    Thread.sleep(5);
    assertEquals(2, ProbeCases.choose(false));
    assertEquals(6, new ProbeCases.Implementation().value(false));
    assertEquals(8, ProbeCases.Choice.staticValue(false));
  }

  @Test
  public void repeatedFrame() {
    assertTrue(true);
    assertEquals(2, ProbeCases.choose(false));
  }
}
