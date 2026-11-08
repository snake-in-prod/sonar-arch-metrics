package io.github.snakeinprod.archmetrics;

import com.google.gson.Gson;
import io.github.snakeinprod.archmetrics.model.FileAnalyzer;
import io.github.snakeinprod.archmetrics.model.PackageGraph;
import java.io.IOException;
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
import org.sonar.api.config.Configuration;
import org.sonar.api.measures.Metric;
import org.sonar.api.scanner.sensor.ProjectSensor;

/**
 * Measures the main Java files of the whole project once (see {@link Analysis}) and saves the values: file metrics on
 * the files, project metrics on the project, module metrics as JSON on the module's first file.
 * {@link ArchAggregator} adds file values up to directories and the project and puts module values on their directory.
 */
public class ArchMetricsSensor implements ProjectSensor {

  private static final Logger LOG = LoggerFactory.getLogger(ArchMetricsSensor.class);

  @Override
  public void describe(SensorDescriptor descriptor) {
    descriptor.name("Architecture metrics").onlyOnLanguage("java");
  }

  @Override
  public void execute(SensorContext context) {
    long start = System.currentTimeMillis();
    Configuration config = context.config();
    FileSystem fs = context.fileSystem();
    Map<String, InputFile> byKey = new HashMap<>();
    Map<String, InputFile> carrier = new HashMap<>();
    List<Analysis.Source> sources = new ArrayList<>();
    Path base = fs.baseDir().toPath();
    try {
      for (InputFile f : fs.inputFiles(fs.predicates().and(fs.predicates().hasLanguage("java"), fs.predicates().hasType(InputFile.Type.MAIN)))) {
        byKey.put(f.key(), f);
        String module = module(base, f.path());
        if (module != null) {
          carrier.merge(module, f, (a, b) -> a.key().compareTo(b.key()) <= 0 ? a : b);
        }
        sources.add(new Analysis.Source(f.key(), f.filename(), f.contents(), module));
      }
      Analysis.Result r = Analysis.run(sources, Paths.binaries(base, config), Paths.jacocoReports(base, config), settings(config));
      r.files().forEach((key, measures) -> measures.forEach((metric, value) -> save(context, byKey.get(key), metric, value)));
      r.project().forEach((metric, value) -> save(context, context.project(), metric, value));
      // sensors cannot save on directories
      r.modules().forEach((module, measures) -> save(context, carrier.get(module), ArchMetrics.MODULE_MEASURES.getKey(),
        new Gson().toJson(Map.of(module, measures))));
      if (r.compiled() == 0) {
        LOG.warn("Architecture metrics: no compiled classes found for the main sources (sonar.java.binaries); coupling and package "
          + "metrics are not computed");
      }
      LOG.info("Architecture metrics: {} files, {} classes in {} ms", sources.size(), r.compiled(), System.currentTimeMillis() - start);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** First directory of the file below the base directory, or null for a file directly in it. */
  static String module(Path base, Path file) {
    Path rel = base.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize());
    return rel.getNameCount() > 1 && !rel.getName(0).toString().equals("..") ? rel.getName(0).toString() : null;
  }

  static Analysis.Settings settings(Configuration config) {
    return new Analysis.Settings(
      new FileAnalyzer.Settings(config.getInt(ArchMetricsPlugin.CC_THRESHOLD).orElse(20), config.getBoolean(ArchMetricsPlugin.CC_SWITCH_CASES).orElse(false)),
      config.getInt(ArchMetricsPlugin.DUP_TOKENS).orElse(50),
      new PackageGraph.Settings(config.getInt(ArchMetricsPlugin.DEP_COMPLEXITY).orElse(12), config.getInt(ArchMetricsPlugin.DEP_FANOUT).orElse(9)),
      config.getInt(ArchMetricsPlugin.NESTING_TOPN).orElse(10), config.getInt(ArchMetricsPlugin.METHOD_LINES).orElse(20),
      config.getInt(ArchMetricsPlugin.CLASS_LINES).orElse(1000), config.getInt(ArchMetricsPlugin.CBO_THRESHOLD).orElse(100),
      config.getDouble(ArchMetricsPlugin.PKG_SIZE).orElse(27_000_000.0));
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static void save(SensorContext context, InputComponent on, String metricKey, Object value) {
    Metric metric = ArchMetrics.byKey(metricKey);
    Object v = metric.getType() == Metric.ValueType.FLOAT ? (Object) ((Number) value).doubleValue() : value;
    context.newMeasure().on(on).forMetric(metric).withValue((java.io.Serializable) v).save();
  }
}
