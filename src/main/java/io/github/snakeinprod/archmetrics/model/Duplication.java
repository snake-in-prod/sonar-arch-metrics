package io.github.snakeinprod.archmetrics.model;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Duplicated blocks over the whole project: runs of at least {@code window} identical tokens that occur more than
 * once. Every occurrence after the first (in file order, then position) marks its tokens; overlapping blocks merge
 * because marks are per token; the code lines holding marked tokens are counted.
 */
public final class Duplication {

  private Duplication() {
  }

  /** Largest number of tokens the index can hold (24 bits of token position in the packed key). */
  public static final int MAX_TOKENS = (1 << 24) - 1;

  /** Whether {@link #duplicatedLines} can measure these files: projects past {@link #MAX_TOKENS} get no duplication measure. */
  public static boolean fits(List<List<Tokens.Token>> files) {
    long total = 0;
    for (List<Tokens.Token> f : files) {
      total += f.size();
    }
    return total <= MAX_TOKENS;
  }

  /** @param files tokens of each file, in a stable order; {@code codeLines} of the same files */
  public static int duplicatedLines(List<List<Tokens.Token>> files, List<Set<Integer>> codeLines, int window) {
    int total = 0;
    for (List<Tokens.Token> f : files) {
      total += f.size();
    }
    if (total >= 1 << 24) {
      // ponytail: 24 bits of token index in the packed key; split the index by file if projects grow past 16M tokens
      throw new IllegalStateException("too many tokens for the duplication index: " + total);
    }
    String[] text = new String[total];
    int[] fileOf = new int[total];
    int[] offset = new int[files.size()];
    int g = 0;
    for (int fi = 0; fi < files.size(); fi++) {
      offset[fi] = g;
      for (Tokens.Token t : files.get(fi)) {
        text[g] = t.text();
        fileOf[g++] = fi;
      }
    }
    // hash every window that lies inside one file; pack (hash, start) into one long and sort
    int count = 0;
    long[] keys = new long[Math.max(0, total)];
    for (int fi = 0; fi < files.size(); fi++) {
      int n = files.get(fi).size();
      if (n < window) {
        continue;
      }
      long h = 0;
      long pow = 1;
      for (int k = 0; k < window - 1; k++) {
        pow *= 31;
      }
      int base = offset[fi];
      for (int k = 0; k < window; k++) {
        h = h * 31 + text[base + k].hashCode();
      }
      for (int s = 0; ; s++) {
        keys[count++] = (h & 0xFFFFFFFFFFL) << 24 | (base + s);
        if (s + window >= n) {
          break;
        }
        h = (h - text[base + s].hashCode() * pow) * 31 + text[base + s + window].hashCode();
      }
    }
    long[] sorted = Arrays.copyOf(keys, count);
    Arrays.sort(sorted);
    boolean[] marked = new boolean[total];
    int i = 0;
    while (i < sorted.length) {
      int j = i;
      while (j + 1 < sorted.length && sorted[j + 1] >>> 24 == sorted[i] >>> 24) {
        j++;
      }
      if (j > i) {
        markRun(sorted, i, j, text, marked, window);
      }
      i = j + 1;
    }
    Set<Long> lines = new HashSet<>();
    for (int fi = 0; fi < files.size(); fi++) {
      List<Tokens.Token> f = files.get(fi);
      for (int k = 0; k < f.size(); k++) {
        int line = f.get(k).line();
        if (marked[offset[fi] + k] && codeLines.get(fi).contains(line)) {
          lines.add((long) fi << 32 | line);
        }
      }
    }
    return lines.size();
  }

  /** Windows with one hash, sorted by start: group equal ones, mark all but the first of each group. */
  private static void markRun(long[] sorted, int from, int to, String[] text, boolean[] marked, int window) {
    boolean[] done = new boolean[to - from + 1];
    for (int a = from; a <= to; a++) {
      if (done[a - from]) {
        continue;
      }
      int first = (int) (sorted[a] & 0xFFFFFF);
      for (int b = a + 1; b <= to; b++) {
        int other = (int) (sorted[b] & 0xFFFFFF);
        if (!done[b - from] && same(text, first, other, window)) {
          done[b - from] = true;
          Arrays.fill(marked, other, other + window, true);
        }
      }
    }
  }

  private static boolean same(String[] text, int a, int b, int window) {
    for (int k = 0; k < window; k++) {
      if (!text[a + k].equals(text[b + k])) {
        return false;
      }
    }
    return true;
  }
}
