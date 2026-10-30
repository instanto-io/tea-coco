package example;

import static org.junit.Assert.assertEquals;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.teavm.junit.TeaVMTestRunner;

@RunWith(TeaVMTestRunner.class)
public class ProbesIT {
  @Test
  public void exercisesBothBranches() {
    assertEquals(1, ProbeCases.choose(true));
    assertEquals(2, ProbeCases.choose(false));
  }
}
