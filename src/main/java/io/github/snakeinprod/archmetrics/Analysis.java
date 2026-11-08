package io.github.snakeinprod.archmetrics;

import com.google.gson.Gson;
import io.github.snakeinprod.archmetrics.model.Bytecode;
import io.github.snakeinprod.archmetrics.model.ClassGraph;
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
import java.util.TreeMap;
import java.util.function.Function;

/**
 * All metrics of one project, independent of the scanner: sources in, measures by metric key out. Set-level metrics
 * are computed for the whole project and again for each module (top-level directory).
 */
public final class Analysis {

  private static final int TOP = 10;

  /**
   * @param key      the file's component key; {@code filename} its name, e.g. {@code Foo.java}
   * @param module   top-level directory of the file, or null
   */
  public record Source(String key, String filename, String contents, String module) {
    public Source(String key, String filename, String contents) {
      this(key, filename, contents, null);
    }
  }

  /** Thresholds: method and class code lines, coupling, package size. */
  public record Settings(FileAnalyzer.Settings file, int dupTokens, PackageGraph.Settings graph, int nestingTopN, int methodLines,
    int classLines, int cbo, double pkgSize) {
    public static final Settings DEFAULT = new Settings(FileAnalyzer.Settings.DEFAULT, 50, PackageGraph.Settings.DEFAULT, 10, 20, 1000,
      100, 27_000_000);
  }

  /**
   * @param files     file key to metric key to value
   * @param project   metric key to value ({@code arch_top_offenders} is the JSON string)
   * @param modules   module to the same metrics as {@code project}, computed over the module's files only
   * @param compiled  project classes found in the binaries (0: coupling and package metrics are missing)
   */
  public record Result(Map<String, Map<String, Object>> files, Map<String, Object> project, Map<String, Map<String, Object>> modules,
    int compiled) {
  }

  private Analysis() {
  }

