# Branch coverage: JaCoCo reference and TeaCoCo contract

Status: **JaCoCo/browser probe parity and raw-data merging are integrated in opt-in Maven/Gradle `jacoco` mode. The 67-test GWT pilot passes with unchanged denominators.**
See [consumer configuration](jacoco-mode.md) and [measured pilot results](gwt-user-compat-pilot.md).
Recorded 2026-10-25. Reference tools: JaCoCo **0.8.15**, javac **21** with `--release 17`, TeaVM **0.16.0**.
JaCoCo 0.8.15 matches the version configured in the Instanto parent POM. The live JaCoCo documentation describes
its current development version, so the experiments and source inspection below pin 0.8.15 explicitly.

## Design correction: reuse the original bytecode model

Updated 2026-10-26 after reviewing the common input to JaCoCo and TeaVM. **Yes: JaCoCo can analyze the same original
class files to establish branch counts and control flow.** Counting those branches does not require recovering them
from optimized JavaScript or TeaVM IR. The remaining problem is obtaining execution evidence with the same identity.

The standalone experiment now reuses JaCoCo's instrumentation as well as its analyzer:

1. Keep the original selected class files as the reporting baseline.
2. Instrument compiler-input copies with JaCoCo 0.8.15's public `Instrumenter`, using a custom
   `IExecutionDataAccessorGenerator` to obtain a TeaCoCo-managed boolean probe array for each original class ID.
3. Compile those copies with stock TeaVM and collect the probe arrays from browser tests through TeaCoCo.
4. Feed the collected class IDs and probe bits into JaCoCo's execution-data/analyzer APIs, using the original files
   for reporting. Let JaCoCo apply its compiler filters and infer line/branch coverage from the probes.
5. Where JVM and browser runs use identical original class bytes and compatible instrumentation, merge their raw
   probe data before generating one report for Sonar. Do not attempt this union from per-line XML counts.

The prototype is verified on stock TeaVM 0.16.0 and real Chrome, using JaCoCo 0.8.15 and Java 17 class files.
JaCoCo's Java 11+ `ConstantDynamic` probe-array lookup is not accepted by TeaVM's `visitLdcInsn` parser. The small
`ProbeAccessAdapter` lowers only the recognized JaCoCo-generated lookup into an ordinary static method call; the
portable runtime returns the same boolean array for each original class ID. Probe placement and numbering remain
JaCoCo's. The unadapted offline instrumentation also executes on the JVM, and its probe bits match both the standard
JaCoCo agent and the adapted browser build exactly. No TeaVM fork, source-map setting or application annotation is used.

This is **a verified experiment, not a released Maven/Gradle or TeaVMTestRunner integration**. Interface initialization,
multiple browser realms, asynchronous/failing tests, application scale, and packaging still need validation in this mode.
Generated or transformed application classes also need a clearly defined baseline; matching source files alone do not
establish matching class identities.

This mode measures the original selected Java bytecode. A never-emitted method still contributes missed coverage to
that baseline; it may additionally be identified as omitted using compiler evidence. Probe writes can affect optimization,
so the instrumented build must not be used as proof of the uninstrumented production build's retained code. An emitted-code
view remains a separate useful diagnostic. Neither its denominator nor its branch identities should be silently mixed
with the original-bytecode metric.

The earlier emitted-IR design below remains a possible separate view. The successful common-probe experiment makes
JaCoCo the preferred accounting implementation; there is no need to reimplement its compiler filters or infer original
branch identity from emitted graphs. Debug line information in the class files supplies Java source
locations for JaCoCo reporting; JavaScript source maps are not the branch-identity mechanism for this route.

