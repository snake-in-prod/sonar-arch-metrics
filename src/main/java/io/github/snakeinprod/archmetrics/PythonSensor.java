package io.github.snakeinprod.archmetrics;

import io.github.snakeinprod.archmetrics.python.PyAnalysis;
import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputComponent;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.measures.Metric;
import org.sonar.api.scanner.sensor.ProjectSensor;

/**
 * Measures the main Python files (see {@link PyAnalysis}) and saves the {@code arch_py_*} values: file metrics on the
 * files, set metrics on the project. Separate from {@link ArchMetricsSensor}: the Java {@code arch_*} values stay the
 * same whatever Python a project holds.
 */
public class PythonSensor implements ProjectSensor {

  private static final Logger LOG = LoggerFactory.getLogger(PythonSensor.class);

  @Override
  public void describe(SensorDescriptor descriptor) {
    descriptor.name("Architecture metrics for Python").onlyOnLanguage("py");
  }

  @Override
  public void execute(SensorContext context) {
    long start = System.currentTimeMillis();
    FileSystem fs = context.fileSystem();
    Path base = fs.baseDir().toPath().toAbsolutePath().normalize();
    Map<String, InputFile> byKey = new HashMap<>();
    List<PyAnalysis.Source> sources = new ArrayList<>();
    try {
      for (InputFile f : fs.inputFiles(fs.predicates().and(fs.predicates().hasLanguage("py"), fs.predicates().hasType(InputFile.Type.MAIN)))) {
        byKey.put(f.key(), f);
        String path = base.relativize(f.path().toAbsolutePath().normalize()).toString().replace('\\', '/');
        sources.add(new PyAnalysis.Source(f.key(), path, f.contents()));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    if (sources.isEmpty()) {
      return;
    }
    PyAnalysis.Result r = PyAnalysis.run(sources, ArchMetricsSensor.settings(context.config()));
    r.files().forEach((key, measures) -> measures.forEach((metric, value) -> save(context, byKey.get(key), metric, value)));
    r.project().forEach((metric, value) -> save(context, context.project(), metric, value));
    LOG.info("Architecture metrics for Python: {} files in {} ms", sources.size(), System.currentTimeMillis() - start);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static void save(SensorContext context, InputComponent on, String metricKey, Object value) {
    Metric metric = ArchMetrics.byKey(metricKey);
    Object v = metric.getType() == Metric.ValueType.FLOAT ? (Object) ((Number) value).doubleValue() : value;
    context.newMeasure().on(on).forMetric(metric).withValue((Serializable) v).save();
  }
}
