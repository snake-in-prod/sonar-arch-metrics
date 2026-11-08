package io.github.snakeinprod.archmetrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ArchMetricsSensorTest {

  @Test
  void module_is_the_first_directory_below_the_base() {
    Path base = Path.of("/repo");
    assertThat(ArchMetricsSensor.module(base, Path.of("/repo/core/src/main/java/A.java"))).isEqualTo("core");
    assertThat(ArchMetricsSensor.module(base, Path.of("/repo/A.java"))).isNull();
    assertThat(ArchMetricsSensor.module(base, Path.of("/elsewhere/x/A.java"))).isNull();
  }
}
