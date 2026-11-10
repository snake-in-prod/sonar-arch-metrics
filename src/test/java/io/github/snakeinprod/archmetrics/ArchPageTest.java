package io.github.snakeinprod.archmetrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ArchPageTest {

  @Test
  void page_shows_a_note_without_java_measures_and_na_for_missing_ones() throws IOException {
    String js;
    try (InputStream in = getClass().getResourceAsStream("/static/architecture.js")) {
      assertThat(in).isNotNull();
      js = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertThat(js)
      .contains("values.arch_code_lines === undefined && values.arch_classes === undefined")
      .contains("No architecture metrics: this plugin measures Java code.")
      .contains("'n/a'");
  }
}
