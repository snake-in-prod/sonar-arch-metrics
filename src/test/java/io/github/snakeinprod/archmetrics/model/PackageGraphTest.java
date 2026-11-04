package io.github.snakeinprod.archmetrics.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PackageGraphTest {

  /**
   * app.a -> app.b 3, app.b -> app.a 1, app.b -> app.c 2, app.c -> app.a 5; app.a.x -> app.a.y 1, app.a.y -> app.a.x 4.
   * Level "app": cycles a-b-a and a-b-c-a, cheapest cut is a -> b (3). Level "app.a": cut x -> y (1).
   */
  private static final Map<String, Map<String, Integer>> REFS = Map.of(
    "app.a", Map.of("app.b", 3),
    "app.b", Map.of("app.a", 1, "app.c", 2),
    "app.c", Map.of("app.a", 5),
    "app.a.x", Map.of("app.a.y", 1),
    "app.a.y", Map.of("app.a.x", 4));

  private static List<PackageGraph.Level> levels() {
    return PackageGraph.levels(Set.of("app.a", "app.b", "app.c", "app.a.x", "app.a.y"), REFS);
  }

  @Test
  void cycle_metrics_per_level_summed() {
    PackageGraph.Result r = PackageGraph.compute(levels(), PackageGraph.Settings.DEFAULT);
    assertThat(r.mfsRefs()).isEqualTo(3 + 1);
    assertThat(r.cycleRefsTotal()).isEqualTo(3 + 1 + 2 + 5 + 1 + 4);
    assertThat(r.cycleNodes()).isEqualTo(3 + 2);
    // root level: app; app: a, b, c; app.a: x, y
    assertThat(r.cycleNodesTotal()).isEqualTo(1 + 3 + 2);
    assertThat(r.depParents()).isEqualTo(2);
    assertThat(r.fanoutMax()).isEqualTo(2);
    assertThat(r.fanoutMaxAt()).isEqualTo("app.b in app");
    assertThat(r.depParentsOver()).isZero();
  }

  @Test
  void dependency_complexity_is_edges_minus_nodes_plus_components() {
    // app: E 4, N 3, P 1 -> 2 > 0 and fan-out 2 > 1; app.a: E 2, N 2, P 1 -> 1 > 0 but fan-out 1
    PackageGraph.Result r = PackageGraph.compute(levels(), new PackageGraph.Settings(0, 1));
    assertThat(r.depParentsOver()).isEqualTo(1);
    assertThat(r.violatingParents()).containsExactly("app");
  }

  @Test
  void references_from_sub_packages_count_for_their_ancestor() {
    // app.a.x -> app.b: at level "app" an edge a -> b
    List<PackageGraph.Level> l = PackageGraph.levels(Set.of("app.a.x", "app.b"), Map.of("app.a.x", Map.of("app.b", 2), "app.b", Map.of("app.a.x", 1)));
    PackageGraph.Result r = PackageGraph.compute(l, PackageGraph.Settings.DEFAULT);
    assertThat(r.cycleNodes()).isEqualTo(2);
    assertThat(r.mfsRefs()).isEqualTo(1);
  }

  @Test
  void greedy_heuristic_is_never_below_the_exact_optimum() {
    Random rnd = new Random(42);
    for (int round = 0; round < 200; round++) {
      int n = 2 + rnd.nextInt(7);
      int[][] w = new int[n][n];
      for (int a = 0; a < n; a++) {
        for (int b = 0; b < n; b++) {
          if (a != b && rnd.nextInt(3) == 0) {
            w[a][b] = 1 + rnd.nextInt(5);
          }
        }
      }
      assertThat(PackageGraph.greedy(w)).isGreaterThanOrEqualTo(PackageGraph.exact(w));
    }
  }

  @Test
  void exact_on_a_triangle_and_a_two_cycle() {
    assertThat(PackageGraph.exact(new int[][] {{0, 2, 0}, {0, 0, 3}, {1, 0, 0}})).isEqualTo(1);
    assertThat(PackageGraph.exact(new int[][] {{0, 5}, {2, 0}})).isEqualTo(2);
  }
}
