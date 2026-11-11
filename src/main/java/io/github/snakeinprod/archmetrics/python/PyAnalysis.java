package io.github.snakeinprod.archmetrics.python;

import com.google.gson.Gson;
import io.github.snakeinprod.archmetrics.Analysis;
import io.github.snakeinprod.archmetrics.ArchMetrics;
import io.github.snakeinprod.archmetrics.model.Duplication;
import io.github.snakeinprod.archmetrics.model.PackageGraph;
import io.github.snakeinprod.archmetrics.model.Tokens;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code arch_py_*} measures of the main Python files of a project: sources in, measures by metric key out. Kept
 * apart from {@link Analysis}, so Python files never change the Java {@code arch_*} values.
 */
public final class PyAnalysis {

  private static final int TOP = 10;
  private static final Set<String> NESTING = Set.of("if", "elif", "else", "for", "while", "try", "except", "finally", "with", "match");
  private static final Set<String> BRANCHES = Set.of("if", "elif", "for", "while", "except");
  private static final Set<String> KEYWORDS = Set.of("and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del",
    "elif", "else", "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass",
    "raise", "return", "try", "while", "with", "yield");
  private static final Set<String> CLOSERS = Set.of(")", "]", "}");
  private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
  private static final Pattern BRANCH_WORD = Pattern.compile("\\b(if|for)\\b");
  private static final Pattern QUOTED = Pattern.compile("'[^']*'|\"[^\"]*\"");

  /** @param path the file's path below the project base directory, "/" separated */
  public record Source(String key, String path, String contents) {
  }

  /** @param files file key to metric key to value; {@code project} the set metrics */
  public record Result(Map<String, Map<String, Object>> files, Map<String, Object> project) {
  }

  record Function(String name, int line, int complexity, int nesting, int lines, double volume, double difficulty) {
    double effort() {
      return volume * difficulty;
    }
  }

  private record Parsed(Source src, List<Token> tokens, PyModule module, Set<Integer> codeLines, List<Function> functions) {
  }

  private PyAnalysis() {
  }

