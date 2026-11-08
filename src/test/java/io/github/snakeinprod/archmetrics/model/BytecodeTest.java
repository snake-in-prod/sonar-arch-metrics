package io.github.snakeinprod.archmetrics.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BytecodeTest {

  @TempDir
  Path dir;

  @Test
  void accesses_to_mutable_static_fields_per_method() throws Exception {
    Path src = dir.resolve("G.java");
    Files.writeString(src, """
      package g;
      public class G {
        static int counter;
        static String name = "x";
        static final Object LOCK = new Object();
        static { counter = 1; }
        int bump() { counter++; return counter + LOCK.hashCode(); }
        static void reset() { counter = 0; name = null; G.counter = 0; }
      }
      """);
    assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", dir.toString(), src.toString())).isZero();
    Map<String, Bytecode.ClassRefs> classes = Bytecode.read(Set.of(dir));
    Bytecode.ClassRefs g = classes.get("g/G");
    assertThat(g.mutableStatics()).containsExactlyInAnyOrder("counter", "name");
    // bump: counter; reset: counter, name; the static initialiser does not count
    assertThat(Bytecode.globalRefs(g, classes)).isEqualTo(3);
  }
}
