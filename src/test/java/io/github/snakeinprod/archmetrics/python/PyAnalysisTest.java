package io.github.snakeinprod.archmetrics.python;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.snakeinprod.archmetrics.Analysis;
import io.github.snakeinprod.archmetrics.model.FileAnalyzer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** shop.orders and shop.billing import each other; util.py holds the function and private-name cases. */
class PyAnalysisTest {

  private static final Path PROJECT = Path.of("src/test/files/python-project");

  private static PyAnalysis.Result run(Analysis.Settings settings) throws IOException {
    List<PyAnalysis.Source> sources = new ArrayList<>();
    try (Stream<Path> s = Files.walk(PROJECT)) {
      for (Path f : s.filter(p -> p.toString().endsWith(".py")).toList()) {
        String path = PROJECT.relativize(f).toString().replace('\\', '/');
        sources.add(new PyAnalysis.Source(path, path, Files.readString(f)));
      }
    }
    return PyAnalysis.run(sources, settings);
  }

  @Test
  void file_metrics() throws IOException {
    Map<String, Object> util = run(Analysis.Settings.DEFAULT).files().get("src/shop/util.py");
    // docstrings and the assert are not code
    assertThat(util).containsEntry("arch_py_code_lines", 33).containsEntry("arch_py_functions", 5).containsEntry("arch_py_classes", 1)
      // visible: 1 + if, for, while, elif, comprehension for and if, conditional expression, except = 9; method: 1 + match
      .containsEntry("arch_py_cc_max", 9).containsEntry("arch_py_cc_sum", 9 + 1 + 1 + 1 + 2)
      // visible 13, _helper 2 (inner's lines are its own), inner 2, _dead 2, method 8
      .containsEntry("arch_py_function_lines_sum", 27).containsEntry("arch_py_function_lines_over_count", 0);
  }

  @Test
  void switch_cases_count_each_case_but_the_wildcard() throws IOException {
    Analysis.Settings d = Analysis.Settings.DEFAULT;
    Analysis.Settings cases = new Analysis.Settings(new FileAnalyzer.Settings(20, true), d.dupTokens(), d.graph(), d.nestingTopN(),
      d.methodLines(), d.classLines(), d.cbo(), d.pkgSize());
    assertThat(run(cases).files().get("src/shop/util.py")).containsEntry("arch_py_cc_sum", 9 + 1 + 1 + 1 + 3);
  }

  @Test
  void project_metrics() throws IOException {
    Map<String, Object> p = run(Analysis.Settings.DEFAULT).project();
    // orders/core.py -> billing/__init__.py (absolute, src layout), billing/invoice.py -> orders/core.py (relative)
    assertThat(p).containsEntry("arch_py_cycle_refs_total", 2).containsEntry("arch_py_mfs_refs", 1).containsEntry("arch_py_cycle_nodes", 2)
      // src; src.shop; src.shop.billing and src.shop.orders
      .containsEntry("arch_py_cycle_nodes_total", 4)
      // _CACHE, _unused_flag, _a, _b, _helper, _dead, _Hidden; _CACHE, _b (in an f-string) and _helper are used
      .containsEntry("arch_py_private_names", 7).containsEntry("arch_py_unused_names", 4)
      .containsEntry("arch_py_dup_lines", 0);
    // nesting: visible 3 (if, for, while), method 1 (match), the other five 0
    assertThat((Double) p.get("arch_py_nesting_topn_avg")).isEqualTo(4.0 / 7);
    assertThat((String) p.get("arch_py_top_offenders")).contains("\"name\":\"visible\"").contains("\"name\":\"_dead\"")
      .contains("src.shop.orders");
  }

  @Test
  void duplicated_blocks_count_the_second_copy() {
    String body = "def f(a, b):\n    if a:\n        return a + b * 2\n    return b - a\n";
    Analysis.Settings d = Analysis.Settings.DEFAULT;
    Analysis.Settings small = new Analysis.Settings(d.file(), 10, d.graph(), d.nestingTopN(), d.methodLines(), d.classLines(), d.cbo(),
      d.pkgSize());
    PyAnalysis.Result r = PyAnalysis.run(List.of(new PyAnalysis.Source("a.py", "a.py", body), new PyAnalysis.Source("b.py", "b.py",
      "x = 1\n" + body)), small);
    assertThat(r.project()).containsEntry("arch_py_dup_lines", 4);
  }

  @Test
  void branches_inside_f_string_fields() {
    assertThat(PyAnalysis.fStringBranches("f\"wait for {'on' if d else 'off'} {{if}} {[y for y in z]}\"")).isEqualTo(2);
    assertThat(PyAnalysis.fStringBranches("\"{x if y else z}\"")).isZero();
  }

  @Test
  void assignment_targets() {
    PyModule m = PyModule.of(TreeBuilder.build(PythonLexer.lex("a = b = 1\nc, (d, *e) = x\nf: int = 2\ng: str\nh += 1\nk == 1\n"
      + "l = lambda q=1: q\n").tokens()));
    List<String> names = m.statements().stream().flatMap(s -> PyAnalysis.targets(s.nodes()).stream()).map(Token::text).toList();
    assertThat(names).containsExactly("a", "b", "c", "d", "e", "f", "g", "l");
  }
}
