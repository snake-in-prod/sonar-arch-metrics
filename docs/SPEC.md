# sonar-arch-metrics: specification

**Goal.** A SonarQube plugin for Java that measures maintainability and test metrics known from the literature and
stores the raw values as measures, per file, package (directory) and project, so other plugins, dashboards and
quality gates can use them. It computes no scores.

**Target.** SonarQube Community Build 26.x and later, sonar-plugin-api of that release, Java 21. Works with the
community branch plugin: nothing branch-specific in the code. Reads Java through the sonar-java frontend or its own
parser on `sonar.sources` plus `sonar.java.binaries` and `sonar.java.libraries`.

**Extension points to use.** `Metrics` for all metrics below, `Sensor` per file, one project-level `ProjectSensor`
for graph metrics (saved on `context.project()`), `MeasureComputer` only to add file values up to packages and
project, `PageDefinition` for one page, `PropertyDefinition` for every threshold.

## Rules for all metrics

- Main code only; test code excluded. Honour `sonar.exclusions`.
- "Code lines" = physical lines minus blank lines, comment-only lines and lines of `assert` statements.
- A "package" is a directory with Java files. "Own files" of a package = files directly in it, not in sub-packages.
- A "reference" from A to B = A calls a method of B, reads a field of B, or writes a field of B. Counted once per
  pair for coupling, once per occurrence for cycle metrics.
- Store counts and maxima as INT or FLOAT metrics, percentages as PERCENT. Mark every metric `qualitative(false)`,
  not hidden, so quality gates can use it. Domain Architecture or Tests.
- Thresholds are settings with the defaults below; a setting changes the measure, never the code.

## Metrics

Key prefix `arch_`. Every metric listed is required.

| Key | Level | Definition |
|---|---|---|
| `arch_methods` | file, sum up | number of methods and constructors with a body |
| `arch_code_lines` | file, sum up | code lines as defined above |
| `arch_cc_sum` | file, sum up | sum of method complexity, see below |
| `arch_cc_max` | file, max up | largest method complexity |
| `arch_cc_over_count` | file, sum up | methods whose complexity is above `sonar.arch.cc.threshold` (default 20) |
| `arch_method_lines_sum` | file, sum up | sum of code lines of all methods |
| `arch_class_lines_max` | file, max up | largest class size in code lines, nested and inner classes excluded from the outer class |
| `arch_cbo_max` | file, max up | largest number of distinct classes a class references, library classes included |
| `arch_dup_lines` | project | code lines inside duplicated blocks, see below |
| `arch_mfs_refs` | project | references in the minimum feedback arc set, summed over all levels of the package tree |
| `arch_cycle_refs_total` | project | all references between sibling packages, summed over all levels |
| `arch_cycle_nodes` | project | packages that are part of a cycle, summed over all levels |
| `arch_cycle_nodes_total` | project | all packages, summed over all levels |
| `arch_dep_parents` | project | packages that have at least 2 sub-packages |
| `arch_dep_parents_over` | project | parent packages whose sub-package graph has E − N + P above `sonar.arch.dep.complexity` (default 12) and a sub-package fan-out above `sonar.arch.dep.fanout` (default 9) |
| `arch_dep_fanout_max` | project | largest fan-out of a sub-package within its parent |
| `arch_pkg_size_max` | project | largest product of own-file code lines and own-file methods over all packages |
| `arch_nesting_topn_avg` | project | average maximum nesting depth of the `sonar.arch.nesting.topN` (default 10) deepest methods |
| `arch_unused_classes`, `arch_unused_methods`, `arch_unused_variables` | project | private or package-private items never referenced; with totals `arch_classes`, `arch_methods_total`, `arch_variables_total` |
| `arch_methods_covered`, `arch_methods_coverable` | file, sum up | from the JaCoCo XML METHOD counter (`sonar.coverage.jacoco.xmlReportPaths`) |

**Method complexity.** 1 + one per `if`, `for`, for-each, `while`, `do`, `catch`, `?:`, and one per whole `switch`
statement or expression (not per case). `&&` and `||` are not counted. Lambdas count inside their enclosing method.
Setting `sonar.arch.cc.switchCases=false` (default); `true` counts each case instead.

**Duplication.** Tokenise each file (identifiers, literals, operators, punctuation; whitespace and comments
dropped). A duplicate is a run of at least `sonar.arch.dup.tokens` (default 50) identical tokens that occurs more than
once in the project. The first occurrence is not counted; every further occurrence counts its code lines.
Overlapping duplicate blocks are merged before lines are counted. Do not reuse SonarQube's own CPD measures.

**Cycle metrics.** Build a graph per level of the package tree: nodes are the child packages of one parent, edges
are references between them (from any file below each child). For each graph, find a minimum feedback arc set
(fewest references whose removal breaks all cycles): exact for graphs up to 20 nodes, a documented greedy heuristic
above. Sum the numbers over all levels.

## Page

One project page "Architecture metrics": a table of the raw values with their definitions and the top 10 classes,
methods and packages behind every maximum (stored as a JSON DATA metric, `arch_top_offenders`).

## Tests

Every metric has a synthetic Java fixture with a hand-counted expected value, including edge cases (nested classes,
lambdas, switch expressions, records, enums, generated code marked `@Generated`, which is excluded). One end-to-end
test runs the plugin with the Sensor test harness on a small multi-package project with a known cycle.

## Out of scope

Scores, grades, colours, weights, quality gate definitions, any language other than Java.

## Done when

The jar installs on SonarQube 26.7 Community Build next to the branch plugin, all metrics appear in the Measures tab
per branch, and a quality gate condition can be added on `arch_cc_max`.
