package io.github.snakeinprod.archmetrics.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DuplicationTest {

  private static final String BLOCK = """
        int total = 0;
        for (int i = 0; i < values.length; i++) {
          total += values[i] * weights[i] + offset;
        }
        return total / Math.max(1, values.length - skipped);
    """;

  @Test
  void every_occurrence_after_the_first_counts_its_lines() {
    String a = "class A {\n  int a(int[] values, int[] weights, int offset, int skipped) {\n" + BLOCK + "  }\n}\n";
    String b = "class B {\n  int b(int[] values, int[] weights, int offset, int skipped) {\n" + BLOCK + "  }\n}\n";
    List<Tokens.Token> ta = Tokens.of(a);
    List<Tokens.Token> tb = Tokens.of(b);
    // B: "(int[] values ... skipped) {" on line 2, the block on lines 3-7, the closing braces on 8 and 9; A is the first occurrence
    int lines = Duplication.duplicatedLines(List.of(ta, tb), List.of(FileAnalyzer.codeLines(ta, null), FileAnalyzer.codeLines(tb, null)), 50);
    assertThat(lines).isEqualTo(8);
  }

  @Test
  void shorter_runs_are_not_duplicates() {
    String a = "class A { int a() { return 1 + 2; } }";
    String b = "class B { int b() { return 1 + 2; } }";
    assertThat(Duplication.duplicatedLines(List.of(Tokens.of(a), Tokens.of(b)),
      List.of(FileAnalyzer.codeLines(Tokens.of(a), null), FileAnalyzer.codeLines(Tokens.of(b), null)), 50)).isZero();
  }

  @Test
  void a_block_repeated_in_one_file_counts_once() {
    String a = "class A {\n  int a(int[] values, int[] weights, int offset, int skipped) {\n" + BLOCK + "  }\n"
      + "  int c(int[] values, int[] weights, int offset, int skipped) {\n" + BLOCK + "  }\n}\n";
    List<Tokens.Token> t = Tokens.of(a);
    // the second method: its signature line (from "(") 9, block 10-14, its closing brace 15
    assertThat(Duplication.duplicatedLines(List.of(t), List.of(FileAnalyzer.codeLines(t, null)), 50)).isEqualTo(7);
  }
}
