package app.a;

import app.b.B;

public class A {
  public int run() {
    return new B().value() + 1;
  }

  public int value() {
    return 2;
  }
}
