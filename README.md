# Tea-Coco

Tea-Coco measures Java source coverage from tests running in the browser. It adds
coverage probes when TeaVM compiles Java to JavaScript, collects their results,
and produces coverage reports for Sonar. Opt-in JaCoCo mode adds original-bytecode
line and branch coverage and exact raw JVM/browser probe merging.

**Alpha — `0.1.0-SNAPSHOT`.** The extension targets **TeaVM 0.16.0** and is intended
for evaluation in coverage test builds. It works with the stock TeaVM compiler
and `TeaVMTestRunner`. Maven Central publication is planned; the current snapshot
can be installed from source.

## What is implemented

- Java source line coverage from actual browser execution.
- Opt-in JaCoCo line/branch coverage with a shared JVM/browser probe identity.
- Opt-in instrumentation restricted to the source roots you select.
- Collection through the existing TeaVM JUnit runner, including failed tests.
- Merging of browser results across test compilations and JVM forks.
- Sonar generic coverage XML and a separate diagnostic report for source
  locations that were examined but not emitted into JavaScript.
- A Maven report goal, a Gradle plugin and a reporter for custom test harnesses.

The examples verify ordinary, minified and optimized JavaScript builds in Chrome.
The instrumentation uses compiler metadata and does not depend on source maps or
Chrome DevTools coverage APIs.

## Line and branch coverage

Use `mode=jacoco` for JaCoCo-compatible counters. Tea-Coco prepares test-only class
copies, preserves originals for reporting, and collects probes through the stock
runner. It leaves packaged application JARs unchanged. The
[JaCoCo mode guide](docs/jacoco-mode.md) has complete Maven and Gradle configuration,
JVM `.exec` merging, failure handling and compiler-diagnostic limits.

The first real [GWT compatibility pilot](docs/gwt-user-compat-pilot.md) passed all
67 browser tests and covered **1,764/6,344 lines and 499/2,230 branches**, compared
with zero coverage from the JVM-only measurement of those browser tests. Both
reports retain the same original-bytecode denominator. A [live Sonar import](docs/sonar-import-pilot.md)
also matched the fixture's exact line/branch counts and combined percentage.

The remaining introductory examples describe the backward-compatible default
`lines` mode, which reports only the source locations present in compiled tests.

## Add line-only coverage to Maven tests

Start with a project whose TeaVM browser tests already run successfully. Keep its
existing TeaVM JUnit dependencies, browser selection and runner configuration.
Add Tea-Coco as a test dependency:

```xml
<dependency>
  <groupId>io.instanto</groupId>
  <artifactId>tea-coco</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <scope>test</scope>
</dependency>
```

Enable coverage in the existing Surefire `systemPropertyVariables`:

```xml
<teavm.coverage.enabled>true</teavm.coverage.enabled>
<teavm.coverage.sourceRoots>${project.basedir}/src/main/java</teavm.coverage.sourceRoots>
<teavm.coverage.dataDirectory>${project.build.directory}/teavm-coverage</teavm.coverage.dataDirectory>
```

Coverage is disabled unless explicitly enabled. Source roots identify the
application or library code to measure. Separate multiple roots with the
platform path separator: `:` on Unix or `;` on Windows. Each module should use
its own data directory.

Keep JUnit's captured stdout, which carries browser results. Coverage builds
must use non-incremental TeaVM compilation. The
[consumer guide](docs/consumer-guide.md) includes a complete opt-in Maven profile
and examples for tests in a separate module.

## Generate a report

Add the reporting plugin to the project's build:

```xml
<plugin>
  <groupId>io.instanto</groupId>
  <artifactId>tea-coco-maven-plugin</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <executions>
    <execution>
      <goals><goal>report</goal></goals>
    </execution>
  </executions>
</plugin>
```

Run `mvn clean verify`. The report goal runs in the `verify` phase and reads both
Surefire and Failsafe XML. Its defaults use the module's compile source roots and
produce these files:

| Output | Contents |
| --- | --- |
| `target/teavm-coverage.xml` | Sonar generic coverage report |
| `target/not-emitted.json` | Examined source locations with no emitted probe |
| `target/teavm-coverage/*.json` | Per-compilation probe baselines |

