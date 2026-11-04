package io.github.snakeinprod.archmetrics;

import java.util.List;
import org.sonar.api.measures.Metric;
import org.sonar.api.measures.Metrics;

/** The raw measures. Keys are a contract: other plugins and quality gates read them. */
public class ArchMetrics implements Metrics {

  public static final String ARCHITECTURE = "Architecture";
  public static final String TESTS = "Tests";

  public static final Metric<Integer> METHODS = integer("arch_methods", "Methods with a body", "Methods and constructors with a body");
  public static final Metric<Integer> CODE_LINES = integer("arch_code_lines", "Code lines",
    "Physical lines minus blank lines, comment-only lines and lines of assert statements");
  public static final Metric<Integer> CC_SUM = integer("arch_cc_sum", "Method complexity sum", "Sum of the complexity of all methods");
  public static final Metric<Integer> CC_MAX = integer("arch_cc_max", "Method complexity max", "Largest method complexity");
  public static final Metric<Integer> CC_OVER_COUNT = integer("arch_cc_over_count", "Methods over complexity threshold",
    "Methods whose complexity is above sonar.arch.cc.threshold");
  public static final Metric<Integer> METHOD_LINES_SUM = integer("arch_method_lines_sum", "Method code lines",
    "Sum of the code lines of all methods");
  public static final Metric<Integer> CLASS_LINES_MAX = integer("arch_class_lines_max", "Class size max",
    "Largest class in code lines, nested and inner classes excluded from the outer class");
  public static final Metric<Integer> CBO_MAX = integer("arch_cbo_max", "Coupling max",
    "Largest number of distinct classes one class references, library classes included");
  public static final Metric<Integer> DUP_LINES = integer("arch_dup_lines", "Duplicated code lines",
    "Code lines inside duplicated blocks of sonar.arch.dup.tokens tokens, first occurrence not counted");
  public static final Metric<Integer> MFS_REFS = integer("arch_mfs_refs", "Feedback arc set references",
    "References in a minimum feedback arc set, summed over all levels of the package tree");
  public static final Metric<Integer> CYCLE_REFS_TOTAL = integer("arch_cycle_refs_total", "References between sibling packages",
    "All references between sibling packages, summed over all levels");
  public static final Metric<Integer> CYCLE_NODES = integer("arch_cycle_nodes", "Packages in cycles",
    "Packages that are part of a cycle among their siblings, summed over all levels");
  public static final Metric<Integer> CYCLE_NODES_TOTAL = integer("arch_cycle_nodes_total", "Packages in the tree",
    "All packages, summed over all levels");
  public static final Metric<Integer> DEP_PARENTS = integer("arch_dep_parents", "Parent packages", "Packages with at least 2 sub-packages");
  public static final Metric<Integer> DEP_PARENTS_OVER = integer("arch_dep_parents_over", "Parent packages over the limits",
    "Parent packages whose sub-package graph has E - N + P above sonar.arch.dep.complexity and a fan-out above sonar.arch.dep.fanout");
  public static final Metric<Integer> DEP_FANOUT_MAX = integer("arch_dep_fanout_max", "Sub-package fan-out max",
    "Largest number of siblings one sub-package references within its parent");
  public static final Metric<Double> PKG_SIZE_MAX = new Metric.Builder("arch_pkg_size_max", "Package size max", Metric.ValueType.FLOAT)
    .setDescription("Largest product of own-file code lines and own-file methods over all packages").setDomain(ARCHITECTURE)
    .setQualitative(false).setDirection(Metric.DIRECTION_WORST).setDecimalScale(0).create();
  public static final Metric<Double> NESTING_TOPN_AVG = new Metric.Builder("arch_nesting_topn_avg", "Deepest nesting average",
    Metric.ValueType.FLOAT).setDescription("Average maximum nesting depth of the sonar.arch.nesting.topN deepest methods")
    .setDomain(ARCHITECTURE).setQualitative(false).setDirection(Metric.DIRECTION_WORST).setDecimalScale(2).create();
  public static final Metric<Integer> UNUSED_CLASSES = integer("arch_unused_classes", "Unused classes",
    "Private or package-private classes never referenced");
  public static final Metric<Integer> UNUSED_METHODS = integer("arch_unused_methods", "Unused methods",
    "Private or package-private methods never referenced");
  public static final Metric<Integer> UNUSED_VARIABLES = integer("arch_unused_variables", "Unused variables",
    "Private or package-private fields and local variables never referenced");
  public static final Metric<Integer> CLASSES = integer("arch_classes", "Classes", "All classes, nested ones included");
  public static final Metric<Integer> METHODS_TOTAL = integer("arch_methods_total", "Methods", "All methods, constructors excluded");
  public static final Metric<Integer> VARIABLES_TOTAL = integer("arch_variables_total", "Variables", "All fields and local variables");
  public static final Metric<Integer> METHODS_COVERED = new Metric.Builder("arch_methods_covered", "Covered methods", Metric.ValueType.INT)
    .setDescription("Methods executed by tests (JaCoCo METHOD counter)").setDomain(TESTS).setQualitative(false)
    .setDirection(Metric.DIRECTION_BETTER).create();
  public static final Metric<Integer> METHODS_COVERABLE = new Metric.Builder("arch_methods_coverable", "Coverable methods",
    Metric.ValueType.INT).setDescription("Methods known to JaCoCo").setDomain(TESTS).setQualitative(false)
    .setDirection(Metric.DIRECTION_NONE).create();
  public static final Metric<String> TOP_OFFENDERS = new Metric.Builder("arch_top_offenders", "Top offenders", Metric.ValueType.DATA)
    .setDescription("JSON: the 10 classes, methods and packages behind every maximum").setDomain(ARCHITECTURE).setQualitative(false)
    .create();

  /** Summed from files to directories and the project. */
  public static final List<Metric<?>> SUMMED = List.of(METHODS, CODE_LINES, CC_SUM, CC_OVER_COUNT, METHOD_LINES_SUM, METHODS_COVERED,
    METHODS_COVERABLE);
  /** Maximum of the files below. */
  public static final List<Metric<?>> MAXED = List.of(CC_MAX, CLASS_LINES_MAX, CBO_MAX);

  private static Metric<Integer> integer(String key, String name, String description) {
    return new Metric.Builder(key, name, Metric.ValueType.INT).setDescription(description).setDomain(ARCHITECTURE).setQualitative(false)
      .setDirection(Metric.DIRECTION_WORST).create();
  }

  /** The metric with this key. */
  public static Metric<?> byKey(String key) {
    for (Metric<?> m : new ArchMetrics().getMetrics()) {
      if (m.getKey().equals(key)) {
        return m;
      }
    }
    throw new IllegalArgumentException(key);
  }

  @Override
  public List<Metric> getMetrics() {
    return List.of(METHODS, CODE_LINES, CC_SUM, CC_MAX, CC_OVER_COUNT, METHOD_LINES_SUM, CLASS_LINES_MAX, CBO_MAX, DUP_LINES, MFS_REFS,
      CYCLE_REFS_TOTAL, CYCLE_NODES, CYCLE_NODES_TOTAL, DEP_PARENTS, DEP_PARENTS_OVER, DEP_FANOUT_MAX, PKG_SIZE_MAX, NESTING_TOPN_AVG,
      UNUSED_CLASSES, UNUSED_METHODS, UNUSED_VARIABLES, CLASSES, METHODS_TOTAL, VARIABLES_TOTAL, METHODS_COVERED, METHODS_COVERABLE,
      TOP_OFFENDERS);
  }
}