  public static Result run(List<Source> sources, Set<Path> binaries, Set<Path> jacocoReports, Settings settings) throws IOException {
    FileAnalyzer analyzer = new FileAnalyzer(settings.file());
    List<Source> sorted = new ArrayList<>(sources);
    sorted.sort(Comparator.comparing(Source::key));
    List<FileStats> stats = new ArrayList<>();
    Map<FileStats, List<Tokens.Token>> tokens = new IdentityHashMap<>();
    Map<FileStats, Source> sourceOf = new IdentityHashMap<>();
    Map<String, Source> bySourcePath = new HashMap<>();
    Map<String, Map<String, Object>> files = new LinkedHashMap<>();
    for (Source src : sorted) {
      List<Tokens.Token> t = Tokens.of(src.contents());
      FileStats s = analyzer.analyze(src.key(), src.contents(), t);
      if (s.generated()) {
        continue;
      }
      stats.add(s);
      tokens.put(s, t);
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
      m.put(ArchMetrics.METHOD_LINES_OVER_COUNT.getKey(), (int) s.methodInfos().stream().filter(x -> x.lines() > settings.methodLines()).count());
      m.put(ArchMetrics.CLASS_LINES_OVER_COUNT.getKey(), (int) s.classInfos().stream().filter(x -> x.lines() > settings.classLines()).count());
      m.put(ArchMetrics.HALSTEAD_OVER_COUNT.getKey(), (int) s.methodInfos().stream().filter(x -> effortOver(x, settings)).count());
    }

    Map<String, Bytecode.ClassRefs> classes = new HashMap<>();
    Bytecode.read(binaries).forEach((name, c) -> {
      if (!c.generated() && bySourcePath.containsKey(c.sourcePath())) {
        classes.put(name, c);
      }
    });
    for (Bytecode.ClassRefs c : classes.values()) {
      Map<String, Object> m = files.get(bySourcePath.get(c.sourcePath()).key());
      int cbo = Bytecode.coupling(c);
      m.merge(ArchMetrics.CBO_MAX.getKey(), cbo, (a, b) -> Math.max((Integer) a, (Integer) b));
      m.merge(ArchMetrics.CBO_OVER_COUNT.getKey(), cbo > settings.cbo() ? 1 : 0, (a, b) -> (Integer) a + (Integer) b);
      m.merge(ArchMetrics.GLOBAL_REFS.getKey(), Bytecode.globalRefs(c, classes), (a, b) -> (Integer) a + (Integer) b);
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

    Map<String, Object> project = scope(stats, tokens, sourceOf, classes, classes, bySourcePath, settings);
    Map<String, List<FileStats>> byModule = new TreeMap<>();
    for (FileStats s : stats) {
      String module = sourceOf.get(s).module();
      if (module != null) {
        byModule.computeIfAbsent(module, k -> new ArrayList<>()).add(s);
      }
    }
    Map<String, Map<String, Object>> modules = new LinkedHashMap<>();
    byModule.forEach((module, moduleStats) -> {
      Map<String, Bytecode.ClassRefs> own = new HashMap<>();
      classes.forEach((name, c) -> {
        if (module.equals(bySourcePath.get(c.sourcePath()).module())) {
          own.put(name, c);
        }
      });
      modules.put(module, scope(moduleStats, tokens, sourceOf, own, classes, bySourcePath, settings));
    });
    return new Result(files, project, modules, classes.size());
  }

  static boolean effortOver(FileStats.MethodInfo m, Settings settings) {
    return m.volume() > 200 && m.difficulty() > 5 && m.effort() > 1000 && m.lines() > settings.methodLines();
  }

  /**
   * The {@link ArchMetrics#SCOPED} values of some files: graphs only between their own classes, global accesses
   * against all classes.
   */
  private static Map<String, Object> scope(List<FileStats> stats, Map<FileStats, List<Tokens.Token>> tokens,
    Map<FileStats, Source> sourceOf, Map<String, Bytecode.ClassRefs> classes, Map<String, Bytecode.ClassRefs> allClasses,
    Map<String, Source> bySourcePath, Settings settings) {
    Map<String, Object> out = new LinkedHashMap<>();
    Map<String, List<Map<String, Object>>> top = new LinkedHashMap<>();
    top.put(ArchMetrics.CC_MAX.getKey(), topMethods(stats, sourceOf, FileStats.MethodInfo::complexity));
    top.put(ArchMetrics.NESTING_TOPN_AVG.getKey(), topMethods(stats, sourceOf, FileStats.MethodInfo::nesting));
    top.put(ArchMetrics.CLASS_LINES_MAX.getKey(), topClasses(stats, sourceOf));

    out.put(ArchMetrics.DUP_LINES.getKey(), Duplication.duplicatedLines(stats.stream().map(tokens::get).toList(),
      stats.stream().map(FileStats::codeLines).toList(), settings.dupTokens()));
    packageSizes(stats, out, top, settings.pkgSize());
    List<FileStats.MethodInfo> methods = stats.stream().flatMap(s -> s.methodInfos().stream()).toList();
    out.put(ArchMetrics.NESTING_TOPN_AVG.getKey(), methods.stream().map(FileStats.MethodInfo::nesting).sorted(Comparator.reverseOrder())
      .limit(settings.nestingTopN()).mapToInt(Integer::intValue).average().orElse(0));
    out.put(ArchMetrics.HALSTEAD_EFFORT_AVG.getKey(), methods.stream().mapToDouble(FileStats.MethodInfo::effort).average().orElse(0));
    unused(stats, out);

    if (!classes.isEmpty()) {
      List<Map<String, Object>> cbo = new ArrayList<>();
      List<Map<String, Object>> globals = new ArrayList<>();
      for (Bytecode.ClassRefs c : classes.values()) {
        String name = c.name().replace('/', '.').replace('$', '.');
        String file = bySourcePath.get(c.sourcePath()).key();
        cbo.add(row(name, file, 0, Bytecode.coupling(c)));
        int g = Bytecode.globalRefs(c, allClasses);
        if (g > 0) {
          globals.add(row(name, file, 0, g));
        }
      }
      top.put(ArchMetrics.CBO_MAX.getKey(), first(cbo));
      packages(classes, settings.graph(), out, top);
      classCycles(classes, out, top);
      top.put(ArchMetrics.GLOBAL_REFS.getKey(), first(globals));
    }
    top.put(ArchMetrics.METHOD_LINES_OVER_COUNT.getKey(), topMethods(stats, sourceOf, FileStats.MethodInfo::lines));
    top.put(ArchMetrics.HALSTEAD_OVER_COUNT.getKey(), topMethods(stats, sourceOf, m -> Math.round(m.effort())));
    out.put(ArchMetrics.TOP_OFFENDERS.getKey(), new Gson().toJson(top));
    return out;
  }

  /** Nested classes count as their top-level class. */
  private static void classCycles(Map<String, Bytecode.ClassRefs> classes, Map<String, Object> out,
    Map<String, List<Map<String, Object>>> top) {
    Set<String> nodes = new HashSet<>();
    Map<String, Map<String, Integer>> refs = new HashMap<>();
    for (Bytecode.ClassRefs c : classes.values()) {
      String from = topLevel(c.name());
      nodes.add(from);
      c.references().forEach((target, n) -> {
        if (classes.containsKey(target)) {
          refs.computeIfAbsent(from, k -> new HashMap<>()).merge(topLevel(target), n, Integer::sum);
        }
      });
    }
    ClassGraph.Result r = ClassGraph.compute(nodes, refs);
    out.put(ArchMetrics.CLASS_MFS_REFS.getKey(), r.mfsRefs());
    out.put(ArchMetrics.CLASS_REFS_TOTAL.getKey(), r.refsTotal());
    out.put(ArchMetrics.CLASS_CYCLE_NODES.getKey(), r.cycleNodes());
    out.put(ArchMetrics.CLASS_NODES.getKey(), r.nodes());
    top.put(ArchMetrics.CLASS_CYCLE_NODES.getKey(), r.cyclic().stream().limit(TOP).map(c -> row(c.replace('/', '.'), null, 0, 1)).toList());
  }

  private static String topLevel(String internalName) {
    int dollar = internalName.indexOf('$');
    return dollar < 0 ? internalName : internalName.substring(0, dollar);
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

  private static void packageSizes(List<FileStats> stats, Map<String, Object> project, Map<String, List<Map<String, Object>>> top,
    double limit) {
    Map<String, long[]> byPkg = new HashMap<>();
    for (FileStats s : stats) {
      long[] v = byPkg.computeIfAbsent(s.pkg(), k -> new long[2]);
      v[0] += s.codeLines().size();
      v[1] += s.methods();
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    double max = 0;
    int over = 0;
    for (Map.Entry<String, long[]> e : byPkg.entrySet()) {
      double size = (double) e.getValue()[0] * e.getValue()[1];
      max = Math.max(max, size);
      over += size > limit ? 1 : 0;
      rows.add(row(e.getKey().isEmpty() ? "(default)" : e.getKey(), null, 0, size));
    }
    project.put(ArchMetrics.PKG_SIZE_MAX.getKey(), max);
    project.put(ArchMetrics.PKG_SIZE_OVER_COUNT.getKey(), over);
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
    Function<FileStats.MethodInfo, Number> value) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (FileStats s : stats) {
      for (FileStats.MethodInfo m : s.methodInfos()) {
        rows.add(row(m.name(), sourceOf.get(s).key(), m.line(), value.apply(m)));
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
