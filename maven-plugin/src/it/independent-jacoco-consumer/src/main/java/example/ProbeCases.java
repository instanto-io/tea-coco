package example;

public class ProbeCases {
  public static int choose(boolean flag) {
    if (flag) {
      return 1;
    } else {
      return 2;
    }
  }

  public static int neverCalled(boolean flag) {
    if (flag) {
      return 10;
    } else {
      return 20;
    }
  }
}
