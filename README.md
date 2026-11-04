# Architecture Metrics

Raw maintainability and test metrics for Java in SonarQube Community Build.

The plugin measures metrics known from the literature (method complexity, size, coupling, duplication, package
cycles, dependency structure, nesting, unused code, method coverage) and stores the raw values as SonarQube measures
on files, directories and the project. It computes no scores and no grades: other plugins, dashboards and quality
gates read the values by metric key. Every value is recomputed on every analysis, so each branch and pull request has
its own.

See [docs/SPEC.md](docs/SPEC.md) for the specification.

## Metrics

Key prefix `arch_`. File metrics are added up to directories and the project (sum or maximum); project metrics exist
on the project only. All are numeric, not hidden, usable in quality gates.

| Key | Level | Definition |
|---|---|---|
| `arch_methods` | file, sum | methods and constructors with a body |
| `arch_code_lines` | file, sum | code lines (see below) |
| `arch_cc_sum` | file, sum | sum of method complexity |
| `arch_cc_max` | file, max | largest method complexity |
| `arch_cc_over_count` | file, sum | methods with a complexity above `sonar.arch.cc.threshold` |
| `arch_method_lines_sum` | file, sum | code lines of all methods |
| `arch_class_lines_max` | file, max | largest class in code lines; nested and local classes are not part of the outer class |
| `arch_cbo_max` | file, max | largest number of distinct classes one class references, library classes included |
| `arch_dup_lines` | project | code lines in duplicated blocks, first occurrence not counted |
| `arch_mfs_refs` | project | references in a minimum feedback arc set, summed over the levels of the package tree |
| `arch_cycle_refs_total` | project | references between sibling packages, summed over the levels |
| `arch_cycle_nodes` | project | packages in a cycle with their siblings, summed over the levels |
| `arch_cycle_nodes_total` | project | packages in the tree, summed over the levels |
| `arch_dep_parents` | project | packages with at least 2 sub-packages |
| `arch_dep_parents_over` | project | parents whose sub-package graph has E − N + P above `sonar.arch.dep.complexity` and a fan-out above `sonar.arch.dep.fanout` |
| `arch_dep_fanout_max` | project | largest number of siblings one sub-package references |
| `arch_pkg_size_max` | project | largest product of code lines and methods of one package's own files |
| `arch_nesting_topn_avg` | project | average nesting depth of the `sonar.arch.nesting.topN` most deeply nested methods |
| `arch_unused_classes` / `arch_classes` | project | private or package-private classes never referenced / all classes |
| `arch_unused_methods` / `arch_methods_total` | project | private or package-private methods never referenced / all methods (constructors excluded) |
| `arch_unused_variables` / `arch_variables_total` | project | private or package-private fields and local variables never referenced / all fields and locals |
| `arch_methods_covered`, `arch_methods_coverable` | file, sum | methods covered by tests and methods known, from the JaCoCo XML METHOD counter |
| `arch_top_offenders` | project | JSON: the 10 methods, classes and packages behind each maximum |

### How each value is counted

- **Scope.** Main Java files only (test files excluded), `sonar.exclusions` honoured. A file whose top-level type is
  annotated `@Generated` (any package) is left out of every metric, so are methods annotated `@Generated`.
- **Code lines.** Lines with at least one token, minus the lines of `assert` statements. Blank lines and lines with only
  comments do not count.
- **Method complexity.** 1, plus 1 per `if`, `for`, for-each, `while`, `do`, `catch` and `?:`, plus 1 per `switch`
  statement or expression (with `sonar.arch.cc.switchCases=true`: 1 per case label, `default` excluded). `&&` and `||`
  do not count. Lambdas count inside their enclosing method; methods of anonymous and local classes are methods of
  their own. Compact constructors of records count as constructors.
- **Method and class size.** Every code line belongs to its innermost method (for method sizes) and to its innermost
  named class (for class sizes). A method starts at its first modifier, type or name, after its annotations.
