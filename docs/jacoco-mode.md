# Original-bytecode line and branch coverage

TeaCoCo's opt-in `jacoco` mode uses JaCoCo 0.8.15 probes in test-only class copies,
compiles those copies with stock TeaVM 0.16.0, and collects browser hits through
stock `TeaVMTestRunner`. JaCoCo analyzes the preserved original classes. No fork,
application annotations, source maps, JVM agent in the browser, or DevTools API is
required. The existing `lines` mode remains the default for compatibility.

This is an alpha integration for the JavaScript backend and non-incremental
compilation. Java 8 and 17 class files, static initialization, interface methods,
async completion, repeated frames, failures, and SIMPLE/ADVANCED/FULL optimization
are covered by the repository's checks. The Gradle plugin is tested with Gradle
9.3.1 and the upstream TeaVM library and application plugins.

## What the application supplies

* Browser tests that already work with TeaVM. Retain their browser selection,
  supported-library dependencies, asynchronous completion, and test assertions.
* Class directories or dependency JARs containing the code to measure, compiled
  with source/line debug information, plus the corresponding Java source roots.
* A dedicated build-output directory and captured JUnit stdout. Each module/test
  task needs its own data directory and report files.
* Optionally, JVM JaCoCo `.exec` files from **exactly the same original class
  bytes**, using the same JaCoCo version. Separate JVM and browser test tasks.

Source roots select classes by package and `SourceFile`; they must match the
class compilation. TeaCoCo validates original and prepared class checksums, but
cannot prove that supplied source text produced those bytes. Duplicate differing
classpath entries, ambiguous sources, and multi-release dependency JARs fail
preparation; select an explicit release's classes for a multi-release library.
Tests must await their async work before completing. A crashed/terminated browser
cannot flush coverage; missing evidence is an error, not zero-percent success.

## Maven opt-in profile

Keep existing TeaVM dependencies and runner properties. Add this profile to the
module executing the browser tests, merging with its existing Surefire settings:

```xml
<profile>
  <id>browser-coverage</id>
  <properties>
    <tea-coco.mode>jacoco</tea-coco.mode>
    <!-- Only for this browser phase: its class copies already contain probes. -->
    <jacoco.skip>true</jacoco.skip>
  </properties>
  <dependencies>
    <dependency>
      <groupId>io.instanto</groupId>
      <artifactId>tea-coco</artifactId>
      <version>0.1.0-SNAPSHOT</version>
      <scope>test</scope>
    </dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.5.4</version>
        <configuration>
          <classesDirectory>${project.build.directory}/teavm-coverage/jacoco/classes</classesDirectory>
          <systemPropertyVariables>
            <teavm.coverage.enabled>true</teavm.coverage.enabled>
            <teavm.coverage.mode>jacoco</teavm.coverage.mode>
            <teavm.coverage.dataDirectory>${project.build.directory}/teavm-coverage</teavm.coverage.dataDirectory>
          </systemPropertyVariables>
        </configuration>
      </plugin>
      <plugin>
        <groupId>io.instanto</groupId>
        <artifactId>tea-coco-maven-plugin</artifactId>
        <version>0.1.0-SNAPSHOT</version>
        <executions>
          <execution><goals><goal>prepare</goal><goal>report</goal></goals></execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</profile>
```

Run `mvn -Pbrowser-coverage clean verify`. `prepare` runs at
`process-test-classes`; `report` runs at `verify`. Prepared copies precede original
classes only on the test classpath. `target/classes`, packaged JARs and published
application artifacts retain their original bytes. Coverage is off when the
profile is inactive. If Failsafe executes TeaVM tests, configure its
`classesDirectory` and coverage properties identically.

A test-only module can select a library dependency in the plugin configuration:

```xml
<configuration>
  <sourceRoots>
    <sourceRoot>${project.basedir}/../library/src/main/java</sourceRoot>
  </sourceRoots>
  <dependencyIncludes>
    <dependencyInclude>example:library</dependencyInclude>
  </dependencyIncludes>
  <!-- Optional raw JVM coverage, preserved outside the browser clean output. -->
  <executionData><file>${project.basedir}/../jvm-results/jacoco.exec</file></executionData>
</configuration>
```

The library must be a resolved project dependency; reactor output directories
work too. `classRoots` accepts additional class directories/JARs. By default only
the current module's main classes are selected. `sourceRoots` defaults to its
compile source roots. The preparer retains all selected original executable
classes, including classes no browser test reaches.

Do not attach a standard JaCoCo JVM agent to prepared browser classes. If an
inherited build configures `argLine` directly, remove that agent from the browser
phase as well as disabling `prepare-agent`. Collect JVM tests against originals
separately and supply their `.exec` files above. Reports with different class IDs
or probe counts fail rather than producing an inaccurate union.

