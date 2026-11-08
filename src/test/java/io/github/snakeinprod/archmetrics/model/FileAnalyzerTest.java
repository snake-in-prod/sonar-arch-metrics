package io.github.snakeinprod.archmetrics.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class FileAnalyzerTest {

  static FileStats analyze(String file, FileAnalyzer.Settings settings) throws Exception {
    String src = Files.readString(Path.of("src/test/files/metrics/" + file));
    return new FileAnalyzer(settings).analyze(file, src, Tokens.of(src));
  }

  private static Map<String, FileStats.MethodInfo> byName(FileStats s) {
    return s.methodInfos().stream().collect(Collectors.toMap(m -> m.name().substring(m.name().indexOf('#') + 1), m -> m, (a, b) -> a));
  }

  @Test
  void complexity_counts_branches_not_boolean_operators() throws Exception {
    FileStats s = analyze("Complexity.java", FileAnalyzer.Settings.DEFAULT);
    Map<String, FileStats.MethodInfo> m = byName(s);
    assertThat(m.get("plain").complexity()).isEqualTo(1);
    assertThat(m.get("branches").complexity()).isEqualTo(11);
    assertThat(m.get("switchExpr").complexity()).isEqualTo(2);
    assertThat(m.get("anon").complexity()).isEqualTo(1);
    assertThat(m.get("run").complexity()).isEqualTo(2);
    assertThat(m.get("deep").complexity()).isEqualTo(6);
    assertThat(s.methods()).isEqualTo(6);
    assertThat(s.ccSum()).isEqualTo(1 + 11 + 2 + 1 + 2 + 6);
    assertThat(s.ccMax()).isEqualTo(11);
    assertThat(s.ccOver()).isZero();
    assertThat(analyze("Complexity.java", new FileAnalyzer.Settings(10, false)).ccOver()).isEqualTo(1);
  }

  @Test
  void switch_cases_setting_counts_each_case() throws Exception {
    Map<String, FileStats.MethodInfo> m = byName(analyze("Complexity.java", new FileAnalyzer.Settings(20, true)));
    assertThat(m.get("branches").complexity()).isEqualTo(12);
    assertThat(m.get("switchExpr").complexity()).isEqualTo(3);
  }

  @Test
  void nesting_keeps_else_if_at_its_level() throws Exception {
    Map<String, FileStats.MethodInfo> m = byName(analyze("Complexity.java", FileAnalyzer.Settings.DEFAULT));
    assertThat(m.get("deep").nesting()).isEqualTo(4);
    assertThat(m.get("plain").nesting()).isZero();
    assertThat(m.get("branches").nesting()).isEqualTo(1);
  }

  @Test
  void code_lines_without_comments_blanks_and_asserts() throws Exception {
    FileStats s = analyze("Lines.java", FileAnalyzer.Settings.DEFAULT);
    assertThat(s.codeLines()).containsExactlyInAnyOrder(1, 6, 8, 12, 13, 15, 16, 17, 18, 19, 20);
    assertThat(s.methodLinesSum()).isEqualTo(6);
    assertThat(s.classInfos()).extracting(FileStats.ClassInfo::lines).containsExactlyInAnyOrder(5, 5);
    assertThat(s.classLinesMax()).isEqualTo(5);
  }

  @Test
  void records_enums_local_classes_and_generated_members() throws Exception {
    FileStats s = analyze("Edge.java", FileAnalyzer.Settings.DEFAULT);
    assertThat(s.methods()).isEqualTo(6);
    assertThat(byName(s)).doesNotContainKey("generated");
    // Edge, Point, Kind, Shape, Local
    assertThat(s.classesTotal()).isEqualTo(5);
  }

  @Test
  void generated_files_are_left_out() throws Exception {
    FileStats s = analyze("GeneratedFile.java", FileAnalyzer.Settings.DEFAULT);
    assertThat(s.generated()).isTrue();
    assertThat(s.methods()).isZero();
  }

  @Test
  void halstead_volume_and_difficulty() {
    // operators return + ; (n1 3, N1 3), operands a b (n2 2, N2 2)
    double[] h = FileAnalyzer.halstead(java.util.List.of("return", "a", "+", "b", ";"));
    assertThat(h[0]).isCloseTo(5 * Math.log(5) / Math.log(2), org.assertj.core.data.Offset.offset(1e-9));
    assertThat(h[1]).isEqualTo(1.5);
  }
}
