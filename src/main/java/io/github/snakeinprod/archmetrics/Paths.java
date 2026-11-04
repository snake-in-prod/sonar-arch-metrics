package io.github.snakeinprod.archmetrics;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.sonar.api.config.Configuration;

/** Where the compiled classes and the JaCoCo reports of a (multi-module) project are. */
final class Paths {

  private static final Set<String> SKIP_DIRS = Set.of(".git", "node_modules", "src", ".scannerwork", ".gradle", ".idea");

  private Paths() {
  }

  /**
   * {@code sonar.java.binaries} of the project and of every declared module ({@code sonar.modules} with
   * {@code <module>.sonar.java.binaries}), plus {@code target/classes} and {@code build/classes/<lang>/main} below the base.
   */
  static Set<Path> binaries(Path base, Configuration config) throws IOException {
    Set<Path> out = new LinkedHashSet<>(resolve(base, config.getStringArray("sonar.java.binaries")));
    modules(base, "", config, "sonar.java.binaries", out);
    walk(base, (dir, name, parent) -> name.equals("classes") && parent.equals("target")
      || name.equals("main") && dir.getParent().getParent() != null && dir.getParent().getParent().getFileName().toString().equals("classes")
        && dir.getParent().getParent().getParent() != null
        && dir.getParent().getParent().getParent().getFileName().toString().equals("build"), out, true);
    return out;
  }

  /** {@code sonar.coverage.jacoco.xmlReportPaths} (project and modules) plus the usual Maven and Gradle report locations. */
  static Set<Path> jacocoReports(Path base, Configuration config) throws IOException {
    String key = "sonar.coverage.jacoco.xmlReportPaths";
    Set<Path> out = new LinkedHashSet<>(resolve(base, config.getStringArray(key)));
    modules(base, "", config, key, out);
    try (Stream<Path> s = Files.walk(base)) {
      s.filter(p -> p.getFileName().toString().endsWith(".xml") && p.toString().replace('\\', '/').matches(
        ".*/(target/site/jacoco[^/]*/jacoco\\.xml|build/reports/jacoco/.*\\.xml)")).forEach(p -> out.add(p.toAbsolutePath().normalize()));
    }
    return out;
  }

  private static void modules(Path base, String prefix, Configuration config, String key, Set<Path> out) throws IOException {
    for (String module : config.getStringArray(prefix + "sonar.modules")) {
      String p = prefix + module.trim() + ".";
      Path moduleBase = config.get(p + "sonar.projectBaseDir").map(base::resolve).orElse(base.resolve(module.trim().replace(':', '_')));
      out.addAll(resolve(moduleBase, config.getStringArray(p + key)));
      modules(moduleBase, p, config, key, out);
    }
  }

  private interface DirMatcher {
    boolean matches(Path dir, String name, String parent);
  }

  private static void walk(Path root, DirMatcher m, Set<Path> out, boolean skipBelowMatch) throws IOException {
    if (!Files.isDirectory(root)) {
      return;
    }
    Files.walkFileTree(root, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
        Path name = dir.getFileName();
        Path parent = dir.getParent();
        if (name == null || parent == null || parent.getFileName() == null || dir.equals(root)) {
          return FileVisitResult.CONTINUE;
        }
        if (SKIP_DIRS.contains(name.toString())) {
          return FileVisitResult.SKIP_SUBTREE;
        }
        if (m.matches(dir, name.toString(), parent.getFileName().toString())) {
          out.add(dir.toAbsolutePath().normalize());
          return skipBelowMatch ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
        }
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFileFailed(Path file, IOException e) {
        return FileVisitResult.CONTINUE;
      }
    });
  }

  /** Paths relative to {@code base}; {@code *} and {@code **} wildcards are expanded. */
  static List<Path> resolve(Path base, String[] patterns) throws IOException {
    List<Path> out = new ArrayList<>();
    for (String raw : patterns) {
      String p = raw.trim().replace('\\', '/');
      if (p.isEmpty()) {
        continue;
      }
      int star = p.indexOf('*');
      if (star >= 0) {
        int cut = p.lastIndexOf('/', star);
        Path root = cut < 0 ? base : base.resolve(p.substring(0, cut + 1)).normalize();
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + p.substring(cut + 1));
        if (Files.isDirectory(root)) {
          try (Stream<Path> s = Files.walk(root)) {
            s.filter(f -> matcher.matches(root.relativize(f))).map(f -> f.toAbsolutePath().normalize()).forEach(out::add);
          }
        }
      } else {
        Path f = base.resolve(p).toAbsolutePath().normalize();
        if (Files.exists(f)) {
          out.add(f);
        }
      }
    }
    return out;
  }
}
