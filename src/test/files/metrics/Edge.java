package fixtures;

// methods with a body: Point compact constructor, Point.norm, Kind.label (constant body), Kind.code, Edge(), Edge.local's run = 6
public class Edge {
  record Point(int x, int y) {
    Point {
      if (x < 0) {
        throw new IllegalArgumentException();
      }
    }

    int norm() {
      return Math.abs(x) + Math.abs(y);
    }
  }

  enum Kind {
    A {
      @Override
      String label() {
        return "a";
      }
    },
    B;

    String label() {
      return "b";
    }
  }

  interface Shape {
    int area();
  }

  Edge() {
    class Local implements Runnable {
      @Override
      public void run() {
        System.out.println(new Point(1, 2).norm());
      }
    }
    new Local().run();
  }

  @javax.annotation.processing.Generated("tool")
  int generated() {
    return 42;
  }
}
