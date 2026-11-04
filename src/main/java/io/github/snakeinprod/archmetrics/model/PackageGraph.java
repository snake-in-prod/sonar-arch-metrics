package io.github.snakeinprod.archmetrics.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Metrics of the package tree. For every parent package, the graph of its child packages: nodes are the children,
 * edge weights the number of references from classes anywhere below one child to classes below another.
 */
public final class PackageGraph {

  /** Graphs with at most this many nodes in a strongly connected component get an exact minimum feedback arc set. */
  public static final int EXACT_LIMIT = 20;

  /** @param complexityLimit E - N + P above which a parent violates; {@code fanoutLimit} likewise for the fan-out */
  public record Settings(int complexityLimit, int fanoutLimit) {
    public static final Settings DEFAULT = new Settings(12, 9);
  }

  /** One level of the tree: the children of {@code parent} and the references between them. */
  public record Level(String parent, List<String> children, int[][] weights) {
  }

  /**
   * @param fanoutMaxAt parent and child with the largest fan-out
   * @param cyclic      packages in a cycle, with their parent
   */
  public record Result(int mfsRefs, int cycleRefsTotal, int cycleNodes, int cycleNodesTotal, int depParents, int depParentsOver,
    int fanoutMax, String fanoutMaxAt, List<String> cyclic, List<String> violatingParents) {
  }

  private PackageGraph() {
  }

  /**
   * @param packages   every package with classes (dotted names, {@code ""} for the default package)
   * @param references class-level references between project classes: from package to package, to count
   */
  public static List<Level> levels(Set<String> packages, Map<String, Map<String, Integer>> references) {
    // the tree: every package and its ancestors, below a virtual root ""
    Map<String, Set<String>> children = new TreeMap<>();
    for (String p : packages) {
      String node = p.isEmpty() ? "(default)" : p;
      String parent = parentOf(node);
      while (true) {
        children.computeIfAbsent(parent, k -> new TreeSet<>()).add(node);
        if (parent.isEmpty()) {
          break;
        }
        node = parent;
        parent = parentOf(node);
      }
    }
    List<Level> out = new ArrayList<>();
    for (Map.Entry<String, Set<String>> e : children.entrySet()) {
      List<String> kids = new ArrayList<>(e.getValue());
      int[][] w = new int[kids.size()][kids.size()];
      for (Map.Entry<String, Map<String, Integer>> from : references.entrySet()) {
        int a = childIndex(kids, e.getKey(), from.getKey().isEmpty() ? "(default)" : from.getKey());
        if (a < 0) {
          continue;
        }
        for (Map.Entry<String, Integer> to : from.getValue().entrySet()) {
          int b = childIndex(kids, e.getKey(), to.getKey().isEmpty() ? "(default)" : to.getKey());
          if (b >= 0 && b != a) {
            w[a][b] += to.getValue();
          }
        }
      }
      out.add(new Level(e.getKey(), kids, w));
    }
    return out;
  }

  private static String parentOf(String pkg) {
    int dot = pkg.lastIndexOf('.');
    return dot < 0 ? "" : pkg.substring(0, dot);
  }

  /** Index of the child of {@code parent} that is {@code pkg} or contains it, or -1. */
  private static int childIndex(List<String> kids, String parent, String pkg) {
    if (!parent.isEmpty() && !pkg.startsWith(parent + ".")) {
      return -1;
    }
    String rest = parent.isEmpty() ? pkg : pkg.substring(parent.length() + 1);
    int dot = rest.indexOf('.');
    String child = (parent.isEmpty() ? "" : parent + ".") + (dot < 0 ? rest : rest.substring(0, dot));
    return kids.indexOf(child);
  }

