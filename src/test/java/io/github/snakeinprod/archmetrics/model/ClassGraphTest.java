package io.github.snakeinprod.archmetrics.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClassGraphTest {

  @Test
  void two_classes_cut_the_lighter_direction() {
    ClassGraph.Result r = ClassGraph.compute(Set.of("A", "B", "C"), Map.of("A", Map.of("B", 3, "A", 9), "B", Map.of("A", 1, "C", 2)));
    assertThat(r.mfsRefs()).isEqualTo(1);
    assertThat(r.refsTotal()).isEqualTo(6);
    assertThat(r.cycleNodes()).isEqualTo(2);
    assertThat(r.nodes()).isEqualTo(3);
    assertThat(r.cyclic()).containsExactly("A", "B");
  }

  @Test
  void large_ring_uses_the_greedy_order() {
    Set<String> nodes = new HashSet<>();
    Map<String, Map<String, Integer>> refs = new HashMap<>();
    int n = PackageGraph.EXACT_LIMIT + 30;
    for (int i = 0; i < n; i++) {
      nodes.add("C" + i);
      refs.put("C" + i, Map.of("C" + (i + 1) % n, 1));
    }
    ClassGraph.Result r = ClassGraph.compute(nodes, refs);
    assertThat(r.mfsRefs()).isEqualTo(1);
    assertThat(r.cycleNodes()).isEqualTo(n);
  }
}