- **Nesting.** Depth of nested `if`, `for`, for-each, `while`, `do`, `switch`, `try` and `synchronized`; an `else if`
  stays at the depth of its `if`.
- **References** come from the compiled classes: a method call (lambdas and method references included) or a field
  read or write of another class. Coupling counts each referenced class once; package graphs count every occurrence.
  Without compiled classes (`sonar.java.binaries`) coupling and package metrics are not computed.
- **Packages** are Java packages. The tree holds every package and its ancestors; at each parent, the graph's nodes
  are its child packages and an edge from A to B sums the references from classes anywhere below A to classes anywhere
  below B. The package's own classes are not a node of its children's graph.
- **Minimum feedback arc set.** Per strongly connected component of each graph: exact for up to 20 packages (dynamic
  programming over subsets for the cheapest order of the packages; references pointing backwards are the set), above
  that the greedy order of Eades, Lin and Smyth (1993): sinks to the back, sources to the front, otherwise the package
  with the largest outgoing minus incoming weight.
- **Dependency complexity** of a parent: E (distinct edges between its sub-packages) − N (sub-packages) + P (weakly
  connected components).
- **Duplication.** Each file is tokenised (identifiers, literals, operators, punctuation; whitespace and comments
  dropped). A block is a run of at least `sonar.arch.dup.tokens` identical tokens that occurs more than once in the
  project. Files are ordered by path; the first occurrence of a block is not counted, every further one marks its
  tokens; overlapping marks merge; the code lines holding marked tokens are counted. SonarQube's own duplication
  measures are not used.
- **Unused items** are found by name: a private item must be named somewhere else in its file, a package-private one
  somewhere in its package. Annotated items, `main`, serialization members (`serialVersionUID`, `readObject`...),
  abstract methods and members of interfaces are not candidates. A local variable is unused when its name never
  appears in an expression of its method; loop and resource variables are not candidates.
- **Method coverage** merges all JaCoCo XML reports (`sonar.coverage.jacoco.xmlReportPaths` of the project and its
  modules, plus `target/site/jacoco*/jacoco.xml` and `build/reports/jacoco/**/*.xml`): a method is covered when any
  report covers it.

## Settings

| Property | Default | Meaning |
|---|---|---|
| `sonar.arch.cc.threshold` | `20` | complexity above which a method counts in `arch_cc_over_count` |
| `sonar.arch.cc.switchCases` | `false` | count each case label instead of each switch |
| `sonar.arch.dup.tokens` | `50` | smallest duplicated block, in tokens |
| `sonar.arch.dep.complexity` | `12` | E − N + P limit of a parent package |
| `sonar.arch.dep.fanout` | `9` | sub-package fan-out limit of a parent package |
| `sonar.arch.nesting.topN` | `10` | how many of the most deeply nested methods `arch_nesting_topn_avg` averages |

A setting changes the measure, never the code. Set them on the project, or on the scanner command line.

## Page

The project page *Architecture metrics* (in the project's *More* menu) lists every value with its definition and,
for each maximum, the ten methods, classes or packages behind it.

## Install

Tested on SonarQube Community Build 26.7.

1. Download `sonar-arch-metrics-<version>.jar` from
   [Releases](https://github.com/snake-in-prod/sonar-arch-metrics/releases).
2. Copy it to `extensions/plugins/` and restart SonarQube (or add its URL to the list of plugins your deployment
   installs).
3. Analyse with compiled classes (`sonar.java.binaries`; the Maven and Gradle scanners set it) and, for method
   coverage, JaCoCo XML reports.

Nothing in the plugin depends on the branch: with a branch plugin every branch and pull request gets its own values.

## Known limits

- Unused items are matched by name, not by symbol: an item whose name is used for something else in its file or
  package counts as used.
- Two classes of the same name in one package (in different modules) are one class for coupling and packages.
- The duplication index holds up to 16 million tokens (roughly 3 million lines).

## Build

```sh
./mvnw verify    # JDK 21
```

The jar is `target/sonar-arch-metrics-<version>.jar`.

## License

Apache-2.0
