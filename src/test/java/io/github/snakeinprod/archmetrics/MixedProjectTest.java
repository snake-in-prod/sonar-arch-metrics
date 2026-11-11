package io.github.snakeinprod.archmetrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sonar.api.batch.fs.FilePredicate;
import org.sonar.api.batch.fs.FilePredicates;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputComponent;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.scanner.fs.InputProject;
import org.sonar.api.batch.measure.Metric;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.config.Configuration;

/** Python files next to Java ones leave every Java arch_* value as it is; Python gets only arch_py_* keys. */
class MixedProjectTest {

  @TempDir
  Path base;

  @Test
  void java_values_are_the_same_with_and_without_python() throws IOException {
    Path java = Path.of("src/test/files/project/src/main/java");
    Path python = Path.of("src/test/files/python-project/src");
    List<Path> javaFiles = copy(java, base.resolve("src/main/java"), ".java");
    List<Path> pyFiles = copy(python, base.resolve("src/main/python"), ".py");
    List<String> args = new ArrayList<>(List.of("-g", "-d", base.resolve("target/classes").toString()));
    javaFiles.forEach(f -> args.add(f.toString()));
    assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new))).isZero();

    Map<String, Object> javaOnly = new TreeMap<>();
    SensorContext alone = context(javaFiles, javaOnly);
    new ArchMetricsSensor().execute(alone);
    new PythonSensor().execute(alone);

    List<Path> all = new ArrayList<>(javaFiles);
    all.addAll(pyFiles);
    Map<String, Object> mixed = new TreeMap<>();
    SensorContext both = context(all, mixed);
    new ArchMetricsSensor().execute(both);
    new PythonSensor().execute(both);

    Map<String, Object> mixedJava = new TreeMap<>(mixed);
    mixedJava.keySet().removeIf(k -> k.contains("|arch_py_"));
    assertThat(javaOnly).isNotEmpty().containsKey("proj|arch_mfs_refs").doesNotContainKey("proj|arch_py_code_lines");
    assertThat(mixedJava).isEqualTo(javaOnly);
    // the Python sensor saves only arch_py_* keys, and only on the project and on Python files
    Map<String, Object> py = new TreeMap<>(mixed);
    py.keySet().removeAll(javaOnly.keySet());
    assertThat(py).isNotEmpty().allSatisfy((k, v) -> assertThat(k).contains("|arch_py_").doesNotContain(".java|"));
    assertThat(py).containsEntry("proj|arch_py_cycle_nodes", 2).containsEntry("src/main/python/shop/util.py|arch_py_functions", 5);
  }

  private static List<Path> copy(Path from, Path to, String ext) throws IOException {
    List<Path> out = new ArrayList<>();
    try (Stream<Path> s = Files.walk(from)) {
      for (Path f : s.filter(p -> p.toString().endsWith(ext)).toList()) {
        Path t = to.resolve(from.relativize(f).toString());
        Files.createDirectories(t.getParent());
        out.add(Files.copy(f, t));
      }
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, java.util.function.BiFunction<String, Object[], Object> answer) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, args) -> answer.apply(m.getName(), args));
  }

  private InputFile input(Path f) {
    String key = base.relativize(f).toString().replace('\\', '/');
    String language = key.endsWith(".py") ? "py" : "java";
    return proxy(InputFile.class, (name, args) -> switch (name) {
      case "key", "toString" -> key;
      case "filename" -> f.getFileName().toString();
      case "path" -> f;
      case "language" -> language;
      case "type" -> InputFile.Type.MAIN;
      case "contents" -> read(f);
      case "hashCode" -> key.hashCode();
      case "equals" -> args[0] instanceof InputFile o && o.key().equals(key);
      default -> null;
    });
  }

  private static String read(Path f) {
    try {
      return Files.readString(f, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  /** A sensor context over these files; saved measures land in {@code saved} as "component|metric". */
  private SensorContext context(List<Path> files, Map<String, Object> saved) {
    List<InputFile> inputs = files.stream().map(this::input).toList();
    FilePredicates predicates = proxy(FilePredicates.class, (name, args) -> switch (name) {
      case "hasLanguage" -> (FilePredicate) f -> f.language().equals(args[0]);
      case "hasType" -> (FilePredicate) f -> f.type() == args[0];
      case "and" -> (FilePredicate) f -> ((FilePredicate) args[0]).apply(f) && ((FilePredicate) args[1]).apply(f);
      default -> throw new UnsupportedOperationException(name);
    });
    FileSystem fs = proxy(FileSystem.class, (name, args) -> switch (name) {
      case "baseDir" -> base.toFile();
      case "predicates" -> predicates;
      case "inputFiles" -> inputs.stream().filter(((FilePredicate) args[0])::apply).toList();
      default -> throw new UnsupportedOperationException(name);
    });
    Configuration config = proxy(Configuration.class, (name, args) -> switch (name) {
      case "getStringArray" -> new String[0];
      case "hasKey" -> false;
      default -> Optional.empty();
    });
    InputProject project = proxy(InputProject.class, (name, args) -> "key".equals(name) ? "proj" : null);
    return proxy(SensorContext.class, (name, args) -> switch (name) {
      case "config" -> config;
      case "fileSystem" -> fs;
      case "project" -> project;
      case "newMeasure" -> measure(saved);
      default -> throw new UnsupportedOperationException(name);
    });
  }

  private static Object measure(Map<String, Object> saved) {
    Object[] state = new Object[3];
    Object[] self = new Object[1];
    self[0] = proxy(org.sonar.api.batch.sensor.measure.NewMeasure.class, (name, args) -> {
      switch (name) {
        case "on" -> state[0] = ((InputComponent) args[0]).key();
        case "forMetric" -> state[1] = ((Metric<?>) args[0]).key();
        case "withValue" -> state[2] = args[0];
        case "save" -> {
          Object previous = saved.put(state[0] + "|" + state[1], state[2]);
          assertThat(previous).as("saved twice: " + state[0] + "|" + state[1]).isNull();
          return null;
        }
        default -> throw new UnsupportedOperationException(name);
      }
      return self[0];
    });
    return self[0];
  }
}
