package fixtures;

/*
 * Code lines: 1, 6, 8, 12, 13, 15, 16, 17, 18, 19, 20 = 11.
 */
public class Lines {
  // a comment line
  int f(int a) {

    assert a > 0
      : "positive";
    return a; // trailing comment
  }

  static class Inner {
    int g() {
      return 2;
    }
  }
}
