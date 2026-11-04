package fixtures;

public class Complexity {
  int plain() {
    return 1;
  }

  // 1 + if, else if, for, for-each, while, do, catch, ?:, switch, ?: in the lambda = 11; && is not counted
  int branches(int a, boolean b) {
    if (a > 0 && b) {
      a++;
    } else if (a < 0) {
      a--;
    }
    for (int i = 0; i < a; i++) {
      a += i;
    }
    for (int x : new int[] {1, 2}) {
      a += x;
    }
    while (a > 100) {
      a /= 2;
    }
    do {
      a++;
    } while (a < 0);
    try {
      a = a / 0;
    } catch (ArithmeticException e) {
      a = 0;
    }
    int c = b ? 1 : 2;
    switch (a) {
      case 1:
        c++;
        break;
      case 2:
        c--;
        break;
      default:
        break;
    }
    java.util.function.IntUnaryOperator f = v -> v > 0 ? v : -v;
    return a + c + f.applyAsInt(a);
  }

  // 1 + switch expression = 2 (cases: 1 + 2 = 3)
  int switchExpr(String s) {
    return switch (s) {
      case "a" -> 1;
      case "b" -> 2;
      default -> 0;
    };
  }

  // 1; the anonymous class's run() is a method of its own: 1 + if = 2
  Runnable anon() {
    return new Runnable() {
      @Override
      public void run() {
        if (System.nanoTime() > 0) {
          System.out.println();
        }
      }
    };
  }

  // nesting 4: for-each > if > (else if, same depth) > try > synchronized; complexity 1 + for-each, if, while, else if, catch = 6
  void deep(int[] xs) {
    for (int x : xs) {
      if (x > 0) {
        while (x > 10) {
          x--;
        }
      } else if (x < 0) {
        try {
          synchronized (this) {
            x++;
          }
        } catch (RuntimeException e) {
          x = 0;
        }
      }
    }
  }
}
