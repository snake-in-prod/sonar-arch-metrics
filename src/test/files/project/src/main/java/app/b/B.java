package app.b;

import app.a.A;

public class B {
  public int value() {
    return 3;
  }

  public int back() {
    return new A().value();
  }
}
