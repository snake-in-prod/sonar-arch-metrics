package io.github.snakeinprod.archmetrics;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.sonar.api.ce.measure.Component;
import org.sonar.api.ce.measure.Measure;
import org.sonar.api.ce.measure.MeasureComputer;
import org.sonar.api.measures.Metric;

/**
 * Adds file values up to directories and the project: sums, and maxima. Files keep what the sensor saved. Module
 * values ride up from their carrier file in {@link ArchMetrics#MODULE_MEASURES} until the directory {@code <project>:<module>}.
 */
public class ArchAggregator implements MeasureComputer {

  private static final Gson GSON = new Gson();
  private static final Type MODULES = new TypeToken<Map<String, Map<String, Object>>>() {
  }.getType();

  @Override
  public MeasureComputerDefinition define(MeasureComputerDefinitionContext context) {
    String[] keys = Stream.of(ArchMetrics.SUMMED, ArchMetrics.MAXED, ArchMetrics.SCOPED, List.of(ArchMetrics.MODULE_MEASURES),
        ArchMetrics.PY_SUMMED, ArchMetrics.PY_MAXED)
      .flatMap(List::stream).map(Metric::getKey).toArray(String[]::new);
    return context.newDefinitionBuilder().setOutputMetrics(keys).build();
  }

  @Override
  public void compute(MeasureComputerContext context) {
    if (context.getComponent().getType() == Component.Type.FILE) {
      return;
    }
    for (Metric<?> m : ArchMetrics.SUMMED) {
      aggregate(context, m.getKey(), false);
    }
    for (Metric<?> m : ArchMetrics.MAXED) {
      aggregate(context, m.getKey(), true);
    }
    for (Metric<?> m : ArchMetrics.PY_SUMMED) {
      aggregate(context, m.getKey(), false);
    }
    for (Metric<?> m : ArchMetrics.PY_MAXED) {
      aggregate(context, m.getKey(), true);
    }
    if (context.getComponent().getType() == Component.Type.DIRECTORY) {
      modules(context);
    }
  }

  private static void modules(MeasureComputerContext context) {
    Map<String, Map<String, Object>> modules = new TreeMap<>();
    for (Measure child : context.getChildrenMeasures(ArchMetrics.MODULE_MEASURES.getKey())) {
      modules.putAll(GSON.fromJson(child.getStringValue(), MODULES));
    }
    if (modules.isEmpty()) {
      return;
    }
    String key = context.getComponent().getKey();
    for (Map.Entry<String, Map<String, Object>> m : modules.entrySet()) {
      if (key.endsWith(":" + m.getKey())) {
        m.getValue().forEach((metric, value) -> add(context, metric, value));
        return;
      }
    }
    context.addMeasure(ArchMetrics.MODULE_MEASURES.getKey(), GSON.toJson(modules));
  }

  private static void add(MeasureComputerContext context, String key, Object value) {
    switch (ArchMetrics.byKey(key).getType()) {
      case INT -> context.addMeasure(key, ((Number) value).intValue());
      case FLOAT -> context.addMeasure(key, ((Number) value).doubleValue());
      default -> context.addMeasure(key, (String) value);
    }
  }

  private static void aggregate(MeasureComputerContext context, String key, boolean max) {
    int value = 0;
    boolean any = false;
    for (Measure child : context.getChildrenMeasures(key)) {
      any = true;
      value = max ? Math.max(value, child.getIntValue()) : value + child.getIntValue();
    }
    if (any) {
      context.addMeasure(key, value);
    }
  }
}
