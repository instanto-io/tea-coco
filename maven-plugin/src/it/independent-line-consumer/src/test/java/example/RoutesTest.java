package example;

import static org.junit.Assert.assertEquals;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.teavm.junit.TeaVMTestRunner;

@RunWith(TeaVMTestRunner.class)
public class RoutesTest {
  @Test
  public void executesRoute() {
    assertEquals(20, Routes.route(10));
  }
}
