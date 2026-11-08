package io.github.snakeinprod.archmetrics.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Cycles between classes: one graph whose nodes are classes and whose edge weights are the references between them.
 * Sparse, for graphs of thousands of classes; the minimum feedback arc set follows {@link PackageGraph}.
 */
public final class ClassGraph {

  /** @param cyclic classes in a cycle */
  public record Result(int mfsRefs, int refsTotal, int cycleNodes, int nodes, List<String> cyclic) {
  }

  private ClassGraph() {
  }

  /** @param references from class to class to count; self references are ignored */
  public static Result compute(Set<String> classes, Map<String, Map<String, Integer>> references) {
    List<String> names = new ArrayList<>(new TreeSet<>(classes));
    Map<String, Integer> index = new HashMap<>();
    names.forEach(n -> index.put(n, index.size()));
    List<Map<Integer, Integer>> out = new ArrayList<>();
    names.forEach(n -> out.add(new HashMap<>()));
    int total = 0;
    for (Map.Entry<String, Map<String, Integer>> from : references.entrySet()) {
      Integer a = index.get(from.getKey());
      for (Map.Entry<String, Integer> to : from.getValue().entrySet()) {
        Integer b = index.get(to.getKey());
        if (a != null && b != null && !a.equals(b)) {
          out.get(a).merge(b, to.getValue(), Integer::sum);
          total += to.getValue();
        }
      }
    }
    int mfs = 0;
    int cycleNodes = 0;
    List<String> cyclic = new ArrayList<>();
    for (List<Integer> scc : stronglyConnected(out)) {
      if (scc.size() > 1) {
        cycleNodes += scc.size();
        scc.forEach(i -> cyclic.add(names.get(i)));
        mfs += feedbackArcSet(out, scc);
      }
    }
    cyclic.sort(null);
    return new Result(mfs, total, cycleNodes, names.size(), cyclic);
  }

  /** Tarjan's strongly connected components, iterative. */
  static List<List<Integer>> stronglyConnected(List<Map<Integer, Integer>> g) {
    int n = g.size();
    int[] index = new int[n];
    int[] low = new int[n];
    boolean[] onStack = new boolean[n];
    Arrays.fill(index, -1);
    Deque<Integer> stack = new ArrayDeque<>();
    List<List<Integer>> result = new ArrayList<>();
    int counter = 0;
    for (int s = 0; s < n; s++) {
      if (index[s] >= 0) {
        continue;
      }
      Deque<Integer> path = new ArrayDeque<>();
      Deque<Iterator<Integer>> next = new ArrayDeque<>();
      index[s] = low[s] = counter++;
      stack.push(s);
      onStack[s] = true;
      path.push(s);
      next.push(g.get(s).keySet().iterator());
      while (!path.isEmpty()) {
        int v = path.peek();
        Iterator<Integer> it = next.peek();
        if (it.hasNext()) {
          int u = it.next();
          if (index[u] < 0) {
            index[u] = low[u] = counter++;
            stack.push(u);
            onStack[u] = true;
            path.push(u);
            next.push(g.get(u).keySet().iterator());
          } else if (onStack[u]) {
            low[v] = Math.min(low[v], index[u]);
          }
          continue;
        }
        path.pop();
        next.pop();
        if (!path.isEmpty()) {
          low[path.peek()] = Math.min(low[path.peek()], low[v]);
        }
        if (low[v] == index[v]) {
          List<Integer> scc = new ArrayList<>();
          int u;
          do {
            u = stack.pop();
            onStack[u] = false;
            scc.add(u);
          } while (u != v);
          result.add(scc);
        }
      }
    }
    return result;
  }

  /** Exact up to {@link PackageGraph#EXACT_LIMIT} nodes, above it the Eades-Lin-Smyth order. */
  static int feedbackArcSet(List<Map<Integer, Integer>> g, List<Integer> scc) {
    int k = scc.size();
    Map<Integer, Integer> local = new HashMap<>();
    scc.forEach(v -> local.put(v, local.size()));
    if (k <= PackageGraph.EXACT_LIMIT) {
      int[][] w = new int[k][k];
      for (int v : scc) {
        g.get(v).forEach((u, c) -> {
          if (local.containsKey(u)) {
            w[local.get(v)][local.get(u)] = c;
          }
        });
      }
      return PackageGraph.exact(w);
    }
    List<Map<Integer, Integer>> out = new ArrayList<>();
    List<Map<Integer, Integer>> in = new ArrayList<>();
    for (int i = 0; i < k; i++) {
      out.add(new HashMap<>());
      in.add(new HashMap<>());
    }
    int[] outW = new int[k];
    int[] inW = new int[k];
    for (int v : scc) {
      int a = local.get(v);
      g.get(v).forEach((u, c) -> {
        Integer b = local.get(u);
        if (b != null) {
          out.get(a).put(b, c);
          in.get(b).put(a, c);
          outW[a] += c;
          inW[b] += c;
        }
      });
    }
    return greedy(out, in, outW, inW);
  }

  private static int greedy(List<Map<Integer, Integer>> out, List<Map<Integer, Integer>> in, int[] outW, int[] inW) {
    int k = out.size();
    boolean[] removed = new boolean[k];
    List<Integer> front = new ArrayList<>();
    Deque<Integer> back = new ArrayDeque<>();
    Deque<Integer> check = new ArrayDeque<>();
    for (int v = 0; v < k; v++) {
      check.add(v);
    }
    int left = k;
    while (left > 0) {
      while (!check.isEmpty()) {
        int v = check.poll();
        if (removed[v]) {
          continue;
        }
        if (outW[v] == 0) {
          back.push(v);
        } else if (inW[v] == 0) {
          front.add(v);
        } else {
          continue;
        }
        remove(v, out, in, outW, inW, removed, check);
        left--;
      }
      if (left == 0) {
        break;
      }
      int pick = -1;
      for (int v = 0; v < k; v++) {
        if (!removed[v] && (pick < 0 || outW[v] - inW[v] > outW[pick] - inW[pick])) {
          pick = v;
        }
      }
      front.add(pick);
      remove(pick, out, in, outW, inW, removed, check);
      left--;
    }
    front.addAll(back);
    int[] pos = new int[k];
    for (int i = 0; i < k; i++) {
      pos[front.get(i)] = i;
    }
    int cost = 0;
    for (int a = 0; a < k; a++) {
      for (Map.Entry<Integer, Integer> e : out.get(a).entrySet()) {
        if (pos[a] > pos[e.getKey()]) {
          cost += e.getValue();
        }
      }
    }
    return cost;
  }

  private static void remove(int v, List<Map<Integer, Integer>> out, List<Map<Integer, Integer>> in, int[] outW, int[] inW,
    boolean[] removed, Deque<Integer> check) {
    removed[v] = true;
    out.get(v).forEach((u, c) -> {
      if (!removed[u]) {
        inW[u] -= c;
        check.add(u);
      }
    });
    in.get(v).forEach((u, c) -> {
      if (!removed[u]) {
        outW[u] -= c;
        check.add(u);
      }
    });
  }
}
