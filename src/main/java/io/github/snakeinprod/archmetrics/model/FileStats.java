package io.github.snakeinprod.archmetrics.model;

import java.util.List;
import java.util.Set;

/**
 * Metrics of one source file, and what the project-level metrics need from it.
 *
 * @param pkg         Java package ({@code ""} for the default package)
 * @param generated   a top-level type is marked {@code @Generated}: the file is left out of every metric
 * @param codeLines   lines that hold code (not blank, not only comments, not part of an {@code assert})
 * @param declarations private and package-private classes, methods and fields that could be unused
 * @param usedNames   identifiers used in the file, declaration names excluded
 */
public record FileStats(String path, String pkg, boolean generated, int methods, Set<Integer> codeLines, int ccSum, int ccMax,
  int ccOver, int methodLinesSum, int classLinesMax, List<MethodInfo> methodInfos, List<ClassInfo> classInfos, int classesTotal,
  int methodsTotal, int variablesTotal, int unusedLocals, List<Declaration> declarations, Set<String> usedNames) {

  /** @param volume Halstead volume of the method's tokens; {@code difficulty} likewise */
  public record MethodInfo(String name, int line, int complexity, int nesting, int lines, double volume, double difficulty) {
    public double effort() {
      return volume * difficulty;
    }
  }

  public record ClassInfo(String name, int line, int lines) {
  }

  /** @param kind {@code class}, {@code method} or {@code variable} */
  public record Declaration(String kind, String name, boolean isPrivate, int line) {
  }

  public static FileStats generated(String path, String pkg) {
    return new FileStats(path, pkg, true, 0, Set.of(), 0, 0, 0, 0, 0, List.of(), List.of(), 0, 0, 0, 0, List.of(), Set.of());
  }
}
