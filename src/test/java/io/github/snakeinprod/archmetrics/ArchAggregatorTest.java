package io.github.snakeinprod.archmetrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.sonar.api.ce.measure.Component;
import org.sonar.api.ce.measure.Measure;
import org.sonar.api.ce.measure.MeasureComputer.MeasureComputerContext;

class ArchAggregatorTest {

  private static final String CARRIED = "{\"core\": {\"arch_dup_lines\": 3, \"arch_nesting_topn_avg\": 0.5, "
    + "\"arch_top_offenders\": \"{}\"}}";

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, java.util.function.BiFunction<String, Object[], Object> answer) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, args) -> answer.apply(m.getName(), args));
  }

  private static Map<String, Object> run(String key) {
    Component component = proxy(Component.class, (name, args) -> switch (name) {
      case "getType" -> Component.Type.DIRECTORY;
      case "getKey" -> key;
      default -> null;
    });
    Measure carried = proxy(Measure.class, (name, args) -> CARRIED);
    Map<String, Object> added = new LinkedHashMap<>();
    MeasureComputerContext context = proxy(MeasureComputerContext.class, (name, args) -> switch (name) {
      case "getComponent" -> component;
      case "getChildrenMeasures" -> ArchMetrics.MODULE_MEASURES.getKey().equals(args[0]) ? List.of(carried) : List.of();
      case "addMeasure" -> added.put((String) args[0], args[1]);
      default -> null;
    });
    new ArchAggregator().compute(context);
    return added;
  }

  @Test
  void module_directory_gets_the_carried_values() {
    assertThat(run("proj:core")).containsExactly(Map.entry("arch_dup_lines", 3), Map.entry("arch_nesting_topn_avg", 0.5),
      Map.entry("arch_top_offenders", "{}"));
  }

  @Test
  void directories_below_pass_them_up() {
    Map<String, Object> added = run("proj:core/src/main");
    assertThat(added).containsOnlyKeys("arch_module_measures");
    assertThat((String) added.get("arch_module_measures")).contains("\"core\"").contains("arch_dup_lines");
  }

  @Test
  void python_file_values_are_summed_and_maxed_apart_from_java() {
    Component component = proxy(Component.class, (name, args) -> name.equals("getType") ? Component.Type.DIRECTORY : "proj:py");
    Measure three = proxy(Measure.class, (name, args) -> 3);
    Measure four = proxy(Measure.class, (name, args) -> 4);
    Map<String, Object> added = new LinkedHashMap<>();
    MeasureComputerContext context = proxy(MeasureComputerContext.class, (name, args) -> switch (name) {
      case "getComponent" -> component;
      case "getChildrenMeasures" -> ((String) args[0]).startsWith("arch_py_") ? List.of(three, four) : List.of();
      case "addMeasure" -> added.put((String) args[0], args[1]);
      default -> null;
    });
    new ArchAggregator().compute(context);
    assertThat(added).containsEntry("arch_py_code_lines", 7).containsEntry("arch_py_cc_max", 4).allSatisfy((k, v) ->
      assertThat(k).startsWith("arch_py_"));
    assertThat(added).hasSize(ArchMetrics.PY_SUMMED.size() + ArchMetrics.PY_MAXED.size());
  }
}