Set `sonar.coverageReportPaths=target/teavm-coverage.xml` when analysing the module.
The plugin does not change Sonar exclusions or quality gates. Its `sourceRoots`,
`dataDirectory`, `surefireReports`, `failsafeReports` and `outputFile` settings
allow different layouts; keep the compiler and reporter source roots aligned.

A failed browser test still flushes coverage before the runner reports failure.
Maven may stop before `verify`; invoke the plugin’s `report` goal separately
when inspecting such a run. Reporting preserves the test outcome and rejects
missing or malformed browser evidence.

## Read the coverage results

A source line is **covered** when at least one probe on that line was hit. It is
**uncovered** when the compiled build contains a probe for it but no collected
test hit that probe.

**Not emitted** means a source location was examined during compilation but had
no probe in any compiled test configuration. This includes optimization effects;
it does not necessarily mean the source is unused. These locations are recorded
in the diagnostic JSON rather than as zero-hit entries in the Sonar report.
Classes never reached by dependency analysis are outside the inventory.

Line coverage does not show whether every path through a condition was taken.
Select the opt-in [JaCoCo mode](docs/jacoco-mode.md) for branch coverage and the
full original-class denominator. Its Maven/Gradle adapters also emit standard raw
execution data and compiler diagnostics. The [design notes](docs/branch-coverage-design.md)
explain the shared identity and the separate emitted-code scope. Run
`python3 scripts/check_jacoco_teavm.py` with JDK 21, Maven and Chrome to verify
JVM/browser probe parity independently.

Probes are inserted after TeaVM's intermediate-code optimization. They do not
make unreachable application methods reachable. Results from the same line are
merged across compiled tests. Start with a clean build to avoid mixing evidence
from separate invocations; the reporter checks compilation identities, probe
counts and source paths.

## Use with Gradle

The `io.instanto.tea-coco` plugin works alongside TeaVM's library or application
plugin. It instruments an existing JUnit `Test` task and provides `teaCocoReport`:

```kotlin
plugins {
    id("io.instanto.tea-coco") version "0.1.0-SNAPSHOT"
}

teaCoco {
    enabled.set(true)
    testTask.set("test")
    sourceRoots.setFrom(layout.projectDirectory.dir("src/main/java"))
}
```

This is added to a build with TeaVM and snapshot plugin resolution already
configured. Run `./gradlew teaCocoReport` to execute the selected tests and write
`build/reports/tea-coco/coverage.xml`. The selected test task also finalizes with
reporting when run directly. See the [Gradle guide](docs/gradle.md) for complete
configuration, task behavior and current limitations.

## Custom browser harnesses

The compiler extension is discovered through TeaVM's plugin service. Custom
harnesses use the same coverage properties, keep the baselines from that
compilation and collect `globalThis.__teavmCoverageV1` after test work completes.
The reporter can then generate the same XML from the collected results.

The [consumer guide](docs/consumer-guide.md#applications-with-playwright-or-another-browser-harness)
describes the transport format, `CoverageRuntime.flush()`, reporter entry points
and failure handling. These details matter when using Playwright or an
application harness instead of `TeaVMTestRunner`.

## Build and limitations

Use JDK 21+, Maven 3.9+ and Chrome for the example tests:

```sh
mvn -B -ntp -s central-settings.xml clean install
python3 scripts/smoke_consumer.py
```

The source build uses public Maven dependencies and stock TeaVM 0.16.0. The
example outputs are under `example/target/`. The Gradle adapter has its own
build; see the [Gradle build instructions](docs/gradle.md#build-locally).

Validation currently covers the JavaScript backend, non-incremental compilation
and Chrome. Later TeaVM versions need compatibility testing. WebAssembly and C
are outside the supported scope. Native JavaScript bodies, generated intrinsic
internals and code without source locations are outside Java line coverage.
Instrumentation adds overhead and should be enabled for coverage testing only.

## Source and license

The implementation uses TeaVM's public plugin SPI and intermediate-code model,
and JaCoCo's public instrumentation, execution-data and reporting APIs in `jacoco` mode.
The probe approach is informed by GWT's compiler coverage design; no GWT compiler
code is included. The Java package is `io.instanto.teavm.coverage`; configuration
properties use the `teavm.coverage.*` prefix.

Apache License 2.0; see [LICENSE](LICENSE).