Sources: [offline instrumentation](https://www.jacoco.org/jacoco/trunk/doc/offline.html),
[Instrumenter 0.8.15](https://github.com/jacoco/jacoco/blob/v0.8.15/org.jacoco.core/src/org/jacoco/core/instr/Instrumenter.java),
[runtime accessor SPI 0.8.15](https://github.com/jacoco/jacoco/blob/v0.8.15/org.jacoco.core/src/org/jacoco/core/runtime/IExecutionDataAccessorGenerator.java),
[probe storage selection 0.8.15](https://github.com/jacoco/jacoco/blob/v0.8.15/org.jacoco.core/src/org/jacoco/core/internal/instr/ProbeArrayStrategyFactory.java).

## Run the browser experiment

With JDK 21, Maven and Google Chrome installed:

```sh
python3 scripts/check_jacoco_teavm.py
```

The script first runs the genuine JaCoCo-agent oracle, then compiles the **same fixture source** in the independent
`research/jacoco-teavm-prototype` module. It compares complete per-class probe arrays (not just counts) for A, B and
UNION across the agent, custom JVM runtime and browser. It runs TeaVM SIMPLE, ADVANCED and FULL optimization, with
FULL also minified, and verifies that JVM A plus browser B equals UNION. The short-circuit, loop, shared-switch,
fall-through, colliding-string-switch and exception cases use the existing reference inputs.

Outputs under `research/jacoco-teavm-prototype/target/proof/` include ordinary JaCoCo `.exec` and XML files. Therefore
Sonar can import a merged report through its existing JaCoCo importer; no new Sonar format is needed for this approach.
The integrated fixture now has a [live Sonar import verification](sonar-import-pilot.md): exact overall counters and the combined calculation match; a new-code baseline test remains separate.

### Compiler evidence and the execution denominator

The experiment separately compiles an **uninstrumented** application at each optimization level. It records:

* `reachability.tsv`: original method/source-line inventory joined to TeaVM's dependency analysis. An absent method
  is marked `unreachable-by-dependency-analysis`. Inlining is not inferred from method absence in generated JavaScript.
* `emission.tsv`: source locations and inline call sites observed by a read-only listener on post-optimization IR at
  JavaScript rendering. These are source-location observations, not a complete original-instruction/branch mapping.
* `*-coverage-and-emission.json`: original JaCoCo coverage with compiler evidence per line, plus a separate
  reachable-bytecode diagnostic excluding only lines owned entirely by unreachable methods. Shared lines are retained.

All three optimization levels give the same measured result for this fixture:

| View | Covered lines | Covered branches |
| --- | ---: | ---: |
| Original bytecode, browser UNION or merged JVM A/browser B | 31 / 32 | 23 / 25 |
| Reachable-bytecode diagnostic, excluding the proven unreachable method | 31 / 31 | 23 / 23 |

The missing original line and two outcomes belong to `Decisions.unreachable`. They remain missed in the ordinary
JaCoCo XML; the diagnostic does not change Sonar's denominator. Other lines with no emitted source location are
explicitly marked `no-emitted-location-not-proof-of-removal`, because an optimizer can move/fold instructions and
line mappings can be incomplete. Exact elimination of individual outcomes within a retained method is **not yet
established** by this evidence. The reachable-bytecode diagnostic is not claimed to be exact emitted-code coverage.

## Coverage semantics

Implement **C1 branch coverage** alongside line coverage. Count normal decision outcomes, preserve branch identity
until aggregation, and export Sonar's branch fields only from a coherent branch inventory. Do not equate a line hit,
a visited destination block or a count of test cases with a covered branch.

Two scopes must remain distinguishable:

* **Original Java bytecode scope:** the branches in the selected input class files after JaCoCo's filters. This is
  the reference model for JVM comparison; an uncalled method still has missed branches.
* **Emitted TeaVM build scope:** the application decisions retained in a particular compiled build. Branches removed
  by optimization are separate diagnostics, not evidence that a test covered them.

Meaningful coverage of emitted code and comparison with JVM coverage are distinct goals. Their percentages must not
be presented as identical. An exact cross-runtime merge requires either common original-bytecode probes or proven
provenance mapping, plus an explicit common denominator. Missing provenance must be visible rather than inferred
from a source line number.

## What JaCoCo actually counts

JaCoCo works from Java bytecode and debug metadata, not a source AST. Its C1 counter reports instructions with at
least two outgoing normal-flow alternatives. Conditional bytecode jumps have two outcomes. Switch instructions
count distinct destination labels, including the default destination. An unconditional jump is not a decision.

Consequences verified in the executable reference:

* A simple `if` and a ternary each have two branches.
* Short-circuit `a && b` or `a || b` has two conditional jumps: four branches, not merely two outcomes for the whole
  expression. Some inputs do not evaluate `b`.
* Loops contribute their conditional decision outcomes. Iteration counts do not multiply the denominator.
* Several switch labels sharing one destination count as one outcome. Entering a later case by fall-through does
  not prove that its selector outcome was taken.
* Exception-handler edges are excluded from C1. A try/catch alone contributes no branches. Explicit `if` statements
  in a handler still count normally.
* This is neither all-path coverage nor modified condition/decision coverage (MC/DC).

Sources: [counter definitions](https://www.jacoco.org/jacoco/trunk/doc/counters.html),
[Instruction.getBranchCounter](https://github.com/jacoco/jacoco/blob/v0.8.15/org.jacoco.core/src/org/jacoco/core/internal/analysis/Instruction.java),
[MethodAnalyzer switch handling](https://github.com/jacoco/jacoco/blob/v0.8.15/org.jacoco.core/src/org/jacoco/core/internal/analysis/MethodAnalyzer.java).

### Compiler-generated branches and filters

Matching JaCoCo requires more than counting every IR conditional. JaCoCo applies filters for compiler-generated
structures. For example, javac implements a string switch with hash lookup, equality checks and a second switch;
JaCoCo's filter removes the hash/equality scaffolding from its counter. Our collision fixture (`"FB"` / `"Ea"`)
still reports only three branch destinations, including default.

Other language/compiler patterns need explicit review: enum and pattern switches, assertions, synthetic methods,
try/finally duplication, try-with-resources, records, and Kotlin-generated control flow. A source line attached to a
synthetic instruction is not enough to classify it as an application decision. We must not claim complete JaCoCo
filter parity based only on the initial Java fixtures.

Source: [StringSwitchJavacFilter](https://github.com/jacoco/jacoco/blob/v0.8.15/org.jacoco.core/src/org/jacoco/core/internal/analysis/filter/StringSwitchJavacFilter.java).

## Reproducible evidence

Run from the TeaCoCo repository with JDK 21 and Maven:

```sh
python3 scripts/check_jacoco_branch_reference.py
```

This runs real JaCoCo agent measurements for input sets A, B and their union. It also merges the A/B `.exec` files
and verifies that the resulting coverage XML, apart from session metadata, exactly matches executing both input
sets together. Logs, XML, raw execution files and `results.json` are written under `target/branch-reference/`.
The fixture is independent of the published modules and is never deployed.

| Construct/method | A covered | B covered | A + B covered | Total branches |
| --- | ---: | ---: | ---: | ---: |
| Simple if | 1 | 1 | 2 | 2 |
| Short-circuit AND | 2 | 3 | 4 | 4 |
| Short-circuit OR | 2 | 3 | 4 | 4 |
| Ternary | 1 | 1 | 2 | 2 |
| Loop | 2 | 1 | 2 | 2 |
| Switch with shared labels | 1 | 2 | 3 | 3 |
| Switch with only one destination | 0 | 0 | 0 | 0 |
| Switch fall-through | 1 | 2 | 3 | 3 |
| String switch with hash collision | 1 | 2 | 3 | 3 |
| Try/catch without a conditional | 0 | 0 | 0 | 0 |
| Condition removed by javac | 0 | 0 | 0 | 0 |
| Never-called conditional method | 0 | 0 | 0 | 2 |

The complementary simple-if case is particularly important: both A and B individually say “1 of 2”, while together
both branches are covered. The counts alone cannot tell whether the reports cover the same or opposite outcomes.

## How JaCoCo obtains the answer efficiently

JaCoCo does not insert a probe on every source statement or edge. It uses a boolean probe array and analyzes control
flow to infer which edges and instructions were executed. Probes at exits and relevant joins allow backward
propagation; additional placements reduce misleading results when invoked methods throw before reaching a later probe.

Probe state means “executed at least once”, not execution frequency. JaCoCo identifies original class definitions
using a class-file checksum and merges matching execution data by probe identity. Analysis must use the original
matching classes, not a different compilation or the already instrumented class files.

The integrated original-bytecode mode reuses JaCoCo's own probe placement; it does not implement a second minimal-probe algorithm.

Sources: [control-flow/probe strategy](https://www.jacoco.org/jacoco/trunk/doc/flow.html),
[class identity design](https://www.jacoco.org/jacoco/trunk/doc/implementation.html),
[MethodProbesAdapter](https://github.com/jacoco/jacoco/blob/v0.8.15/org.jacoco.core/src/org/jacoco/core/internal/flow/MethodProbesAdapter.java).

## Sonar: thresholds and the limits of merging XML

Sonar imports JaCoCo XML via `sonar.coverage.jacoco.xmlReportPaths` and generic XML via `sonar.coverageReportPaths`.
These contribute to its normal coverage/new-code metrics and therefore existing quality-gate thresholds. Its coverage
metric combines covered lines and covered branch outcomes over the corresponding totals; it is not the average or
sum of two tool percentages. New-code coverage restricts that calculation to new/changed source lines.

The scanner stores overlapping coverage by source line as follows:

* Line execution: logical OR.
* Number of branch outcomes: maximum supplied count.
* Number of covered branch outcomes: maximum supplied count.

Therefore importing A=`1/2` and B=`1/2` gives **`1/2`, not the true `2/2` union** in the complementary-if example.
This can undercount complementary tests. If the compilers describe different branch universes on the same line,
maximum counts are not even evidence of a meaningful common branch denominator.

This behavior was checked in the public scanner storage source and in the locally cached
`sonar-scanner-engine-community-13.4.1.4007.jar` using `javap -p -c`: coverage-storage lambdas use boolean OR and
`Math.max`. Its cache content hash is `826dd133d6bf6e9c71c1bf81aed2dcf7743862912a8ac7af2c4e9a60fda16107`.
The server reported version `26.8.0.126808`. This paragraph records implementation inspection; the later [live import check](sonar-import-pilot.md) verifies the integrated standard JaCoCo XML counters and overall calculation.

JaCoCo XML and Sonar generic XML contain counts per source line, not the identity of each covered outcome. An exact
JVM/browser branch union cannot be reconstructed from these XML files alone. We need raw JVM probe evidence,
matching original classes, browser branch IDs and a proven correspondence. JaCoCo's public aggregate counters alone
also do not expose that mapping; relying on internal analyzer/filter APIs would require version-pinned adapters and tests.

Recommended integration policy:

1. Continue combining JVM and browser **line hits**; do not change the threshold merely to make the pilot green.
2. For browser branches, first report one explicitly identified compiled branch universe with a single authoritative
   count per line. Do not add counts across builds or assume `(file, line)` identifies a decision.
3. Where JVM and browser branch domains overlap, implement an identity-aware merge before Sonar export. If identities
   cannot be reconciled, retain separate diagnostics and state the unsupported overlap rather than fabricate a union.
4. Once a unified generic report owns the combined branch metric, avoid also importing incompatible JaCoCo branch
   totals for those same files. An explicit coverage ownership policy is needed; it must not silently drop missed code.
5. Prove the installed scanner's behavior using complementary branch inputs before relying on the combined gate.

Sources: [generic coverage schema](https://docs.sonarsource.com/sonarqube-server/analyzing-source-code/test-coverage/generic-test-data),
[metric definitions](https://docs.sonarsource.com/sonarqube-server/user-guide/code-metrics/metrics-definition#coverage),
[scanner storage, pinned public source](https://github.com/SonarSource/sonarqube/blob/9.9.0.65466/sonar-scanner-engine/src/main/java/org/sonar/scanner/sensor/DefaultSensorStorage.java),
[JaCoCo report importer](https://github.com/SonarSource/sonar-jacoco/blob/master/src/main/java/org/sonar/plugins/jacoco/ReportImporter.java).

## Earlier emitted-IR design (separate coverage scope, not the primary implementation)

### Inventory and identity

Keep compilation UUIDs for transporting counter arrays, but add a versioned decision/outcome inventory. Record the
compiler version, optimization configuration, source/class fingerprints, containing method, source location, decision
identity, outcome identity and relevant inline context. Bind each runtime bit to exactly one inventory outcome.

A UUID plus probe index identifies a runtime counter, not a portable source branch. Multiple decisions can occupy
one line, methods can be inlined/cloned, and separate runner compilations can optimize the same method differently.
Use an optimized-graph fingerprint to delimit the initial branch universe. Merge by stable outcome identity only when
the graph/provenance matches; do not sum ordinary/minified/optimized compilations into inflated source totals.

TeaVM 0.16.0's public `TextLocation` exposes file, line and inlining information, but no original bytecode instruction
offset. Exact JaCoCo-compatible provenance through optimization is therefore **not established by the current SPI**.
A method/line/ordinal heuristic is insufficient. For this emitted-code scope, investigate a supported provenance hook
upstream. For the original-bytecode scope, evaluate JaCoCo probes before TeaVM compilation as described above; that
route can preserve common probe identity without recovering it from transformed graphs. Keep any effect of those
probes on optimization explicit rather than treating this as a measurement of uninstrumented emitted code.

### Edge instrumentation

The public IR has `BranchingInstruction`, `BinaryBranchingInstruction` and `SwitchInstruction` with explicit targets.
Instrument normal decision edges after the selected optimization stage:

1. Collect decisions and group duplicate switch destinations before changing the graph.
2. Split each counted edge with a small probe block, then jump to the original destination. Do not put the probe at
   a shared target: another predecessor or switch fall-through would falsely mark that decision outcome as covered.
3. Repair phi incoming-source references for the split predecessor edges. Preserve exception-region semantics and
   SSA validity; probe operations must not throw or change application behavior.
4. Record true/false or distinct switch destination hits, excluding exceptional edges and tool/runtime scaffolding.
5. Associate hits with decision/outcome IDs, then map to source lines for presentation.

Count outcomes, not explicit `break`, `continue`, returns or unconditional back edges. If optimization removes the
decision or leaves only one destination, report that separately. Validate later JavaScript optimization/inlining so
instrumentation does not create a misleading denominator or alter application semantics.

### Collection and export

Retain hit bits rather than counts, and OR repeated observations of the same outcome across test cases. Keep class
initialization hits, async completion, failed-test capture and browser-realm collection behavior from line coverage.
Introduce a versioned schema for branch metadata; reject mismatched inventories and unknown outcome IDs.

For each source line with a valid branch inventory, emit:

```xml
<lineToCover lineNumber="42" covered="true" branchesToCover="4" coveredBranches="3"/>
```

Lines without decisions omit branch attributes. Do not infer branch coverage from line coverage. Do not count omitted
branches as covered or claim unexecuted compiled outcomes are omitted. Keep the source-level and emitted-build scopes
explicit in diagnostics and in the Sonar import policy.

### Acceptance gates before publishing branch coverage

* Differential fixtures against this JaCoCo oracle where the decision graphs are equivalent.
* Tests for edge joins, shared switch targets/defaults, fall-through, nested/short-circuit expressions, loops and phi repair.
* String/enum/pattern-switch and other compiler-filter parity tests, with unsupported patterns identified explicitly.
* Ordinary/minified/optimized TeaVM runs, repeated runner frames, asynchronous tests, failure paths and separate realms.
* A test proving that complementary reports merge to `2/2` only when outcome identity is available; unknown identities fail.
* Omitted-method/constant-condition tests proving instrumentation does not retain dead application code.
* A real Sonar import checking line hits, branch totals, new-code metrics and threshold evaluation.
* Runtime, compilation-time and bundle-size measurements on the first real module before enabling it routinely.

## First real-world exercise (initial selection)

Use `gwt-user-compat`, measured by `gwt-user-compat-tests` in `instanto-io/teavm-compat`. The existing local results
contain 67 passing tests in 14 TeaVMTestRunner suites using `@SkipJVM`, covering widgets, lifecycle, scheduling,
browser APIs and generated bindings.

On 2026-10-25, generating a JaCoCo report from that existing execution file against the compiled runtime classes
reported **0/6,341 lines and 0/2,230 branches** across 326 classes. This is an existing-build baseline, not a newly run
clean comparison. The TeaCoCo pilot must rerun both measurements from a pinned clean revision and report each tool's
source scope; the emitted TeaVM denominator may be smaller. Do not replace the repository's existing source-map
coverage pipeline or alter its Sonar gates merely to run this experiment.

The clean pinned comparison was subsequently completed on 2026-10-26: see
[the pilot record](gwt-user-compat-pilot.md). Its updated source revision has 6,344 lines;
the JVM and browser measurements agree exactly on that denominator.
