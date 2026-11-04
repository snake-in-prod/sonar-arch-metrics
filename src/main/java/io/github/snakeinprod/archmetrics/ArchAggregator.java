package io.github.snakeinprod.archmetrics;

import org.sonar.api.ce.measure.Component;
import org.sonar.api.ce.measure.Measure;
import org.sonar.api.ce.measure.MeasureComputer;
import org.sonar.api.measures.Metric;

/** Adds file values up to directories and the project: sums, and maxima. Files keep what the sensor saved. */
public class ArchAggregator implements MeasureComputer {

  @Override
  public MeasureComputerDefinition define(MeasureComputerDefinitionContext context) {
    String[] keys = java.util.stream.Stream.concat(ArchMetrics.SUMMED.stream(), ArchMetrics.MAXED.stream()).map(Metric::getKey)
      .toArray(String[]::new);
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