  public static Result run(List<Source> sources, Analysis.Settings settings) {
    List<Source> sorted = new ArrayList<>(sources);
    sorted.sort(Comparator.comparing(Source::key));
    List<Parsed> parsed = new ArrayList<>();
    Map<String, Map<String, Object>> files = new LinkedHashMap<>();
    for (Source src : sorted) {
      Parsed p = parse(src, settings.file().switchCases());
      parsed.add(p);
      Map<String, Object> m = files.computeIfAbsent(src.key(), k -> new LinkedHashMap<>());
      m.put(ArchMetrics.PY_CODE_LINES.getKey(), p.codeLines().size());
      m.put(ArchMetrics.PY_FUNCTIONS.getKey(), p.functions().size());
      m.put(ArchMetrics.PY_CLASSES.getKey(), (int) p.module().walk().stream().filter(s -> s.keyword().equals("class")).count());
      m.put(ArchMetrics.PY_CC_SUM.getKey(), p.functions().stream().mapToInt(Function::complexity).sum());
      m.put(ArchMetrics.PY_CC_MAX.getKey(), p.functions().stream().mapToInt(Function::complexity).max().orElse(0));
      m.put(ArchMetrics.PY_CC_OVER_COUNT.getKey(), count(p, f -> f.complexity() > settings.file().complexityThreshold()));
      m.put(ArchMetrics.PY_FUNCTION_LINES_SUM.getKey(), p.functions().stream().mapToInt(Function::lines).sum());
      m.put(ArchMetrics.PY_FUNCTION_LINES_OVER_COUNT.getKey(), count(p, f -> f.lines() > settings.methodLines()));
      m.put(ArchMetrics.PY_HALSTEAD_OVER_COUNT.getKey(), count(p, f -> effortOver(f, settings)));
    }

    Map<String, Object> project = new LinkedHashMap<>();
    Map<String, List<Map<String, Object>>> top = new LinkedHashMap<>();
    top.put(ArchMetrics.PY_CC_MAX.getKey(), topFunctions(parsed, Function::complexity));
    top.put(ArchMetrics.PY_NESTING_TOPN_AVG.getKey(), topFunctions(parsed, Function::nesting));
    top.put(ArchMetrics.PY_FUNCTION_LINES_OVER_COUNT.getKey(), topFunctions(parsed, Function::lines));
    top.put(ArchMetrics.PY_HALSTEAD_OVER_COUNT.getKey(), topFunctions(parsed, f -> Math.round(f.effort())));

    List<List<Tokens.Token>> dupTokens = new ArrayList<>();
    for (Parsed p : parsed) {
      List<Tokens.Token> t = new ArrayList<>();
      for (Token x : p.tokens()) {
        if (!isLayout(x)) {
          t.add(new Tokens.Token(x.text(), x.line()));
        }
      }
      dupTokens.add(t);
    }
    project.put(ArchMetrics.PY_DUP_LINES.getKey(), Duplication.duplicatedLines(dupTokens, parsed.stream().map(Parsed::codeLines).toList(),
      settings.dupTokens()));
    List<Function> functions = parsed.stream().flatMap(p -> p.functions().stream()).toList();
    project.put(ArchMetrics.PY_NESTING_TOPN_AVG.getKey(), functions.stream().map(Function::nesting).sorted(Comparator.reverseOrder())
      .limit(settings.nestingTopN()).mapToInt(Integer::intValue).average().orElse(0));
    project.put(ArchMetrics.PY_HALSTEAD_EFFORT_AVG.getKey(), functions.stream().mapToDouble(Function::effort).average().orElse(0));

    PackageGraph.Result g = PackageGraph.compute(PackageGraph.levels(packages(parsed), importReferences(parsed)), settings.graph());
    project.put(ArchMetrics.PY_MFS_REFS.getKey(), g.mfsRefs());
    project.put(ArchMetrics.PY_CYCLE_REFS_TOTAL.getKey(), g.cycleRefsTotal());
    project.put(ArchMetrics.PY_CYCLE_NODES.getKey(), g.cycleNodes());
    project.put(ArchMetrics.PY_CYCLE_NODES_TOTAL.getKey(), g.cycleNodesTotal());
    top.put(ArchMetrics.PY_CYCLE_NODES.getKey(), g.cyclic().stream().limit(TOP).map(p -> row(p, null, 0, 1)).toList());

    unused(parsed, project, top);
    project.put(ArchMetrics.PY_TOP_OFFENDERS.getKey(), new Gson().toJson(top));
    return new Result(files, project);
  }

  private static int count(Parsed p, java.util.function.Predicate<Function> test) {
    return (int) p.functions().stream().filter(test).count();
  }

  static boolean effortOver(Function f, Analysis.Settings settings) {
    return f.volume() > 200 && f.difficulty() > 5 && f.effort() > 1000 && f.lines() > settings.methodLines();
  }

  // ------------------------------------------------------------------ one file

