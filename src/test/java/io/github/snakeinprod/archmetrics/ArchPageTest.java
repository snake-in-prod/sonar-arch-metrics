package io.github.snakeinprod.archmetrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ArchPageTest {

  @Test
  void page_shows_a_note_without_measures_na_for_missing_ones_and_python_apart() throws IOException {
    String js;
    try (InputStream in = getClass().getResourceAsStream("/static/architecture.js")) {
      assertThat(in).isNotNull();
      js = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertThat(js)
      .contains("values.arch_code_lines === undefined && values.arch_classes === undefined")
      .contains("No architecture metrics: this plugin measures Java and Python code.")
      .contains("'n/a'")
      .contains("if (hasPython)").contains("section(pyKeys, 'arch_py_top_offenders')")
      .contains("options.el.style.gridColumn = '1 / -1'");
    for (org.sonar.api.measures.Metric<?> m : new ArchMetrics().getMetrics()) {
      if (m.getKey().startsWith("arch_py_")) {
        assertThat(js).contains("'" + m.getKey() + "'");
      }
    }
  }
}
