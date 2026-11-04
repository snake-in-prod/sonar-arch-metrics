package io.github.snakeinprod.archmetrics.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * References between classes read from compiled classes: method calls and field reads and writes. Lambdas and method
 * references count as calls of their target.
 */
public final class Bytecode {

  private Bytecode() {
  }

  /**
   * @param name       internal name ({@code com/acme/Foo$Bar})
   * @param sourcePath {@code com/acme/Foo.java}
   * @param references referenced class to the number of occurrences (the class itself excluded)
   */
  public record ClassRefs(String name, String sourcePath, boolean generated, Map<String, Integer> references) {
    public String packageName() {
      int slash = name.lastIndexOf('/');
      return slash < 0 ? "" : name.substring(0, slash).replace('/', '.');
    }
  }

  /** Every class under the given directories (jars are libraries, not read). */
  public static Map<String, ClassRefs> read(Collection<Path> roots) throws IOException {
    Map<String, ClassRefs> out = new HashMap<>();
    for (Path root : roots) {
      if (!Files.isDirectory(root)) {
        continue;
      }
      try (Stream<Path> s = Files.walk(root)) {
        for (Path f : (Iterable<Path>) s.filter(p -> p.toString().endsWith(".class"))::iterator) {
          if (f.getFileName().toString().equals("module-info.class") || f.getFileName().toString().equals("package-info.class")) {
            continue;
          }
          try (InputStream in = Files.newInputStream(f)) {
            ClassRefs c = read(in.readAllBytes());
            out.putIfAbsent(c.name(), c);
          }
        }
      }
    }
    return out;
  }

  public static ClassRefs read(byte[] bytes) {
    Map<String, Integer> refs = new HashMap<>();
    String[] info = new String[2];
    boolean[] generated = {false};
    new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
      @Override
      public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        info[0] = name;
      }

      @Override
      public void visitSource(String source, String debug) {
        info[1] = source;
      }

      @Override
      public org.objectweb.asm.AnnotationVisitor visitAnnotation(String desc, boolean visible) {
        if (desc.endsWith("/Generated;")) {
          generated[0] = true;
        }
        return null;
      }

      @Override
      public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
        return null;
      }

      @Override
      public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        return new MethodVisitor(Opcodes.ASM9) {
          @Override
          public void visitMethodInsn(int opcode, String owner, String n, String d, boolean itf) {
            add(refs, owner);
          }

          @Override
          public void visitFieldInsn(int opcode, String owner, String n, String d) {
            add(refs, owner);
          }

          @Override
          public void visitInvokeDynamicInsn(String n, String d, Handle bsm, Object... args) {
            for (Object a : args) {
              if (a instanceof Handle h) {
                add(refs, h.getOwner());
              }
            }
          }
        };
      }
    }, ClassReader.SKIP_FRAMES);
    String name = info[0];
    refs.remove(name);
    int slash = name.lastIndexOf('/');
    String file = info[1] != null ? info[1] : name.substring(slash + 1).replaceAll("\\$.*", "") + ".java";
    return new ClassRefs(name, (slash < 0 ? "" : name.substring(0, slash + 1)) + file, generated[0], Map.copyOf(refs));
  }

  private static void add(Map<String, Integer> refs, String owner) {
    if (!owner.startsWith("[")) {
      refs.merge(owner, 1, Integer::sum);
    }
  }

  /** Distinct classes a class references (coupling between objects), libraries included. */
  public static int coupling(ClassRefs c) {
    Set<String> distinct = new HashSet<>(c.references().keySet());
    return distinct.size();
  }
}