  public static Result compute(List<Level> levels, Settings settings) {
    int mfs = 0;
    int refsTotal = 0;
    int cycleNodes = 0;
    int nodesTotal = 0;
    int depParents = 0;
    int depOver = 0;
    int fanoutMax = 0;
    String fanoutAt = null;
    List<String> cyclic = new ArrayList<>();
    List<String> violating = new ArrayList<>();
    for (Level l : levels) {
      int n = l.children().size();
      nodesTotal += n;
      for (int[] row : l.weights()) {
        for (int v : row) {
          refsTotal += v;
        }
      }
      for (List<Integer> scc : stronglyConnected(l.weights())) {
        if (scc.size() > 1) {
          cycleNodes += scc.size();
          scc.forEach(i -> cyclic.add(l.children().get(i)));
          mfs += feedbackArcSet(l.weights(), scc);
        }
      }
      int edges = 0;
      int levelFanout = 0;
      for (int a = 0; a < n; a++) {
        int out = 0;
        for (int b = 0; b < n; b++) {
          if (l.weights()[a][b] > 0) {
            edges++;
            out++;
          }
        }
        levelFanout = Math.max(levelFanout, out);
        if (out > fanoutMax) {
          fanoutMax = out;
          fanoutAt = l.children().get(a) + " in " + (l.parent().isEmpty() ? "(root)" : l.parent());
        }
      }
      if (n >= 2 && !l.parent().isEmpty()) {
        depParents++;
        int complexity = edges - n + components(l.weights());
        if (complexity > settings.complexityLimit() && levelFanout > settings.fanoutLimit()) {
          depOver++;
          violating.add(l.parent());
        }
      }
    }
    return new Result(mfs, refsTotal, cycleNodes, nodesTotal, depParents, depOver, fanoutMax, fanoutAt, cyclic, violating);
  }

  /** Weakly connected components. */
  static int components(int[][] w) {
    int n = w.length;
    int[] comp = new int[n];
    Arrays.fill(comp, -1);
    int c = 0;
    for (int s = 0; s < n; s++) {
      if (comp[s] >= 0) {
        continue;
      }
      Deque<Integer> todo = new ArrayDeque<>(List.of(s));
      comp[s] = c;
      while (!todo.isEmpty()) {
        int a = todo.pop();
        for (int b = 0; b < n; b++) {
          if ((w[a][b] > 0 || w[b][a] > 0) && comp[b] < 0) {
            comp[b] = c;
            todo.push(b);
          }
        }
      }
      c++;
    }
    return c;
  }

  /** Tarjan's strongly connected components. */
  static List<List<Integer>> stronglyConnected(int[][] w) {
    int n = w.length;
    int[] index = new int[n];
    int[] low = new int[n];
    boolean[] onStack = new boolean[n];
    Arrays.fill(index, -1);
    Deque<Integer> stack = new ArrayDeque<>();
    List<List<Integer>> out = new ArrayList<>();
    int[] counter = {0};
    for (int v = 0; v < n; v++) {
      if (index[v] < 0) {
        strong(v, w, index, low, onStack, stack, out, counter);
      }
    }
    return out;
  }

  private static void strong(int v, int[][] w, int[] index, int[] low, boolean[] onStack, Deque<Integer> stack, List<List<Integer>> out,
    int[] counter) {
    index[v] = low[v] = counter[0]++;
    stack.push(v);
    onStack[v] = true;
    for (int u = 0; u < w.length; u++) {
      if (w[v][u] <= 0) {
        continue;
      }
      if (index[u] < 0) {
        strong(u, w, index, low, onStack, stack, out, counter);
        low[v] = Math.min(low[v], low[u]);
      } else if (onStack[u]) {
        low[v] = Math.min(low[v], index[u]);
      }
    }
    if (low[v] == index[v]) {
      List<Integer> scc = new ArrayList<>();
      int u;
      do {
        u = stack.pop();
        onStack[u] = false;
        scc.add(u);
      } while (u != v);
      out.add(scc);
    }
  }

  /**
   * Weight of a minimum feedback arc set of one strongly connected component: the least total weight of references
   * whose removal leaves no cycle. Equivalent to the cheapest node order, where every reference pointing backwards in
   * the order is removed. Exact (dynamic programming over subsets) up to {@link #EXACT_LIMIT} nodes; above it the
   * greedy order of Eades, Lin and Smyth (1993), "A fast and effective heuristic for the feedback arc set problem".
   */
  static int feedbackArcSet(int[][] w, List<Integer> scc) {
    int n = scc.size();
    int[][] sub = new int[n][n];
    for (int a = 0; a < n; a++) {
      for (int b = 0; b < n; b++) {
        sub[a][b] = w[scc.get(a)][scc.get(b)];
      }
    }
    return n <= EXACT_LIMIT ? exact(sub) : greedy(sub);
  }

