package example;

import static org.junit.Assert.assertEquals;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.teavm.junit.TeaVMTestRunner;

@RunWith(TeaVMTestRunner.class)
public class DisabledIT {
  @Test
  public void executesWithoutCoverage() {
    assertEquals(20, DisabledCases.compute(10));
  }
}
