package io.github.snakeinprod.archmetrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The whole analysis on a small project: packages app.a and app.b reference each other, app.u holds unused items. */
class AnalysisTest {

  private static final Path PROJECT = Path.of("src/test/files/project");

  @TempDir
  Path classes;

  private Analysis.Result result;

  @BeforeEach
  void analyse() throws Exception {
    List<Path> files = new ArrayList<>();
    try (Stream<Path> s = Files.walk(PROJECT.resolve("src/main/java"))) {
      s.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
    }
    List<String> args = new ArrayList<>(List.of("-g", "-d", classes.toString()));
    files.forEach(f -> args.add(f.toString()));
    assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new))).isZero();
    List<Analysis.Source> sources = new ArrayList<>();
    for (Path f : files) {
      String key = PROJECT.relativize(f).toString().replace('\\', '/');
      String module = f.getParent().getFileName().toString();
      sources.add(new Analysis.Source(key, f.getFileName().toString(), Files.readString(f), module));
    }
    result = Analysis.run(sources, Set.of(classes), Set.of(PROJECT.resolve("reports/jacoco.xml")), Analysis.Settings.DEFAULT);
  }

  private Object project(String metric) {
    return result.project().get(metric);
  }

  private Object file(String path, String metric) {
    return result.files().get("src/main/java/app/" + path).get(metric);
  }

  @Test
  void file_metrics() {
    assertThat(file("a/A.java", "arch_methods")).isEqualTo(2);
    assertThat(file("a/A.java", "arch_cc_max")).isEqualTo(1);
    assertThat(file("a/A.java", "arch_code_lines")).isEqualTo(10);
    // B and java.lang.Object (the super constructor call)
    assertThat(file("a/A.java", "arch_cbo_max")).isEqualTo(2);
  }

  @Test
  void package_cycle_between_a_and_b() {
    // A -> B: new B(), value(); B -> A: new A(), value()
    assertThat(project("arch_cycle_refs_total")).isEqualTo(4);
    assertThat(project("arch_mfs_refs")).isEqualTo(2);
    assertThat(project("arch_cycle_nodes")).isEqualTo(2);
    // root level: app; level app: a, b, u
    assertThat(project("arch_cycle_nodes_total")).isEqualTo(4);
    assertThat(project("arch_dep_parents")).isEqualTo(1);
    assertThat(project("arch_dep_fanout_max")).isEqualTo(1);
    assertThat(result.compiled()).isEqualTo(6);
  }

  @Test
  void unused_items_and_totals() {
    assertThat(project("arch_unused_classes")).isEqualTo(1);
    assertThat(project("arch_unused_methods")).isEqualTo(2);
    assertThat(project("arch_unused_variables")).isEqualTo(3);
    assertThat(project("arch_classes")).isEqualTo(6);
    assertThat(project("arch_methods_total")).isEqualTo(11);
    assertThat(project("arch_variables_total")).isEqualTo(8);
  }

  @Test
  void project_wide_values() {
    assertThat(project("arch_dup_lines")).isEqualTo(0);
    // api() has a for-each (1), the 10 others 0: the 10 deepest average 0.1
    assertThat((Double) project("arch_nesting_topn_avg")).isEqualTo(0.1);
    // u: 32 + 7 code lines x 7 methods (6 in Unused, 1 in Other)
    assertThat((Double) project("arch_pkg_size_max")).isEqualTo(39.0 * 7);
    assertThat((String) project("arch_top_offenders")).contains("arch_cc_max").contains("app.u.Unused");
  }

  @Test
  void class_cycle_between_a_and_b() {
    // A <-> B (2 references each way), Other -> Unused 2; Unused's nested classes count as Unused
    assertThat(project("arch_class_nodes")).isEqualTo(4);
    assertThat(project("arch_class_refs_total")).isEqualTo(6);
    assertThat(project("arch_class_cycle_nodes")).isEqualTo(2);
    assertThat(project("arch_class_mfs_refs")).isEqualTo(2);
    assertThat((String) project("arch_top_offenders")).contains("arch_class_cycle_nodes").contains("app.a.A");
  }

  @Test
  void new_file_counts_are_zero_on_small_code() {
    assertThat(file("a/A.java", "arch_method_lines_over_count")).isEqualTo(0);
    assertThat(file("a/A.java", "arch_class_lines_over_count")).isEqualTo(0);
    assertThat(file("a/A.java", "arch_halstead_over_count")).isEqualTo(0);
    assertThat(file("a/A.java", "arch_cbo_over_count")).isEqualTo(0);
    assertThat(file("a/A.java", "arch_global_refs")).isEqualTo(0);
    assertThat(project("arch_pkg_size_over_count")).isEqualTo(0);
    assertThat((Double) project("arch_halstead_effort_avg")).isPositive();
  }

  @Test
  void module_values_cover_their_own_files_only() {
    assertThat(result.modules()).containsOnlyKeys("a", "b", "u");
    Map<String, Object> u = result.modules().get("u");
    assertThat(u).containsKeys(ArchMetrics.SCOPED.stream().map(m -> m.getKey()).toArray(String[]::new));
    assertThat(u.get("arch_unused_classes")).isEqualTo(1);
    assertThat(u.get("arch_classes")).isEqualTo(4);
    assertThat((Double) u.get("arch_pkg_size_max")).isEqualTo(39.0 * 7);
    assertThat((String) u.get("arch_top_offenders")).contains("app.u.Unused").doesNotContain("app.a.A");
    // a cycle needs both packages: none inside one module
    Map<String, Object> a = result.modules().get("a");
    assertThat(a.get("arch_cycle_nodes")).isEqualTo(0);
    assertThat(a.get("arch_class_cycle_nodes")).isEqualTo(0);
    assertThat(a.get("arch_class_nodes")).isEqualTo(1);
    assertThat(a.get("arch_classes")).isEqualTo(1);
  }

  @Test
  void method_coverage_from_jacoco() {
    assertThat(file("a/A.java", "arch_methods_covered")).isEqualTo(2);
    assertThat(file("a/A.java", "arch_methods_coverable")).isEqualTo(3);
  }
}