  private static Parsed parse(Source src, boolean switchCases) {
    List<Token> tokens = PythonLexer.lex(src.contents()).tokens();
    PyModule module = PyModule.of(TreeBuilder.build(tokens));
    // docstrings (and other statements that are only a string) and assert statements are not code
    Set<Token> notCode = Collections.newSetFromMap(new IdentityHashMap<>());
    for (PyStatement s : module.walk()) {
      if (s.body().isEmpty() && (s.keyword().equals("assert") || s.nodes().stream().allMatch(n -> isKind(n, TokenKind.STRING)))) {
        notCode.addAll(Nodes.tokens(s.nodes()));
      }
    }
    Set<Integer> code = new TreeSet<>();
    for (Token t : tokens) {
      if (!isLayout(t) && !notCode.contains(t)) {
        for (int l = t.line(); l <= t.endLine(); l++) {
          code.add(l);
        }
      }
    }

    List<PyStatement> defs = new ArrayList<>();
    List<String> names = new ArrayList<>();
    collectDefs(module.statements(), "", defs, names);
    // each code line belongs to its innermost function; functions nest, so the innermost is the latest start
    int[] lines = new int[defs.size()];
    Map<Integer, Integer> owner = new HashMap<>();
    for (int l : code) {
      int best = -1;
      for (int i = 0; i < defs.size(); i++) {
        if (defs.get(i).first().line() <= l && l <= defs.get(i).last().endLine()
          && (best < 0 || defs.get(i).first().line() >= defs.get(best).first().line())) {
          best = i;
        }
      }
      if (best >= 0) {
        lines[best]++;
        owner.put(l, best);
      }
    }
    List<List<Token>> fnTokens = new ArrayList<>();
    defs.forEach(d -> fnTokens.add(new ArrayList<>()));
    for (Token t : tokens) {
      Integer i = owner.get(t.line());
      if (i != null && !isLayout(t)) {
        fnTokens.get(i).add(t);
      }
    }
    List<Function> functions = new ArrayList<>();
    for (int i = 0; i < defs.size(); i++) {
      PyStatement d = defs.get(i);
      double[] h = halstead(fnTokens.get(i));
      functions.add(new Function(names.get(i), d.first().line(), 1 + branches(d.body(), switchCases), depth(d.body(), 0), lines[i], h[0],
        h[1]));
    }
    return new Parsed(src, tokens, module, code, functions);
  }

  private static void collectDefs(List<PyStatement> block, String prefix, List<PyStatement> defs, List<String> names) {
    for (PyStatement s : block) {
      String kw = s.keyword();
      String inner = prefix;
      if ((kw.equals("def") || kw.equals("class")) && s.name() != null) {
        inner = prefix.isEmpty() ? s.name() : prefix + "." + s.name();
        if (kw.equals("def")) {
          defs.add(s);
          names.add(inner);
        }
      }
      collectDefs(s.body(), inner, defs, names);
    }
  }

  /**
   * Branches of a function body, nested functions and classes left out: if, elif, for, while, except, each match (or
   * each case but {@code case _}), and each {@code if} and {@code for} inside an expression (conditional expressions,
   * comprehensions), lambdas included.
   */
  static int branches(List<PyStatement> block, boolean switchCases) {
    int n = 0;
    for (PyStatement s : block) {
      String kw = s.keyword();
      if (kw.equals("def") || kw.equals("class")) {
        continue;
      }
      boolean compound = !s.body().isEmpty();
      if (compound && (BRANCHES.contains(kw) || kw.equals("match") && !switchCases)) {
        n++;
      } else if (compound && kw.equals("case") && switchCases && !(s.nodes().size() > 1 && Nodes.isIdent(s.nodes().get(1), "_"))) {
        n++;
      }
      Node head = s.nodes().get(s.isAsync() ? 1 : 0);
      for (Token t : Nodes.tokens(s.nodes())) {
        if ((t.isIdent("if") || t.isIdent("for")) && t != head.first()) {
          n++;
        } else if (t.kind() == TokenKind.STRING) {
          n += fStringBranches(t.text());
        }
      }
      n += branches(s.body(), switchCases);
    }
    return n;
  }

