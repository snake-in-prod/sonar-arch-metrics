package io.github.snakeinprod.archmetrics;

import org.sonar.api.Plugin;
import org.sonar.api.PropertyType;
import org.sonar.api.config.PropertyDefinition;

public class ArchMetricsPlugin implements Plugin {

  public static final String CC_THRESHOLD = "sonar.arch.cc.threshold";
  public static final String CC_SWITCH_CASES = "sonar.arch.cc.switchCases";
  public static final String DUP_TOKENS = "sonar.arch.dup.tokens";
  public static final String DEP_COMPLEXITY = "sonar.arch.dep.complexity";
  public static final String DEP_FANOUT = "sonar.arch.dep.fanout";
  public static final String NESTING_TOPN = "sonar.arch.nesting.topN";
  public static final String METHOD_LINES = "sonar.arch.method.lines.threshold";
  public static final String CLASS_LINES = "sonar.arch.class.lines.threshold";
  public static final String CBO_THRESHOLD = "sonar.arch.cbo.threshold";
  public static final String PKG_SIZE = "sonar.arch.pkg.size.threshold";

  @Override
  public void define(Context context) {
    context.addExtensions(ArchMetrics.class, ArchMetricsSensor.class, PythonSensor.class, ArchAggregator.class, ArchPage.class,
      prop(CC_THRESHOLD, "Complexity threshold", "Methods with a complexity above this count in arch_cc_over_count.", "20", PropertyType.INTEGER),
      prop(CC_SWITCH_CASES, "Count switch cases", "Count each case of a switch in the method complexity instead of 1 per switch.", "false",
        PropertyType.BOOLEAN),
      prop(DUP_TOKENS, "Duplication window", "Smallest number of identical tokens in a row that counts as a duplicated block.", "50",
        PropertyType.INTEGER),
      prop(DEP_COMPLEXITY, "Dependency complexity limit", "A parent package counts in arch_dep_parents_over when E - N + P of its "
        + "sub-package graph is above this...", "12", PropertyType.INTEGER),
      prop(DEP_FANOUT, "Dependency fan-out limit", "...and the largest sub-package fan-out is above this.", "9", PropertyType.INTEGER),
      prop(NESTING_TOPN, "Deepest methods", "How many of the most deeply nested methods arch_nesting_topn_avg averages.", "10",
        PropertyType.INTEGER),
      prop(METHOD_LINES, "Method size threshold", "Methods with more code lines count in arch_method_lines_over_count.", "20",
        PropertyType.INTEGER),
      prop(CLASS_LINES, "Class size threshold", "Classes with more code lines count in arch_class_lines_over_count.", "1000",
        PropertyType.INTEGER),
      prop(CBO_THRESHOLD, "Coupling threshold", "Classes referencing more distinct classes count in arch_cbo_over_count.", "100",
        PropertyType.INTEGER),
      prop(PKG_SIZE, "Package size threshold", "Packages with a larger size (code lines x methods) count in arch_pkg_size_over_count.",
        "27000000", PropertyType.FLOAT));
  }

  private static PropertyDefinition prop(String key, String name, String description, String defaultValue, PropertyType type) {
    return PropertyDefinition.builder(key).name(name).description(description).defaultValue(defaultValue).type(type)
      .category("Architecture Metrics").onConfigScopes(PropertyDefinition.ConfigScope.PROJECT).build();
  }
}