Maven stops before `verify` when Surefire fails. Collect the evidence separately
with `mvn -Pbrowser-coverage tea-coco:report` (or the fully qualified plugin goal),
while preserving the failed test command's exit status in CI. Do not ignore test
failures or run `prepare` again before this report: preparation starts a new run.

## Gradle

Add the plugin alongside the existing TeaVM library/application plugin, using the
[local snapshot configuration](gradle.md#use-a-locally-built-snapshot):

```kotlin
plugins {
    id("io.instanto.tea-coco") version "0.1.0-SNAPSHOT"
}
teaCoco {
    mode.set("jacoco")
    testTask.set("test")
    // Defaults: main source roots and main output classes.
    // sourceRoots.setFrom(layout.projectDirectory.dir("../library/src/main/java"))
    // classRoots.setFrom(project(":library").extensions
    //     .getByType<SourceSetContainer>()["main"].output.classesDirs)
    // executionData.from(layout.projectDirectory.file("../jvm-results/jacoco.exec"))
}
```

Run `./gradlew teaCocoReport`. `teaCocoPrepare` creates a fresh overlay/run ID before
the selected `Test` task. `teaCocoCollect` finalizes the test even on failure; the
build still fails. Coverage tasks deliberately do not reuse cached test results
and do not support configuration cache yet. If Gradle's standard JaCoCo plugin is
applied, its agent is disabled **only on the selected prepared browser Test task**.
Use another task against original classes for JVM coverage and supply
`executionData`; wire that task as a dependency when collecting both in one build.

`-PteaCoco.enabled=false` leaves normal test execution and its classpath unchanged.
A clean disabled build produces no TeaCoCo probes or reports. Use one plugin
instance per project and one selected browser test task for now.

## Reports and Sonar

| Output | Maven default | Gradle default |
| --- | --- | --- |
| Standard JaCoCo XML | `target/teavm-coverage.xml` | `build/reports/tea-coco/coverage.xml` |
| Standard raw union | XML path plus `.exec` | XML path plus `.exec` |
| Compiler diagnostics | `target/compiler-evidence.json` | `build/reports/tea-coco/compiler-evidence.json` |
| Original classes, overlay and run manifest | `target/teavm-coverage/jacoco/` | `build/tea-coco/data/jacoco/` |

Use `sonar.coverage.jacoco.xmlReportPaths` for this mode. The old line-only mode
uses `sonar.coverageReportPaths` instead. Import the **single merged report** for
the selected source scope; do not try to combine complementary branches by
importing separate JVM/browser XML counts. Matching raw probe hits are ORed before
JaCoCo calculates line and branch counters. The standard XML feeds Sonar's normal
coverage calculation; TeaCoCo does not change gates, exclusions or thresholds.
The manual `sonar-pilot.yml` workflow checks the server's actual imported counters
and coverage calculation in a dedicated fixture project. See the [live result](sonar-import-pilot.md)
and the separate read-token requirement for automated readback.

The full original-bytecode denominator is retained. Never-called methods and
classes remain missed. JaCoCo's filters and C1 branch definition apply (including
short-circuit decisions; exception-handler edges are not branches). This is not
MC/DC or all-path coverage. Code removed by javac is absent from the original
bytecode and therefore outside that denominator.

`compiler-evidence.json` records dependency-reachable methods and source locations
present in the renderer's optimized programs across the **instrumented builds**.
A method not reached by any measured test is distinguishable from an unhit branch
inside a reached method. Missing line metadata does not prove an exact branch was
eliminated. Instrumentation itself can affect optimization, so these diagnostics
are not a claim about an uninstrumented production bundle. They never remove
missed code from the standard report. The standalone parity experiment additionally
compares separate uninstrumented builds; see [the design notes](branch-coverage-design.md).

## Custom harnesses and implementation boundary

The normal integration needs no runner extension. TeaCoCo adds a flush at stock
`TestJsEntryPoint.main` exits/raises, after its awaited test work. A custom entry
point must call `JacocoRuntime.flush(runId)` after completion and on failure, using
the UUID from `jacoco/manifest.json`. Preserve the line beginning
`__TEA_COCO_JACOCO_V1__:` in JUnit `<system-out>` and retain the compilation evidence.
Use `JacocoReporter` with the data directory, output XML, platform-separated source
roots, optional `--exec=/path/to/jvm.exec`, and JUnit-report directories.

The portable runtime holds boolean arrays; it is not JaCoCo's JVM agent. Java 11+
JaCoCo instrumentation uses a constant-dynamic lookup that TeaVM 0.16 cannot parse.
TeaCoCo lowers that specific synthetic lookup to an ordinary static call without
changing JaCoCo's original class ID, probe placement or index. Other bytecode is
not rewritten by that adapter. The [parity experiment](../scripts/check_jacoco_teavm.py)
compares real JVM-agent and browser probe arrays and their complementary union.