  /** {@code if} and {@code for} in the replacement fields of an f-string, quoted text inside them left out. */
  static int fStringBranches(String text) {
    if (!isFString(text) || text.indexOf('{') < 0) {
      return 0;
    }
    StringBuilder fields = new StringBuilder();
    int depth = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '{' && depth == 0 && i + 1 < text.length() && text.charAt(i + 1) == '{') {
        i++;
      } else if (c == '{') {
        depth++;
        fields.append(' ');
      } else if (c == '}' && depth > 0) {
        depth--;
        fields.append(' ');
      } else if (depth > 0) {
        fields.append(c);
      }
    }
    return (int) BRANCH_WORD.matcher(QUOTED.matcher(fields).replaceAll(" ")).results().count();
  }

  /** Deepest nesting of if, for, while, try, with and match; elif, else, except and finally stay at their statement's depth. */
  static int depth(List<PyStatement> block, int d) {
    int max = d;
    for (PyStatement s : block) {
      String kw = s.keyword();
      if (kw.equals("def") || kw.equals("class")) {
        continue;
      }
      int inner = NESTING.contains(kw) && !s.body().isEmpty() ? d + 1 : d;
      max = Math.max(max, Math.max(inner, depth(s.body(), inner)));
    }
    return max;
  }

  /** As for Java: operands are names and literals, operators every other token; closing brackets are not counted. */
  static double[] halstead(List<Token> tokens) {
    Set<String> operators = new HashSet<>();
    Set<String> operands = new HashSet<>();
    int bigN1 = 0;
    int bigN2 = 0;
    for (Token t : tokens) {
      if (t.kind() == TokenKind.OP && CLOSERS.contains(t.text())) {
        continue;
      }
      boolean operand = t.kind() == TokenKind.IDENT && !KEYWORDS.contains(t.text()) || t.kind() == TokenKind.NUMBER
        || t.kind() == TokenKind.STRING;
      if (operand) {
        operands.add(t.text());
        bigN2++;
      } else {
        operators.add(t.text());
        bigN1++;
      }
    }
    int n = operators.size() + operands.size();
    double volume = n < 2 ? 0 : (bigN1 + bigN2) * Math.log(n) / Math.log(2);
    double difficulty = operands.isEmpty() ? 0 : operators.size() / 2.0 * bigN2 / operands.size();
    return new double[] {volume, difficulty};
  }

  // ------------------------------------------------------------------ imports and packages

  /** A package is a directory holding Python files, dotted: {@code src/app/core/x.py} is in {@code src.app.core}. */
  static String packageOf(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? "" : path.substring(0, slash).replace('.', '_').replace('/', '.');
  }

  private static Set<String> packages(List<Parsed> parsed) {
    Set<String> out = new HashSet<>();
    parsed.forEach(p -> out.add(packageOf(p.src().path())));
    return out;
  }

  /** Package to package: one reference per pair of files where the first imports the second. */
  private static Map<String, Map<String, Integer>> importReferences(List<Parsed> parsed) {
    Set<String> paths = new HashSet<>();
    parsed.forEach(p -> paths.add(p.src().path()));
    Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
    for (Parsed p : parsed) {
      String from = p.src().path();
      for (String target : imports(p.module(), from, paths)) {
        out.computeIfAbsent(packageOf(from), k -> new HashMap<>()).merge(packageOf(target), 1, Integer::sum);
      }
    }
    return out;
  }

  /** The project files a module imports, itself left out; imports anywhere in the file count. */
  static Set<String> imports(PyModule m, String from, Set<String> paths) {
    List<String> dotted = new ArrayList<>(m.imports().values());
    for (PyStatement s : m.walk()) {
      // from a.b import * leaves no name in imports
      List<Node> n = s.nodes();
      if (s.keyword().equals("from") && n.size() > 3 && Nodes.isLeaf(n.get(n.size() - 1), "*")) {
        dotted.add(Nodes.tokens(n.subList(1, n.size() - 2)).stream().map(Token::text).reduce("", String::concat));
      }
    }
    Set<String> out = new TreeSet<>();
    for (String d : dotted) {
      String p = module(d, from, paths);
      if (p != null && !p.equals(from)) {
        out.add(p);
      }
    }
    return out;
  }

  /**
   * The project file an import names: the longest dotted prefix that is a module ({@code a/b.py}) or package
   * ({@code a/b/__init__.py}); a relative import from the file's package, an absolute one from each directory above
   * the file (deepest first) and that directory's {@code src/}.
   */
  static String module(String dotted, String from, Set<String> paths) {
    int dots = 0;
    while (dots < dotted.length() && dotted.charAt(dots) == '.') {
      dots++;
    }
    List<String> roots = new ArrayList<>();
    if (dots > 0) {
      String base = join(dir(from), "../".repeat(dots - 1));
      if (base == null) {
        return null;
      }
      roots.add(base.isEmpty() ? "" : base + "/");
    } else {
      for (String d = dir(from); ; d = dir(d.substring(0, d.length() - 1))) {
        roots.add(d);
        roots.add(d + "src/");
        if (d.isEmpty()) {
          break;
        }
      }
    }
    String name = dotted.substring(dots);
    while (true) {
      String rel = name.replace('.', '/');
      for (String root : roots) {
        for (String c : name.isEmpty() ? List.of(root + "__init__.py") : List.of(root + rel + ".py", root + rel + "/__init__.py")) {
          if (paths.contains(c)) {
            return c;
          }
        }
      }
      if (name.isEmpty() || dots == 0 && !name.contains(".")) {
        return null;
      }
      name = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : "";
    }
  }

  private static String dir(String path) {
    return path.substring(0, path.lastIndexOf('/') + 1);
  }

  /** {@code dir} + {@code relative} with "." and ".." resolved, or null when it leaves the base directory. */
  private static String join(String dir, String relative) {
    List<String> parts = new ArrayList<>();
    for (String s : (dir + relative).split("/")) {
      if (s.equals("..")) {
        if (parts.isEmpty()) {
          return null;
        }
        parts.remove(parts.size() - 1);
      } else if (!s.isEmpty() && !s.equals(".")) {
        parts.add(s);
      }
    }
    return String.join("/", parts);
  }

  // ------------------------------------------------------------------ unused private names

  /**
   * Module-level private names ({@code _x}, not {@code __x__}): defs, classes and assignment targets outside any def
   * or class, also inside top-level if, try, with and loops. One is unused when no name token in the project other than
   * a def or class name or a module-level assignment target spells it, nor any name inside an f-string.
   */
  private static void unused(List<Parsed> parsed, Map<String, Object> project, Map<String, List<Map<String, Object>>> top) {
    Set<Token> declaring = Collections.newSetFromMap(new IdentityHashMap<>());
    List<Map<String, Token>> declared = new ArrayList<>();
    for (Parsed p : parsed) {
      Map<String, Token> names = new TreeMap<>();
      moduleLevel(p.module().statements(), declaring, names);
      for (PyStatement s : p.module().walk()) {
        if (s.nameToken() != null) {
          declaring.add(s.nameToken());
        }
      }
      declared.add(names);
    }
    Set<String> used = new HashSet<>();
    for (Parsed p : parsed) {
      for (Token t : p.tokens()) {
        if (t.kind() == TokenKind.IDENT && !declaring.contains(t)) {
          used.add(t.text());
        } else if (t.kind() == TokenKind.STRING && isFString(t.text())) {
          Matcher m = NAME.matcher(t.text());
          while (m.find()) {
            used.add(m.group());
          }
        }
      }
    }
    int total = 0;
    List<Map<String, Object>> rows = new ArrayList<>();
    for (int i = 0; i < parsed.size(); i++) {
      for (Map.Entry<String, Token> d : declared.get(i).entrySet()) {
        total++;
        if (!used.contains(d.getKey())) {
          rows.add(row(d.getKey(), parsed.get(i).src().key(), d.getValue().line(), 1));
        }
      }
    }
    project.put(ArchMetrics.PY_UNUSED_NAMES.getKey(), rows.size());
    project.put(ArchMetrics.PY_PRIVATE_NAMES.getKey(), total);
    top.put(ArchMetrics.PY_UNUSED_NAMES.getKey(), rows.stream().limit(TOP).toList());
  }

  private static void moduleLevel(List<PyStatement> block, Set<Token> declaring, Map<String, Token> names) {
    for (PyStatement s : block) {
      String kw = s.keyword();
      if (kw.equals("def") || kw.equals("class")) {
        Token t = s.nameToken();
        if (t != null && isPrivate(t.text())) {
          names.putIfAbsent(t.text(), t);
        }
        continue;
      }
      if (s.body().isEmpty()) {
        for (Token t : targets(s.nodes())) {
          declaring.add(t);
          if (isPrivate(t.text())) {
            names.putIfAbsent(t.text(), t);
          }
        }
      }
      moduleLevel(s.body(), declaring, names);
    }
  }

  /** Names bound by an assignment statement: {@code a = b = 1}, {@code a, (b, c) = x}, {@code a: int = 1}, {@code a: int}. */
  static List<Token> targets(List<Node> nodes) {
    List<Token> out = new ArrayList<>();
    List<List<Node>> parts = Nodes.split(nodes, "=");
    if (parts.size() > 1 && !isLeafOp(nodes.get(nodes.size() - 1), "=")) {
      for (int i = 0; i < parts.size() - 1; i++) {
        names(parts.get(i), out);
      }
    } else if (nodes.size() > 2 && Nodes.leaf(nodes.get(0)) != null && Nodes.leaf(nodes.get(0)).kind() == TokenKind.IDENT
      && isLeafOp(nodes.get(1), ":")) {
      out.add(Nodes.leaf(nodes.get(0)));
    }
    return out;
  }

  private static void names(List<Node> target, List<Token> out) {
    for (List<Node> item : Nodes.split(target, ",")) {
      int j = isLeafOp(item.get(0), "*") && item.size() > 1 ? 1 : 0;
      Node n = item.get(j);
      Token t = Nodes.leaf(n);
      boolean single = item.size() == j + 1 || item.size() > j + 1 && isLeafOp(item.get(j + 1), ":");
      if (t != null && t.kind() == TokenKind.IDENT && single && !KEYWORDS.contains(t.text())) {
        out.add(t);
      } else if (n instanceof Node.Group g && item.size() == j + 1 && (g.is("(") || g.is("["))) {
        names(g.children(), out);
      }
    }
  }

  private static boolean isPrivate(String name) {
    return name.length() > 1 && name.startsWith("_") && !(name.startsWith("__") && name.endsWith("__"));
  }

  private static boolean isFString(String text) {
    int q = 0;
    while (q < text.length() && text.charAt(q) != '\'' && text.charAt(q) != '"') {
      q++;
    }
    String prefix = text.substring(0, q).toLowerCase(java.util.Locale.ROOT);
    return prefix.indexOf('f') >= 0 || prefix.indexOf('t') >= 0;
  }

  // ------------------------------------------------------------------ helpers

  private static boolean isLayout(Token t) {
    return t.kind() == TokenKind.NEWLINE || t.kind() == TokenKind.INDENT || t.kind() == TokenKind.DEDENT;
  }

  private static boolean isKind(Node n, TokenKind kind) {
    return Nodes.leaf(n) != null && Nodes.leaf(n).kind() == kind;
  }

  private static boolean isLeafOp(Node n, String text) {
    return Nodes.leaf(n) != null && Nodes.leaf(n).kind() == TokenKind.OP && Nodes.leaf(n).is(text);
  }

  private static List<Map<String, Object>> topFunctions(List<Parsed> parsed, java.util.function.Function<Function, Number> value) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Parsed p : parsed) {
      for (Function f : p.functions()) {
        rows.add(row(f.name(), p.src().key(), f.line(), value.apply(f)));
      }
    }
    rows.sort(Comparator.comparingDouble((Map<String, Object> m) -> ((Number) m.get("value")).doubleValue()).reversed());
    return new ArrayList<>(rows.subList(0, Math.min(TOP, rows.size())));
  }

  private static Map<String, Object> row(String name, String file, int line, Number value) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", name);
    if (file != null) {
      m.put("file", file);
    }
    if (line > 0) {
      m.put("line", line);
    }
    m.put("value", value);
    return m;
  }
}