  /** best[S] = cheapest order of the nodes in S placed first; adding v after S costs the references from v into S. */
  static int exact(int[][] w) {
    int n = w.length;
    int[] best = new int[1 << n];
    Arrays.fill(best, Integer.MAX_VALUE);
    best[0] = 0;
    // into[v][S] would need 2^n * n memory; compute the cost of placing v after S incrementally instead
    for (int s = 0; s < 1 << n; s++) {
      if (best[s] == Integer.MAX_VALUE) {
        continue;
      }
      for (int v = 0; v < n; v++) {
        if ((s & 1 << v) != 0) {
          continue;
        }
        int cost = 0;
        for (int u = 0; u < n; u++) {
          if ((s & 1 << u) != 0) {
            cost += w[v][u];
          }
        }
        int t = s | 1 << v;
        best[t] = Math.min(best[t], best[s] + cost);
      }
    }
    return best[(1 << n) - 1];
  }

  /** Eades-Lin-Smyth: peel sinks to the end and sources to the front, otherwise the node with the largest out - in. */
  static int greedy(int[][] w) {
    int n = w.length;
    boolean[] removed = new boolean[n];
    Deque<Integer> front = new ArrayDeque<>();
    Deque<Integer> back = new ArrayDeque<>();
    int left = n;
    while (left > 0) {
      boolean changed = true;
      while (changed) {
        changed = false;
        for (int v = 0; v < n; v++) {
          if (!removed[v] && degree(w, removed, v, true) == 0) {
            back.push(v);
            removed[v] = true;
            left--;
            changed = true;
          } else if (!removed[v] && degree(w, removed, v, false) == 0) {
            front.addLast(v);
            removed[v] = true;
            left--;
            changed = true;
          }
        }
      }
      if (left == 0) {
        break;
      }
      int pick = -1;
      int bestDelta = Integer.MIN_VALUE;
      for (int v = 0; v < n; v++) {
        if (!removed[v]) {
          int delta = degree(w, removed, v, true) - degree(w, removed, v, false);
          if (delta > bestDelta) {
            bestDelta = delta;
            pick = v;
          }
        }
      }
      front.addLast(pick);
      removed[pick] = true;
      left--;
    }
    List<Integer> order = new ArrayList<>(front);
    order.addAll(back);
    int[] pos = new int[n];
    for (int i = 0; i < n; i++) {
      pos[order.get(i)] = i;
    }
    int cost = 0;
    for (int a = 0; a < n; a++) {
      for (int b = 0; b < n; b++) {
        if (w[a][b] > 0 && pos[a] > pos[b]) {
          cost += w[a][b];
        }
      }
    }
    return cost;
  }

  /** Weighted out-degree ({@code out}) or in-degree among the nodes left. */
  private static int degree(int[][] w, boolean[] removed, int v, boolean out) {
    int d = 0;
    for (int u = 0; u < w.length; u++) {
      if (!removed[u]) {
        d += out ? w[v][u] : w[u][v];
      }
    }
    return d;
  }

  /** Package-to-package reference counts from class-level references, project classes only. */
  public static Map<String, Map<String, Integer>> packageReferences(Map<String, Bytecode.ClassRefs> classes) {
    Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
    for (Bytecode.ClassRefs c : classes.values()) {
      for (Map.Entry<String, Integer> r : c.references().entrySet()) {
        Bytecode.ClassRefs target = classes.get(r.getKey());
        if (target != null) {
          out.computeIfAbsent(c.packageName(), k -> new HashMap<>()).merge(target.packageName(), r.getValue(), Integer::sum);
        }
      }
    }
    return out;
  }

  public static Set<String> packages(Map<String, Bytecode.ClassRefs> classes) {
    Set<String> out = new HashSet<>();
    classes.values().forEach(c -> out.add(c.packageName()));
    return out;
  }
}
