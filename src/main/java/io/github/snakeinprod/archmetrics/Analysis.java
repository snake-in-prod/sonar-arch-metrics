package io.github.snakeinprod.archmetrics;

import com.google.gson.Gson;
import io.github.snakeinprod.archmetrics.model.Bytecode;
import io.github.snakeinprod.archmetrics.model.Duplication;
import io.github.snakeinprod.archmetrics.model.FileAnalyzer;
import io.github.snakeinprod.archmetrics.model.FileStats;
import io.github.snakeinprod.archmetrics.model.Jacoco;
import io.github.snakeinprod.archmetrics.model.PackageGraph;
import io.github.snakeinprod.archmetrics.model.Tokens;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

/** All metrics of one project, independent of the scanner: sources in, measures by metric key out. */
public final class Analysis {

  private static final int TOP = 10;

  /** @param key the file's component key; {@code filename} its name, e.g. {@code Foo.java} */
  public record Source(String key, String filename, String contents) {
  }

  public record Settings(FileAnalyzer.Settings file, int dupTokens, PackageGraph.Settings graph, int nestingTopN) {
    public static final Settings DEFAULT = new Settings(FileAnalyzer.Settings.DEFAULT, 50, PackageGraph.Settings.DEFAULT, 10);
  }

  /**
   * @param files     file key to metric key to value
   * @param project   metric key to value ({@code arch_top_offenders} is the JSON string)
   * @param compiled  project classes found in the binaries (0: coupling and package metrics are missing)
   */
  public record Result(Map<String, Map<String, Object>> files, Map<String, Object> project, int compiled) {
  }

  private Analysis() {
  }

