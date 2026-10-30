# TeaCoCo

TeaCoCo brings JaCoCo-compatible Java coverage to tests executed in a browser
through TeaVM. Use JaCoCo for tests running on the JVM, TeaCoCo for tests running
in Chromium, or both for one report covering both runtimes.

**Alpha — `0.1.0-SNAPSHOT`, TeaVM `0.16.0`.** TeaCoCo works with the stock compiler
and `TeaVMTestRunner`. Maven Central publication is planned; the current snapshot
can be [built and installed from source](docs/building.md).

## Choose your test setup

| Where application code executes | Coverage tool | Result |
| --- | --- | --- |
| Only the JRE/JVM | JaCoCo | Ordinary JVM line and branch coverage; TeaCoCo is unnecessary. |
| Some tests on the JVM, others in Chromium | JaCoCo + TeaCoCo in `jacoco` mode | One report from the union of JVM and browser probe hits. |
| Only Chromium | TeaCoCo in `jacoco` mode | Browser line and branch coverage against the original Java classes. |

**JaCoCo mode is the default.** Binding the coverage goals enables collection;
there is no separate enable switch. The optional `lines` mode produces
line-only Sonar generic XML and has a different coverage denominator; see
[line-only mode](#alternative-line-only-mode).

## How it works

Test runners execute tests; coverage tools record which application code those
tests reach. An ordinary JUnit test executes on the JVM. `TeaVMTestRunner` runs
on the JVM too, but compiles the browser test to JavaScript and launches it in
Chromium. A JVM JaCoCo agent cannot observe that JavaScript execution.

TeaCoCo prepares instrumented **test-only copies** of Java classes before TeaVM
compiles them. The probes execute in the browser, and TeaCoCo collects their hits
through the stock runner's captured output. It reports against preserved original
class files, leaving `target/classes` and packaged application JARs unchanged.
Application code needs no coverage annotations or manual probe calls.

| Responsibility | JaCoCo on the JVM | TeaCoCo in `jacoco` mode |
| --- | --- | --- |
| Prepare coverage | `prepare-agent` supplies the test JVM's agent argument. | `prepare` creates class copies and configures the selected browser test launcher. |
| Record execution | The JVM agent records probe hits. | Compiled probes record browser hits; the stock runner carries them in JUnit output. |
| Produce counters | `report` analyzes original classes using JVM execution data. | `report` uses the same JaCoCo analysis on preserved originals and browser hits, optionally merged with JVM data. |
| Code counted by default | The module's main classes. | The module's main classes matched to its compile source roots, including selected classes never reached by a browser test. |
| Report files | HTML, XML and CSV; raw data in `target/jacoco.exec`. | Standard JaCoCo XML and raw `.exec` data; HTML can be rendered with JaCoCo. |

For mixed execution, both sides must use the **same original class bytes** and
JaCoCo version (TeaCoCo currently uses **0.8.15**). Matching probe hits are merged
before counters are calculated: a hit in either runtime counts once. Adding two
XML reports or their percentages cannot establish that union. Never-called
methods and classes remain missed; compiler diagnostics do not remove them from
the report. JaCoCo's branch definition applies, rather than every possible path.

## Maven plugin: get a coverage report

The [Maven guide](docs/maven.md) contains the complete configuration for all three
scenarios. The browser recipes assume your TeaVM tests already run in Chromium;
keep their TeaVM dependencies and browser configuration. TeaCoCo adds coverage,
not browser-test setup.

For browser coverage, bind `prepare` and `report` from
`io.instanto:teacoco-maven-plugin:0.1.0-SNAPSHOT`. Preparation supplies the runtime,
coverage settings and prepared test classpath automatically. You do not need to
add `teacoco` as a test dependency unless your own code imports its APIs.
The consumer setup and reporting use Maven or Gradle; Python is not required.

### 1. Only JRE-executable tests: use JaCoCo

Add this plugin under `build/plugins` in the module containing your JVM tests:

```xml
<plugin>
  <groupId>org.jacoco</groupId>
  <artifactId>jacoco-maven-plugin</artifactId>
  <version>0.8.15</version>
  <executions>
    <execution><id>jvm-agent</id><goals><goal>prepare-agent</goal></goals></execution>
    <execution><id>jvm-report</id><goals><goal>report</goal></goals></execution>
  </executions>
</plugin>
```

Run `mvn clean verify`. With ordinary forked Surefire tests, JaCoCo collects
`target/jacoco.exec` and writes `target/site/jacoco/index.html` and
`target/site/jacoco/jacoco.xml`. If you already configure Surefire's `argLine`,
retain JaCoCo's argument as described in the
[JVM-only recipe](docs/maven.md#1-only-jre-executable-tests).
No TeaCoCo dependency or browser is required.

### 2. Mixed JVM and Chromium tests: use both

Use separate test executions so each runtime gets the appropriate coverage setup.
The [complete mixed recipe](docs/maven.md#2-mixed-jvm-and-chromium-tests) uses:

1. Surefire for `*JvmTest` classes against original application classes, with the
   JaCoCo agent.
2. TeaCoCo `prepare` at `pre-integration-test`, with `testPlugin=failsafe`, to
   prepare and configure the browser execution.
3. Failsafe for `*BrowserIT` classes using `TeaVMTestRunner`, the prepared copies
   and **no JVM agent**. Browser tests use `@SkipJVM` to avoid a second JVM run.
4. TeaCoCo `report` at `verify`, with JVM data supplied through `executionData`:

The TeaCoCo part of that setup is:

```xml
<plugin>
  <groupId>io.instanto</groupId>
  <artifactId>teacoco-maven-plugin</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <configuration>
    <testPlugin>failsafe</testPlugin>
  </configuration>
  <executions>
    <execution>
      <id>prepare-browser</id>
      <phase>pre-integration-test</phase>
      <goals><goal>prepare</goal></goals>
    </execution>
    <execution>
      <id>merged-report</id>
      <phase>verify</phase>
      <goals><goal>report</goal></goals>
      <configuration>
        <executionData>
          <file>${project.build.directory}/jacoco.exec</file>
        </executionData>
      </configuration>
    </execution>
  </executions>
</plugin>
```

With that recipe installed, run `mvn clean verify`. The combined report is
`target/teavm-coverage.xml`; its raw union is `target/teavm-coverage.xml.exec`.
`executionData` accepts additional compatible raw `.exec` files: usually JVM
data here, but also earlier TeaCoCo browser/merged data. Current browser hits
are collected automatically.

The report covers code reached by either set of tests, even when neither runtime
covers all branches by itself. Test names are a recipe convention, not a TeaCoCo
requirement. The [Mockatcha example](mockatcha-example/README.md) is a runnable
version of this arrangement.

### 3. Only Chromium tests: use TeaCoCo

Use the [browser-only profile](docs/maven.md#3-only-chromium-tests), or add
this plugin to your existing browser test build:

```xml
<plugin>
  <groupId>io.instanto</groupId>
  <artifactId>teacoco-maven-plugin</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <executions>
    <execution><goals><goal>prepare</goal><goal>report</goal></goals></execution>
  </executions>
</plugin>
```

Run `mvn clean verify`, or `mvn -Pbrowser-coverage clean verify` if using the
profile.
`prepare` runs at `process-test-classes`, the existing browser tests run at
`test`, and `report` runs at `verify`. The same
`target/teavm-coverage.xml` and `.xml.exec` files now contain **browser-only**
coverage. No JVM execution-data file or JaCoCo JVM agent is needed.

### Maven configuration reference

Put shared settings in the plugin's `<configuration>` so preparation and reporting
use the same sources, data directory and output path. Settings inside an execution
apply only to its goals, as with `executionData` in the mixed example above.

| Setting | Default | When to change it |
| --- | --- | --- |
| `mode` | `jacoco` | Set `lines` only for line-only generic XML. CLI: `-Dteacoco.mode=lines`. |
| `skip` | `false` | Use `-Dteacoco.skip=true` to skip both goals and automatic test configuration. |
| `testPlugin` | `surefire` | Set `failsafe` when it launches the browser tests; also move `prepare` to `pre-integration-test`. CLI: `-Dteacoco.testPlugin=failsafe`. |
| `testExecutionId` | All test executions of the selected plugin | Select one execution by its Maven `<id>` if that plugin launches other tests too. CLI: `-Dteacoco.testExecutionId=browser-tests`. |
| `sourceRoots` | The module's compile source roots | Supply matching library or generated-source directories when tests live elsewhere. |
| `dependencyIncludes` | Empty | In JaCoCo mode, add resolved dependencies by exact `groupId:artifactId` to measure library code. |
| `classRoots` | Empty; the module's main output is always an input | In JaCoCo mode, add other class directories or JARs. |
| `executionData` | Empty | Merge additional compatible raw `.exec` files in JaCoCo mode. Omit for browser-only coverage. |
| `dataDirectory` | `target/teavm-coverage` | Choose a dedicated directory below this module's build directory. Preparation starts a fresh run there. |
| `outputFile` | `target/teavm-coverage.xml` | Change the XML destination; raw JaCoCo output adds `.exec` to this path. |
| `surefireReports`, `failsafeReports` | `target/surefire-reports`, `target/failsafe-reports` | Point the reporter at custom JUnit XML output directories. Both are read by default. |

For example, a separate browser-test module can measure a library it already
depends on. Add this inside the TeaCoCo plugin alongside its goal bindings:

```xml
<configuration>
  <sourceRoots>
    <sourceRoot>${project.basedir}/../library/src/main/java</sourceRoot>
  </sourceRoots>
  <dependencyIncludes>
    <dependencyInclude>example:library</dependencyInclude>
  </dependencyIncludes>
</configuration>
```

The dependency must be resolved by Maven, and the source root must match its class
files. Use `<classRoots><classRoot>...</classRoot></classRoots>` for an additional
directory or JAR instead. Source roots select the report scope; they are directories
above package paths, not individual source files. There is no automatic reactor-wide
aggregation goal.

`prepare` defaults to `process-test-classes`; `report` defaults to `verify`.
Binding those goals is enough: no explicit mode, enable flag, runtime dependency,
or Surefire classpath override is required. A plugin declared only in
`pluginManagement` does not execute. Use a Maven profile to make the goal bindings
optional, or `-Dteacoco.skip=true` to keep the profile active without coverage.

### Read or import the report

| Scenario | XML for Sonar | Raw execution data |
| --- | --- | --- |
| JVM only | `target/site/jacoco/jacoco.xml` | `target/jacoco.exec` |
| Mixed | `target/teavm-coverage.xml` | `target/teavm-coverage.xml.exec` (union) |
| Chromium only | `target/teavm-coverage.xml` | `target/teavm-coverage.xml.exec` (browser) |

All three use `sonar.coverage.jacoco.xmlReportPaths`. For mixed coverage, import
the single combined XML for these sources. TeaCoCo does not change Sonar gates or
exclusions.

TeaCoCo currently writes XML and raw data, **not HTML automatically**. For the
single-module recipes above, generate the familiar JaCoCo HTML report after
TeaCoCo has finished:

```sh
mvn org.jacoco:jacoco-maven-plugin:0.8.15:report \
  -Djacoco.dataFile=target/teavm-coverage.xml.exec
```

Open `target/site/jacoco/index.html`. This renders the browser or combined data
against the unchanged module classes; it replaces any earlier JVM-only report
in that directory. Do not clean between collecting coverage and rendering it.
For dependency classes or separate test modules, use the
[scope and reporting guidance](docs/maven.md#report-scope-and-failures).

Keep captured JUnit stdout and use non-incremental TeaVM compilation. A failed
test remains a failed build. If Surefire stops Maven before `verify`, run only
the report goal against the existing evidence; the [Maven guide](docs/maven.md#report-scope-and-failures)
shows the command and explains failure handling.

## Gradle plugin

Use Gradle's standard `jacoco` plugin for JVM-only tests. For browser coverage,
apply **`io.instanto.teacoco`** alongside TeaVM's `org.teavm.library` or `org.teavm`
plugin. TeaCoCo configures one existing `Test` task per project; it does not create
browser tests or instrument an application's `generateJavaScript` task.

### Resolve and apply the plugin

For the current alpha, [build and install the artifacts locally](docs/gradle.md#build-locally).
Add local resolution in `settings.gradle.kts` so Gradle can find the plugin marker:

```kotlin
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
    }
}
```

Then use this in `build.gradle.kts`, retaining your project's existing TeaVM
dependencies and test settings:

```kotlin
plugins {
    java
    id("org.teavm.library") version "0.16.0" // or org.teavm for an application
    id("io.instanto.teacoco") version "0.1.0-SNAPSHOT"
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.teavm:teavm-jso-impl:0.16.0")
}

tasks.test {
    useJUnit()
    systemProperty("teavm.junit.js", "true")
    systemProperty("teavm.junit.js.runner", "browser-chrome")
}
```

No `teaCoco` block is needed for the defaults: coverage is enabled in `jacoco`
mode for `test`, measuring the main source set's Java classes. Browser tests use
`TeaVMTestRunner` and `@SkipJVM`. The plugin supplies its runtime automatically.

### Run tests and collect the report

Run `./gradlew teaCocoReport`, or run the selected browser test task directly.

| Task | What it does |
| --- | --- |
| `teaCocoPrepare` | Prepares fresh test-only class copies in JaCoCo mode before the selected browser task. |
| Selected `Test` task (`test` by default) | Runs the browser tests with coverage and captures their JUnit output. |
| `teaCocoCollect` | Writes the report from existing evidence; also runs as the selected test's finalizer. Invoking it alone does not run tests. |
| `teaCocoReport` | Runs the selected tests and collection together. |

The default outputs are `build/reports/teacoco/coverage.xml`,
`coverage.xml.exec` and `compiler-evidence.json`. Import the XML with
`sonar.coverage.jacoco.xmlReportPaths`. TeaCoCo does not generate HTML itself;
Gradle's `JacocoReport` can render the raw data against the same original classes.

Collection runs after an ordinary test failure, and the build still fails.
Coverage runs deliberately rerun the selected test task rather than reuse cached
test results. Configuration cache is not supported for these tasks yet.

### Gradle configuration reference

Configure these properties in a `teaCoco { ... }` block in `build.gradle.kts`:

| Property | Default | Example or purpose |
| --- | --- | --- |
| `enabled` | `true` | Opt out with `-PteaCoco.enabled=false`; no explicit enable setting is needed. |
| `mode` | `jacoco` | `mode.set("lines")` selects line-only generic XML. |
| `testTask` | `test` | `testTask.set("browserTest")` selects another existing `Test` task. |
| `sourceRoots` | Main source set's Java directories | `sourceRoots.setFrom(file("../library/src/main/java"))` replaces the measured source roots. |
| `classRoots` | Main output class directories | `classRoots.from(files("../library/build/classes/java/main"))` adds inputs in JaCoCo mode. Arrange for those classes to be built first. |
| `executionData` | Empty | `executionData.from(layout.buildDirectory.file("jacoco/jvmTest.exec"))` adds compatible raw JaCoCo data. |
| `dataDirectory` | `build/teacoco/data` | `dataDirectory.set(layout.buildDirectory.dir("coverage-data"))` chooses a dedicated directory that TeaCoCo can clear. |
| `reportFile` | `build/reports/teacoco/coverage.xml` | `reportFile.set(layout.buildDirectory.file("reports/browser/coverage.xml"))` moves the report and its companion files. |
| `version` | `0.1.0-SNAPSHOT` | Runtime/reporter version; keep it aligned with the applied TeaCoCo plugin. |

Use `setFrom(...)` to replace a file collection and `from(...)` to add to it.
Sources and classes must come from the same revision. Each project needs its own
data directory and report destination. One selected browser task is supported;
there is no automatic cross-project aggregation.

To run ordinary tests without TeaCoCo, use
`./gradlew test -PteaCoco.enabled=false`. An explicit `enabled.set(true)` in the
build overrides that command-line default, so leave it unset if you want the
command-line opt-out. Disabling coverage does not delete reports from older runs.

### Mixed JVM and Chromium tests with Gradle

Keep the browser setup above, add `jacoco` to its `plugins` block, and separate
the tests by name: ordinary `*JvmTest` classes and TeaVM `*BrowserTest` classes.
The following additions run JVM tests first and merge their raw coverage with
the browser run:

```kotlin
jacoco {
    toolVersion = "0.8.15"
}

val jvmTest = tasks.register<Test>("jvmTest") {
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnit()
    include("**/*JvmTest.class")
}

tasks.test {
    include("**/*BrowserTest.class")
    dependsOn(jvmTest)
}

teaCoco {
    executionData.from(layout.buildDirectory.file("jacoco/jvmTest.exec"))
}
```

Run `./gradlew clean teaCocoReport`. Gradle's JaCoCo agent records `jvmTest`
against original classes; TeaCoCo disables that agent only on the selected
browser task, `test`. The resulting `coverage.xml` and `.xml.exec` contain the
union. `executionData` does not schedule its producer task by itself—the
`dependsOn(jvmTest)` above supplies that ordering. Compatible earlier TeaCoCo raw
reports can also be inputs; current browser hits are collected automatically.

See the [Gradle guide](docs/gradle.md) for supported versions, local builds and
further integration details.

## Alternative line-only mode

The explicitly selected `lines` mode inserts probes using TeaVM's
compiler metadata. It measures source lines present in compiled tests and writes
**Sonar generic coverage XML**, imported with `sonar.coverageReportPaths`.
It does not provide JaCoCo branch counters or raw JVM/browser probe merging.

In this mode, examined locations without an emitted probe go in
`not-emitted.json`, rather than being counted as zero-hit lines. Classes never
reached by dependency analysis are outside its inventory. In `jacoco` mode,
selected original classes remain in the denominator and compiler diagnostics go
in `compiler-evidence.json` instead. Neither mode needs source maps or Chrome
DevTools coverage APIs.

Use the [line-only consumer guide](docs/consumer-guide.md) for that configuration.
The [JaCoCo mode guide](docs/jacoco-mode.md) explains probe identity, branch
semantics and compiler diagnostics in more detail.

## Examples and validation

- [JaCoCo branch example](jacoco-example/pom.xml): stock `TeaVMTestRunner` with
  JaCoCo-compatible line and branch reporting.
- [Line-only example](example/pom.xml): lightweight line coverage via TeaVM compiler metadata.
- [Mockatcha timesheet example](mockatcha-example/README.md): complementary tests
  demonstrate **1/6 JVM + 5/6 browser → 6/6 merged branch outcomes**, verified by
  `CoverageParityIT` and retaining unchanged application JARs.
- **Automated verification**: Independent Maven consumers are verified natively during
  the build via Maven Invoker (`maven-plugin/src/it`), and independent Gradle consumers
  via Gradle TestKit (`gradle-plugin/src/test`).

## Custom browser harnesses

The recipes above collect results automatically through stock TeaVMTestRunner.
A Playwright test controlling a separately compiled application needs explicit
compiler setup and collection when the browser work completes. See the
[JaCoCo harness contract](docs/jacoco-mode.md#custom-harnesses-and-implementation-boundary)
or the [line-only harness guide](docs/consumer-guide.md#applications-with-playwright-or-another-browser-harness).

## Build and limitations

Use JDK 21+, Maven 3.9+ and Chrome for the example tests:

```sh
mvn -B -ntp -s central-settings.xml clean install
```

The source build uses public Maven dependencies and stock TeaVM 0.16.0. The
Gradle adapter has its own [build instructions](docs/gradle.md#build-locally).

Validation covers the JavaScript backend, non-incremental compilation and Chrome,
including minified and optimized builds. Later TeaVM versions need compatibility
testing. WebAssembly and C are outside the supported scope. Native JavaScript
bodies, generated intrinsic internals and code without source locations are
outside Java line coverage. Enable instrumentation for coverage testing only.

## Source and license

TeaCoCo uses TeaVM's public plugin SPI and intermediate-code model, and JaCoCo's
public instrumentation, execution-data and reporting APIs in `jacoco` mode.
The probe approach is informed by GWT's compiler coverage design; no GWT compiler
code is included. The Java package is `io.instanto.teavm.coverage`; configuration
properties use the `teavm.coverage.*` prefix.

Apache License 2.0; see [LICENSE](LICENSE).
