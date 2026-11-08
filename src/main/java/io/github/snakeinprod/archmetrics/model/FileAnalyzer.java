package io.github.snakeinprod.archmetrics.model;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.stmt.AssertStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Computes {@link FileStats} from the source of one Java file. */
public final class FileAnalyzer {

  /** @param complexityThreshold methods above it are counted; {@code switchCases}: count each case, not each switch */
  public record Settings(int complexityThreshold, boolean switchCases) {
    public static final Settings DEFAULT = new Settings(20, false);
  }

  private static final Set<String> SERIALIZATION = Set.of("readObject", "writeObject", "readResolve", "writeReplace", "readObjectNoData");

  private final Settings settings;
  private final JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
    .setAttributeComments(false));

  public FileAnalyzer(Settings settings) {
    this.settings = settings;
  }

  /** Code lines of a file from its tokens and its assert statements. */
  static Set<Integer> codeLines(List<Tokens.Token> tokens, CompilationUnit cu) {
    Set<Integer> lines = new HashSet<>();
    tokens.forEach(t -> lines.add(t.line()));
    if (cu != null) {
      for (AssertStmt a : cu.findAll(AssertStmt.class)) {
        a.getRange().ifPresent(r -> {
          for (int l = r.begin.line; l <= r.end.line; l++) {
            lines.remove(l);
          }
        });
      }
    }
    return lines;
  }

  public FileStats analyze(String path, String source, List<Tokens.Token> tokens) {
    ParseResult<CompilationUnit> result = parser.parse(source);
    CompilationUnit cu = result.getResult().filter(c -> result.isSuccessful()).orElse(null);
    String pkg = cu == null ? packageOf(tokens) : cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
    if (cu != null && cu.getTypes().stream().anyMatch(FileAnalyzer::isGenerated)) {
      return FileStats.generated(path, pkg);
    }
    Set<Integer> code = codeLines(tokens, cu);
    if (cu == null) {
      return new FileStats(path, pkg, false, 0, code, 0, 0, 0, 0, 0, List.of(), List.of(), 0, 0, 0, 0, List.of(), Set.of());
    }

    // ranges that own code lines: methods, and types (named, anonymous, enum constant bodies)
    List<Owner> owners = new ArrayList<>();
    List<FileStats.MethodInfo> methods = new ArrayList<>();
    int ccSum = 0;
    int ccMax = 0;
    int ccOver = 0;
    List<CallableDeclaration<?>> callables = new ArrayList<>();
    cu.findAll(CallableDeclaration.class).forEach(c -> callables.add((CallableDeclaration<?>) c));
    List<CompactConstructorDeclaration> compact = cu.findAll(CompactConstructorDeclaration.class);
    List<Node> methodNodes = new ArrayList<>();
    for (CallableDeclaration<?> c : callables) {
      boolean hasBody = c instanceof ConstructorDeclaration || c instanceof MethodDeclaration m && m.getBody().isPresent();
      if (hasBody && !isGenerated(c)) {
        methodNodes.add(c);
      }
    }
    compact.stream().filter(c -> !isGenerated(c)).forEach(methodNodes::add);
    for (Node m : methodNodes) {
      int cc = complexity(m);
      int nesting = nesting(m);
      ccSum += cc;
      ccMax = Math.max(ccMax, cc);
      if (cc > settings.complexityThreshold()) {
        ccOver++;
      }
      owners.add(new Owner(m, methodStart(m), true));
      methods.add(new FileStats.MethodInfo(typeName(m) + "#" + methodName(m), line(m), cc, nesting, 0, 0, 0));
    }
    List<Node> types = new ArrayList<>();
    cu.findAll(TypeDeclaration.class).forEach(t -> types.add(t));
    for (Node t : types) {
      owners.add(new Owner(t, line(t), false));
    }
    for (ObjectCreationExpr o : cu.findAll(ObjectCreationExpr.class)) {
      if (o.getAnonymousClassBody().isPresent()) {
        owners.add(new Owner(o, line(o), false));
      }
    }
    for (EnumConstantDeclaration e : cu.findAll(EnumConstantDeclaration.class)) {
      if (!e.getClassBody().isEmpty()) {
        owners.add(new Owner(e, line(e), false));
      }
    }

    // each code line belongs to its innermost method (for method sizes) and innermost named type (class sizes)
    int[] methodLines = new int[methodNodes.size()];
    int[] typeLines = new int[types.size()];
    Map<Integer, Integer> methodOfLine = new HashMap<>();
    for (int l : code) {
      Owner inner = innermost(owners, l, o -> true);
      if (inner != null && inner.method()) {
        int i = methodNodes.indexOf(inner.node());
        methodLines[i]++;
        methodOfLine.put(l, i);
      }
      Owner type = innermost(owners, l, o -> !o.method() && o.node() instanceof TypeDeclaration);
      if (type != null) {
        typeLines[types.indexOf(type.node())]++;
      }
    }
    List<List<String>> methodTokens = new ArrayList<>();
    methodNodes.forEach(m -> methodTokens.add(new ArrayList<>()));
    for (Tokens.Token t : tokens) {
      Integer i = methodOfLine.get(t.line());
      if (i != null) {
        methodTokens.get(i).add(t.text());
      }
    }
    int methodLinesSum = 0;
    for (int i = 0; i < methods.size(); i++) {
      FileStats.MethodInfo m = methods.get(i);
      double[] h = halstead(methodTokens.get(i));
      methods.set(i, new FileStats.MethodInfo(m.name(), m.line(), m.complexity(), m.nesting(), methodLines[i], h[0], h[1]));
      methodLinesSum += methodLines[i];
    }
    List<FileStats.ClassInfo> classes = new ArrayList<>();
    int classLinesMax = 0;
    for (int i = 0; i < types.size(); i++) {
      classes.add(new FileStats.ClassInfo(qualifiedName(pkg, types.get(i)), line(types.get(i)), typeLines[i]));
      classLinesMax = Math.max(classLinesMax, typeLines[i]);
    }

    // unused items and totals
    List<FileStats.Declaration> decls = new ArrayList<>();
    int classesTotal = 0;
    for (Node t : types) {
      TypeDeclaration<?> td = (TypeDeclaration<?>) t;
      classesTotal++;
      if (!isPublicOrProtected(td) && !inInterface(td) && td.getAnnotations().isEmpty() && !td.isTopLevelType()
        || td.isTopLevelType() && !td.hasModifier(Modifier.Keyword.PUBLIC) && td.getAnnotations().isEmpty()) {
        decls.add(new FileStats.Declaration("class", td.getNameAsString(), td.hasModifier(Modifier.Keyword.PRIVATE), line(td)));
      }
    }
    int methodsTotal = 0;
    for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
      if (isGenerated(m)) {
        continue;
      }
      methodsTotal++;
      boolean candidate = !isPublicOrProtected(m) && !inInterface(m) && m.getAnnotations().isEmpty() && !m.isAbstract() && !m.isNative()
        && !SERIALIZATION.contains(m.getNameAsString()) && !(m.isStatic() && "main".equals(m.getNameAsString()))
        && !(m.getParentNode().orElse(null) instanceof ObjectCreationExpr) && !(m.getParentNode().orElse(null) instanceof EnumConstantDeclaration);
      if (candidate) {
        decls.add(new FileStats.Declaration("method", m.getNameAsString(), m.hasModifier(Modifier.Keyword.PRIVATE), line(m)));
      }
    }
    int variablesTotal = 0;
    for (FieldDeclaration f : cu.findAll(FieldDeclaration.class)) {
      for (VariableDeclarator v : f.getVariables()) {
        variablesTotal++;
        String n = v.getNameAsString();
        if (!isPublicOrProtected(f) && !inInterface(f) && f.getAnnotations().isEmpty() && !"serialVersionUID".equals(n)
          && !"serialPersistentFields".equals(n)) {
          decls.add(new FileStats.Declaration("variable", n, f.hasModifier(Modifier.Keyword.PRIVATE), line(v)));
        }
      }
    }
    int unusedLocals = 0;
    for (VariableDeclarationExpr e : cu.findAll(VariableDeclarationExpr.class)) {
      Node parent = e.getParentNode().orElse(null);
      boolean loopOrResource = parent instanceof ForEachStmt || parent instanceof TryStmt;
      for (VariableDeclarator v : e.getVariables()) {
        variablesTotal++;
        if (!loopOrResource && e.getAnnotations().isEmpty() && !usedInScope(v)) {
          unusedLocals++;
        }
      }
    }
    return new FileStats(path, pkg, false, methodNodes.size(), code, ccSum, ccMax, ccOver, methodLinesSum, classLinesMax, methods, classes,
      classesTotal, methodsTotal, variablesTotal, unusedLocals, decls, usedNames(cu));
  }

  private record Owner(Node node, int start, boolean method) {
    boolean contains(int line) {
      return node.getRange().map(r -> start <= line && line <= r.end.line).orElse(false);
    }

    int column() {
      return node.getRange().map(r -> r.begin.column).orElse(0);
    }
  }

  private static Owner innermost(List<Owner> owners, int line, java.util.function.Predicate<Owner> filter) {
    Owner best = null;
    for (Owner o : owners) {
      if (filter.test(o) && o.contains(line) && (best == null || o.start() > best.start()
        || o.start() == best.start() && (o.column() > best.column() || o.column() == best.column() && o.method()))) {
        best = o;
      }
    }
    return best;
  }

  // ------------------------------------------------------------------ complexity and nesting

  /** 1 + branches of the method body, lambdas included, nested and anonymous classes not. */
  int complexity(Node method) {
    int[] cc = {1};
    walk(method, n -> {
      if (n instanceof IfStmt || n instanceof ForStmt || n instanceof ForEachStmt || n instanceof WhileStmt || n instanceof DoStmt
        || n instanceof CatchClause || n instanceof ConditionalExpr) {
        cc[0]++;
      } else if (n instanceof SwitchStmt s) {
        cc[0] += settings.switchCases() ? cases(s.getEntries()) : 1;
      } else if (n instanceof SwitchExpr s) {
        cc[0] += settings.switchCases() ? cases(s.getEntries()) : 1;
      }
    });
    return cc[0];
  }

  private static int cases(List<SwitchEntry> entries) {
    return (int) entries.stream().filter(e -> !e.getLabels().isEmpty()).count();
  }

  /** Deepest nesting of control statements; an {@code else if} stays at its {@code if}'s depth. */
  static int nesting(Node method) {
    return depth(method, 0);
  }

  private static int depth(Node n, int d) {
    int max = d;
    for (Node c : n.getChildNodes()) {
      if (isBoundary(c)) {
        continue;
      }
      boolean control = c instanceof IfStmt && !(n instanceof IfStmt p && p.getElseStmt().filter(e -> e == c).isPresent())
        || c instanceof ForStmt || c instanceof ForEachStmt || c instanceof WhileStmt || c instanceof DoStmt || c instanceof SwitchStmt
        || c instanceof SwitchExpr || c instanceof TryStmt || c instanceof SynchronizedStmt;
      max = Math.max(max, depth(c, control ? d + 1 : d));
    }
    return max;
  }

  /** Visits the nodes below {@code root} that run as part of it: not nested type declarations or anonymous class bodies. */
  static void walk(Node root, Consumer<Node> visitor) {
    for (Node c : root.getChildNodes()) {
      if (isBoundary(c)) {
        continue;
      }
      visitor.accept(c);
      walk(c, visitor);
    }
  }

  private static boolean isBoundary(Node c) {
    Node parent = c.getParentNode().orElse(null);
    return c instanceof TypeDeclaration || c instanceof BodyDeclaration && (parent instanceof ObjectCreationExpr || parent instanceof EnumConstantDeclaration);
  }

  // ------------------------------------------------------------------ Halstead

  private static final Set<String> KEYWORDS = Set.of("abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
    "const", "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if",
    "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private", "protected", "public",
    "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
    "volatile", "while", "var", "yield", "record");
  private static final Set<String> CLOSERS = Set.of(")", "]", "}");

  /**
   * Volume N * log2(n) and difficulty n1 / 2 * N2 / n2. Operands are identifiers and literals, operators every other
   * token; a closing bracket belongs to its opening one.
   */
  static double[] halstead(List<String> tokens) {
    Set<String> operators = new HashSet<>();
    Set<String> operands = new HashSet<>();
    int bigN1 = 0;
    int bigN2 = 0;
    for (String t : tokens) {
      if (CLOSERS.contains(t)) {
        continue;
      }
      char c = t.charAt(0);
      boolean operand = Character.isJavaIdentifierStart(c) && !KEYWORDS.contains(t) || Character.isDigit(c) || c == '"' || c == '\''
        || c == '.' && t.length() > 1 && Character.isDigit(t.charAt(1));
      if (operand) {
        operands.add(t);
        bigN2++;
      } else {
        operators.add(t);
        bigN1++;
      }
    }
    int n = operators.size() + operands.size();
    double volume = n < 2 ? 0 : (bigN1 + bigN2) * Math.log(n) / Math.log(2);
    double difficulty = operands.isEmpty() ? 0 : operators.size() / 2.0 * bigN2 / operands.size();
    return new double[] {volume, difficulty};
  }

  // ------------------------------------------------------------------ names and helpers

  /** A local is used when its name appears in an expression of the enclosing body. */
  private static boolean usedInScope(VariableDeclarator v) {
    Node scope = v;
    while (scope.getParentNode().isPresent() && !(scope instanceof CallableDeclaration) && !(scope instanceof LambdaExpr)
      && !(scope instanceof CompactConstructorDeclaration) && !(scope instanceof com.github.javaparser.ast.body.InitializerDeclaration)) {
      scope = scope.getParentNode().get();
    }
    String name = v.getNameAsString();
    return scope.findAll(NameExpr.class).stream().anyMatch(n -> n.getNameAsString().equals(name));
  }

  /** Every identifier in the file except the names that declarations give themselves. */
  private static Set<String> usedNames(CompilationUnit cu) {
    Set<String> out = new HashSet<>();
    for (SimpleName s : cu.findAll(SimpleName.class)) {
      Node p = s.getParentNode().orElse(null);
      boolean declaration = p instanceof TypeDeclaration<?> t && t.getName() == s || p instanceof CallableDeclaration<?> c && c.getName() == s
        || p instanceof VariableDeclarator v && v.getName() == s || p instanceof Parameter pa && pa.getName() == s;
      if (!declaration) {
        out.add(s.getIdentifier());
      }
    }
    return out;
  }

  static boolean isGenerated(Node n) {
    return n instanceof NodeWithAnnotations<?> a && a.getAnnotations().stream()
      .anyMatch(x -> "Generated".equals(x.getName().getIdentifier()));
  }

  private static boolean isPublicOrProtected(Node n) {
    return n instanceof com.github.javaparser.ast.nodeTypes.NodeWithModifiers<?> m
      && (m.hasModifier(Modifier.Keyword.PUBLIC) || m.hasModifier(Modifier.Keyword.PROTECTED));
  }

  /** Members of interfaces and annotations are implicitly public. */
  private static boolean inInterface(Node n) {
    Node p = n.getParentNode().orElse(null);
    return p instanceof ClassOrInterfaceDeclaration c && c.isInterface() || p instanceof AnnotationDeclaration;
  }

  private static int line(Node n) {
    return n.getRange().map(r -> r.begin.line).orElse(0);
  }

  /** First line of a method after its annotations. */
  private static int methodStart(Node m) {
    if (m instanceof CallableDeclaration<?> c) {
      int start = c.getName().getRange().map(r -> r.begin.line).orElse(line(m));
      for (Modifier mod : c.getModifiers()) {
        start = Math.min(start, line(mod));
      }
      if (c instanceof MethodDeclaration md) {
        start = Math.min(start, line(md.getType()));
      }
      return start;
    }
    if (m instanceof CompactConstructorDeclaration c) {
      return c.getName().getRange().map(r -> r.begin.line).orElse(line(m));
    }
    return line(m);
  }

  private static String methodName(Node m) {
    return m instanceof CallableDeclaration<?> c ? (c instanceof ConstructorDeclaration ? "<init>" : c.getNameAsString())
      : m instanceof CompactConstructorDeclaration ? "<init>" : "?";
  }

  private static String typeName(Node n) {
    for (Node p = n.getParentNode().orElse(null); p != null; p = p.getParentNode().orElse(null)) {
      if (p instanceof TypeDeclaration<?> t) {
        return t.getNameAsString();
      }
      if (p instanceof ObjectCreationExpr o) {
        return o.getType().getNameAsString() + "$anonymous";
      }
    }
    return "?";
  }

  private static String qualifiedName(String pkg, Node t) {
    StringBuilder sb = new StringBuilder(((TypeDeclaration<?>) t).getNameAsString());
    for (Node p = t.getParentNode().orElse(null); p != null; p = p.getParentNode().orElse(null)) {
      if (p instanceof TypeDeclaration<?> outer) {
        sb.insert(0, outer.getNameAsString() + ".");
      }
    }
    return pkg.isEmpty() ? sb.toString() : pkg + "." + sb;
  }

  private static String packageOf(List<Tokens.Token> tokens) {
    if (tokens.isEmpty() || !"package".equals(tokens.get(0).text())) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 1; i < tokens.size() && !";".equals(tokens.get(i).text()); i++) {
      sb.append(tokens.get(i).text());
    }
    return sb.toString();
  }
}
