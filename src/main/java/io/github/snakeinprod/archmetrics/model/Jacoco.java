package io.github.snakeinprod.archmetrics.model;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;

import java.util.Map;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

/**
 * Method coverage from JaCoCo XML reports. A method is identified by class, name, descriptor and line, so several
 * reports (unit and integration tests, one per module) merge: a method is covered when any report covers it.
 */
public final class Jacoco {

  private Jacoco() {
  }

  public record MethodCoverage(int covered, int coverable) {
  }

  /** Source path ({@code com/acme/Foo.java}) to its method coverage. */
  public static Map<String, MethodCoverage> read(Collection<Path> reports) throws Exception {
    Map<String, Map<String, Boolean>> methods = new HashMap<>();
    XMLInputFactory f = XMLInputFactory.newFactory();
    f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    for (Path report : reports) {
      if (!Files.isRegularFile(report)) {
        continue;
      }
      try (InputStream in = Files.newInputStream(report)) {
        XMLStreamReader r = f.createXMLStreamReader(in);
        String pkg = "";
        String cls = null;
        String source = null;
        String method = null;
        while (r.hasNext()) {
          int e = r.next();
          if (e == XMLStreamConstants.START_ELEMENT) {
            switch (r.getLocalName()) {
              case "package" -> pkg = r.getAttributeValue(null, "name");
              case "class" -> {
                cls = r.getAttributeValue(null, "name");
                String sf = r.getAttributeValue(null, "sourcefilename");
                source = sf == null ? null : (pkg.isEmpty() ? "" : pkg + "/") + sf;
              }
              case "method" -> method = cls + "#" + r.getAttributeValue(null, "name") + r.getAttributeValue(null, "desc") + "@"
                + r.getAttributeValue(null, "line");
              case "counter" -> {
                if (method != null && source != null && "METHOD".equals(r.getAttributeValue(null, "type"))) {
                  boolean covered = Integer.parseInt(r.getAttributeValue(null, "covered")) > 0;
                  methods.computeIfAbsent(source, k -> new HashMap<>()).merge(method, covered, Boolean::logicalOr);
                }
              }
              default -> {
              }
            }
          } else if (e == XMLStreamConstants.END_ELEMENT) {
            switch (r.getLocalName()) {
              case "method" -> method = null;
              case "class" -> {
                cls = null;
                source = null;
              }
              default -> {
              }
            }
          }
        }
        r.close();
      }
    }
    Map<String, MethodCoverage> out = new HashMap<>();
    methods.forEach((src, m) -> out.put(src, new MethodCoverage((int) m.values().stream().filter(b -> b).count(), m.size())));
    return out;
  }

}
