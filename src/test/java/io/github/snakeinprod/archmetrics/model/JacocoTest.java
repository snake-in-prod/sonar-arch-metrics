package io.github.snakeinprod.archmetrics.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JacocoTest {

  @TempDir
  Path dir;

  private Path report(String name, boolean secondCovered) throws Exception {
    String xml = """
      <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
      <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
      <report name="r">
        <package name="com/acme">
          <class name="com/acme/Foo" sourcefilename="Foo.java">
            <method name="a" desc="()V" line="3"><counter type="METHOD" missed="0" covered="1"/></method>
            <method name="b" desc="()V" line="7"><counter type="METHOD" missed="%d" covered="%d"/></method>
            <counter type="METHOD" missed="1" covered="1"/>
          </class>
          <class name="com/acme/Foo$Inner" sourcefilename="Foo.java">
            <method name="c" desc="()V" line="12"><counter type="METHOD" missed="1" covered="0"/></method>
          </class>
        </package>
      </report>
      """.formatted(secondCovered ? 0 : 1, secondCovered ? 1 : 0);
    Path p = dir.resolve(name);
    Files.writeString(p, xml);
    return p;
  }

  @Test
  void methods_merge_over_reports_by_signature() throws Exception {
    Map<String, Jacoco.MethodCoverage> one = Jacoco.read(List.of(report("unit.xml", false)));
    assertThat(one.get("com/acme/Foo.java")).isEqualTo(new Jacoco.MethodCoverage(1, 3));
    Map<String, Jacoco.MethodCoverage> both = Jacoco.read(List.of(report("unit.xml", false), report("it.xml", true)));
    assertThat(both.get("com/acme/Foo.java")).isEqualTo(new Jacoco.MethodCoverage(2, 3));
  }
}