  public static Result run(List<Source> sources, Set<Path> binaries, Set<Path> jacocoReports, Settings settings) throws IOException {
    FileAnalyzer analyzer = new FileAnalyzer(settings.file());
    List<Source> sorted = new ArrayList<>(sources);
    sorted.sort(Comparator.comparing(Source::key));
    List<FileStats> stats = new ArrayList<>();
    List<List<Tokens.Token>> tokens = new ArrayList<>();
    Map<FileStats, Source> sourceOf = new IdentityHashMap<>();
    Map<String, Source> bySourcePath = new HashMap<>();
    Map<String, Map<String, Object>> files = new LinkedHashMap<>();
    Map<String, Object> project = new LinkedHashMap<>();
    for (Source src : sorted) {
      List<Tokens.Token> t = Tokens.of(src.contents());
      FileStats s = analyzer.analyze(src.key(), src.contents(), t);
      if (s.generated()) {
        continue;
      }
      stats.add(s);
      tokens.add(t);
      sourceOf.put(s, src);
      bySourcePath.put((s.pkg().isEmpty() ? "" : s.pkg().replace('.', '/') + "/") + src.filename(), src);
      Map<String, Object> m = files.computeIfAbsent(src.key(), k -> new LinkedHashMap<>());
      m.put(ArchMetrics.METHODS.getKey(), s.methods());
      m.put(ArchMetrics.CODE_LINES.getKey(), s.codeLines().size());
      m.put(ArchMetrics.CC_SUM.getKey(), s.ccSum());
      m.put(ArchMetrics.CC_MAX.getKey(), s.ccMax());
      m.put(ArchMetrics.CC_OVER_COUNT.getKey(), s.ccOver());
      m.put(ArchMetrics.METHOD_LINES_SUM.getKey(), s.methodLinesSum());
      m.put(ArchMetrics.CLASS_LINES_MAX.getKey(), s.classLinesMax());
    }

    Map<String, List<Map<String, Object>>> top = new LinkedHashMap<>();
    top.put(ArchMetrics.CC_MAX.getKey(), topMethods(stats, sourceOf, FileStats.MethodInfo::complexity));
    top.put(ArchMetrics.NESTING_TOPN_AVG.getKey(), topMethods(stats, sourceOf, FileStats.MethodInfo::nesting));
    top.put(ArchMetrics.CLASS_LINES_MAX.getKey(), topClasses(stats, sourceOf));

    project.put(ArchMetrics.DUP_LINES.getKey(), Duplication.duplicatedLines(tokens, stats.stream().map(FileStats::codeLines).toList(),
      settings.dupTokens()));
    packageSizes(stats, project, top);
    List<Integer> deepest = stats.stream().flatMap(s -> s.methodInfos().stream()).map(FileStats.MethodInfo::nesting)
      .sorted(Comparator.reverseOrder()).limit(settings.nestingTopN()).toList();
    project.put(ArchMetrics.NESTING_TOPN_AVG.getKey(), deepest.stream().mapToInt(Integer::intValue).average().orElse(0));
    unused(stats, project);

    Map<String, Bytecode.ClassRefs> classes = new HashMap<>();
    Bytecode.read(binaries).forEach((name, c) -> {
      if (!c.generated() && bySourcePath.containsKey(c.sourcePath())) {
        classes.put(name, c);
      }
    });
    if (!classes.isEmpty()) {
      List<Map<String, Object>> rows = new ArrayList<>();
      for (Bytecode.ClassRefs c : classes.values()) {
        int cbo = Bytecode.coupling(c);
        Source src = bySourcePath.get(c.sourcePath());
        files.get(src.key()).merge(ArchMetrics.CBO_MAX.getKey(), cbo, (a, b) -> Math.max((Integer) a, (Integer) b));
        rows.add(row(c.name().replace('/', '.').replace('$', '.'), src.key(), 0, cbo));
      }
      top.put(ArchMetrics.CBO_MAX.getKey(), first(rows));
      packages(classes, settings.graph(), project, top);
    }

    try {
      Jacoco.read(jacocoReports).forEach((path, cov) -> {
        Source src = bySourcePath.get(path);
        if (src != null) {
          files.get(src.key()).put(ArchMetrics.METHODS_COVERED.getKey(), cov.covered());
          files.get(src.key()).put(ArchMetrics.METHODS_COVERABLE.getKey(), cov.coverable());
        }
      });
    } catch (Exception e) {
      throw new IOException("cannot read a JaCoCo report: " + e.getMessage(), e);
    }
    project.put(ArchMetrics.TOP_OFFENDERS.getKey(), new Gson().toJson(top));
    return new Result(files, project, classes.size());
  }

  private static void packages(Map<String, Bytecode.ClassRefs> classes, PackageGraph.Settings settings, Map<String, Object> project,
    Map<String, List<Map<String, Object>>> top) {
    PackageGraph.Result r = PackageGraph.compute(PackageGraph.levels(PackageGraph.packages(classes), PackageGraph.packageReferences(classes)),
      settings);
    project.put(ArchMetrics.MFS_REFS.getKey(), r.mfsRefs());
    project.put(ArchMetrics.CYCLE_REFS_TOTAL.getKey(), r.cycleRefsTotal());
    project.put(ArchMetrics.CYCLE_NODES.getKey(), r.cycleNodes());
    project.put(ArchMetrics.CYCLE_NODES_TOTAL.getKey(), r.cycleNodesTotal());
    project.put(ArchMetrics.DEP_PARENTS.getKey(), r.depParents());
    project.put(ArchMetrics.DEP_PARENTS_OVER.getKey(), r.depParentsOver());
    project.put(ArchMetrics.DEP_FANOUT_MAX.getKey(), r.fanoutMax());
    if (r.fanoutMaxAt() != null) {
      top.put(ArchMetrics.DEP_FANOUT_MAX.getKey(), List.of(row(r.fanoutMaxAt(), null, 0, r.fanoutMax())));
    }
    top.put(ArchMetrics.CYCLE_NODES.getKey(), r.cyclic().stream().limit(TOP).map(p -> row(p, null, 0, 1)).toList());
    top.put(ArchMetrics.DEP_PARENTS_OVER.getKey(), r.violatingParents().stream().limit(TOP).map(p -> row(p, null, 0, 1)).toList());
  }

  private static void packageSizes(List<FileStats> stats, Map<String, Object> project, Map<String, List<Map<String, Object>>> top) {
    Map<String, long[]> byPkg = new HashMap<>();
    for (FileStats s : stats) {
      long[] v = byPkg.computeIfAbsent(s.pkg(), k -> new long[2]);
      v[0] += s.codeLines().size();
      v[1] += s.methods();
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    double max = 0;
    for (Map.Entry<String, long[]> e : byPkg.entrySet()) {
      double size = (double) e.getValue()[0] * e.getValue()[1];
      max = Math.max(max, size);
      rows.add(row(e.getKey().isEmpty() ? "(default)" : e.getKey(), null, 0, size));
    }
    project.put(ArchMetrics.PKG_SIZE_MAX.getKey(), max);
    top.put(ArchMetrics.PKG_SIZE_MAX.getKey(), first(rows));
  }

  /** Private items must be used in their file, package-private ones in their package. */
  private static void unused(List<FileStats> stats, Map<String, Object> project) {
    Map<String, Set<String>> namesByPkg = new HashMap<>();
    for (FileStats s : stats) {
      namesByPkg.computeIfAbsent(s.pkg(), k -> new HashSet<>()).addAll(s.usedNames());
    }
    int classes = 0;
    int methods = 0;
    int variables = 0;
    int classesTotal = 0;
    int methodsTotal = 0;
    int variablesTotal = 0;
    for (FileStats s : stats) {
      classesTotal += s.classesTotal();
      methodsTotal += s.methodsTotal();
      variablesTotal += s.variablesTotal();
      variables += s.unusedLocals();
      for (FileStats.Declaration d : s.declarations()) {
        if (!(d.isPrivate() ? s.usedNames() : namesByPkg.get(s.pkg())).contains(d.name())) {
          switch (d.kind()) {
            case "class" -> classes++;
            case "method" -> methods++;
            default -> variables++;
          }
        }
      }
    }
    project.put(ArchMetrics.UNUSED_CLASSES.getKey(), classes);
    project.put(ArchMetrics.UNUSED_METHODS.getKey(), methods);
    project.put(ArchMetrics.UNUSED_VARIABLES.getKey(), variables);
    project.put(ArchMetrics.CLASSES.getKey(), classesTotal);
    project.put(ArchMetrics.METHODS_TOTAL.getKey(), methodsTotal);
    project.put(ArchMetrics.VARIABLES_TOTAL.getKey(), variablesTotal);
  }

  private static List<Map<String, Object>> topMethods(List<FileStats> stats, Map<FileStats, Source> sourceOf,
    ToIntFunction<FileStats.MethodInfo> value) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (FileStats s : stats) {
      for (FileStats.MethodInfo m : s.methodInfos()) {
        rows.add(row(m.name(), sourceOf.get(s).key(), m.line(), value.applyAsInt(m)));
      }
    }
    return first(rows);
  }

  private static List<Map<String, Object>> topClasses(List<FileStats> stats, Map<FileStats, Source> sourceOf) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (FileStats s : stats) {
      for (FileStats.ClassInfo c : s.classInfos()) {
        rows.add(row(c.name(), sourceOf.get(s).key(), c.line(), c.lines()));
      }
    }
    return first(rows);
  }

  /** The {@value #TOP} rows with the largest value. */
  private static List<Map<String, Object>> first(List<Map<String, Object>> rows) {
    rows.sort(Comparator.comparingDouble((Map<String, Object> m) -> ((Number) m.get("value")).doubleValue()).reversed());
    return new ArrayList<>(rows.subList(0, Math.min(TOP, rows.size())));
  }

  private static Map<String, Object> row(String name, String file, int line, Number value) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", name);
    if (file != null) {
      m.put("file", file);
    }
    if (line > 0) {
      m.put("line", line);
    }
    m.put("value", value);
    return m;
  }
}
