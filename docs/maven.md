# Maven: three coverage setups

Choose the recipe matching **where application code executes**. JRE-executable
means the tests run normally on the JVM; Chromium tests execute JavaScript compiled
by TeaVM. Surefire/Failsafe launch tests, and the coverage plugins collect/report
execution without replacing their runners.

Use JDK 21 and Maven 3.9+. For browser recipes, first [install TeaCoCo](building.md)
and get your TeaVM 0.16.0 tests running in Chrome/Chromium with stock TeaVMTestRunner.
Keep your working TeaVM and JUnit dependencies, browser settings and test selection.
No Python installation or script is needed to build, use or report with TeaCoCo.

**JaCoCo mode is the default.** Binding `prepare` and `report` enables coverage.
`prepare` supplies the selected test launcher's coverage properties, runtime
classpath, source roots and prepared classes. No separate enable flag, mode
setting, classpath override or `teacoco` test dependency is required. Adding the
plugin only to `pluginManagement`, or invoking `report` after uninstrumented tests,
does not collect coverage retroactively.

## 1. Only JRE-executable tests

Use standard JaCoCo. Add this under `build/plugins`:

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

Run `mvn clean verify`. Open `target/site/jacoco/index.html`; XML is at
`target/site/jacoco/jacoco.xml`, and raw execution data is at `target/jacoco.exec`.
No TeaCoCo dependency or plugin is needed.

This uses JaCoCo's default `argLine` property and a forked Surefire JVM. If you
already set Surefire's `argLine`, include `@{argLine}` before your existing JVM
arguments and define an empty `<argLine />` property for builds that skip JaCoCo.
Do not set Surefire's `forkCount` to zero when collecting agent coverage.
See the official [prepare-agent](https://www.jacoco.org/jacoco/trunk/doc/prepare-agent-mojo.html)
and [report](https://www.jacoco.org/jacoco/trunk/doc/report-mojo.html) references.

## 2. Mixed JVM and Chromium tests

Use separate executions: ordinary `*JvmTest` tests in Surefire and browser
`*BrowserIT` tests in Failsafe. Browser classes keep
`@RunWith(TeaVMTestRunner.class)` and use `@SkipJVM`; this prevents the stock
runner from also executing those prepared classes on the JVM. The filename
conventions below are explicit filters, so adapt them to your own suites.

Merge these sections into your POM, retaining the TeaVM test dependencies from
your working build. Keep the plugins in this order so TeaCoCo can report available
browser coverage before Failsafe's final failure check. This follows the
[runnable Mockatcha example](../mockatcha-example/pom.xml), without its extra
browser-only comparison report.

```xml
<properties>
  <jvmCoverageArgs />
</properties>

<build>
  <plugins>
    <plugin>
      <groupId>org.jacoco</groupId>
      <artifactId>jacoco-maven-plugin</artifactId>
      <version>0.8.15</version>
      <executions>
        <execution>
          <id>jvm-agent</id>
          <goals>
            <goal>prepare-agent</goal>
          </goals>
          <configuration>
            <propertyName>jvmCoverageArgs</propertyName>
          </configuration>
        </execution>
        <execution>
          <id>jvm-report</id>
          <phase>verify</phase>
          <goals>
            <goal>report</goal>
          </goals>
        </execution>
      </executions>
    </plugin>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-surefire-plugin</artifactId>
      <version>3.5.4</version>
      <configuration>
        <argLine>@{jvmCoverageArgs}</argLine>
        <includes>
          <include>**/*JvmTest.java</include>
        </includes>
      </configuration>
    </plugin>
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
          <goals>
            <goal>prepare</goal>
          </goals>
        </execution>
        <execution>
          <id>merged-report</id>
          <phase>verify</phase>
          <goals>
            <goal>report</goal>
          </goals>
          <configuration>
            <executionData>
              <file>${project.build.directory}/jacoco.exec</file>
            </executionData>
          </configuration>
        </execution>
      </executions>
    </plugin>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-failsafe-plugin</artifactId>
      <version>3.5.4</version>
      <configuration>
        <includes>
          <include>**/*BrowserIT.java</include>
        </includes>
        <systemPropertyVariables>
          <teavm.junit.target>${project.build.directory}/teavm-tests</teavm.junit.target>
          <teavm.junit.js>true</teavm.junit.js>
          <teavm.junit.js.runner>browser-chrome</teavm.junit.js.runner>
          <teavm.junit.wasm-gc>false</teavm.junit.wasm-gc>
          <teavm.junit.c>false</teavm.junit.c>
          <teavm.junit.minified>true</teavm.junit.minified>
          <teavm.junit.optimized>true</teavm.junit.optimized>
        </systemPropertyVariables>
      </configuration>
      <executions>
        <execution>
          <goals>
            <goal>integration-test</goal>
            <goal>verify</goal>
          </goals>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

Run `mvn clean verify`. The sequence is:

1. JaCoCo supplies `jvmCoverageArgs` only to Surefire. JVM tests use original classes.
2. After packaging, TeaCoCo prepares browser copies at `pre-integration-test`.
3. TeaCoCo configures Failsafe to use those copies and its runtime, without a
   JaCoCo JVM agent. TeaVMTestRunner executes the browser tests and captures hits.
4. At `verify`, TeaCoCo merges `target/jacoco.exec` with browser hits and writes
   `target/teavm-coverage.xml` plus `target/teavm-coverage.xml.exec`.

`target/site/jacoco/index.html` from the ordinary JaCoCo execution is **JVM-only**.
Use [the HTML command below](#open-the-browser-or-combined-report-as-html) to
render the combined result. Feed Sonar only the combined XML for these sources.

### What executionData means

`executionData` accepts **additional compatible raw JaCoCo `.exec` files**.
In this recipe the file is produced by JVM tests, but it can also be an earlier
TeaCoCo browser or merged `.xml.exec` result. The current browser run is collected
automatically from its JUnit output; do not list that output as `executionData`.
Do not supply XML summaries or the output file the reporter is about to write.

Every extra input must use matching original class bytes and probe identities
(TeaCoCo uses JaCoCo 0.8.15). Only classes in the selected report scope contribute;
conflicting class IDs or probe counts fail reporting. A hit from any input covers
that probe once. Omit `executionData` for browser-only coverage.

## 3. Only Chromium tests

Add this profile under `profiles` in the module containing your existing browser
tests. Those tests use `@RunWith(TeaVMTestRunner.class)` and `@SkipJVM`.
Surefire's normal test naming patterns apply; retain your existing test includes.

```xml
<profile>
  <id>browser-coverage</id>
  <build>
    <plugins>
      <plugin>
        <groupId>io.instanto</groupId>
        <artifactId>teacoco-maven-plugin</artifactId>
        <version>0.1.0-SNAPSHOT</version>
        <executions>
          <execution><goals><goal>prepare</goal><goal>report</goal></goals></execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</profile>
```

Run `mvn -Pbrowser-coverage clean verify`. The default phases put preparation at
`process-test-classes` and reporting at `verify`. Surefire runs the browser tests
between those steps. The output is `target/teavm-coverage.xml` and
`target/teavm-coverage.xml.exec`, both containing browser-only coverage.

TeaCoCo supplies its runtime and coverage settings automatically. In JaCoCo mode
it removes standard JaCoCo agent arguments from the selected browser execution,
while preserving other JVM arguments and leaving other launchers unchanged.
If browser tests use Failsafe instead, set `<testPlugin>failsafe</testPlugin>`
and bind preparation to `pre-integration-test`, as in the mixed recipe.

## Open the browser or combined report as HTML

TeaCoCo emits standard JaCoCo XML and raw execution data. It does not currently
write HTML itself. After either browser recipe finishes, use standard JaCoCo to
render its raw data against the unchanged module classes:

```sh
mvn org.jacoco:jacoco-maven-plugin:0.8.15:report \
  -Djacoco.dataFile=target/teavm-coverage.xml.exec
```

Open `target/site/jacoco/index.html`. Do not run `clean` first. The command does
not rerun tests; it replaces any prior JVM-only HTML/XML/CSV in `target/site/jacoco`.
The TeaCoCo XML remains at its own path. If your parent disables JaCoCo, add
`-Djacoco.skip=false`.

This convenience command reports the current module's classes. For dependency
classes or a different source scope, configure JaCoCo reporting for that same
scope instead of expecting this command to aggregate dependencies.

## Report scope and failures

By default TeaCoCo measures the module's main output classes matched to its
compile source roots. Selected original executable classes remain in the report
even when browser tests never reach them.

| Setting / output | Default |
| --- | --- |
| `mode` / `-Dteacoco.mode` | `jacoco`; choose `lines` explicitly for line-only generic XML |
| `skip` / `-Dteacoco.skip` | `false`; `true` skips preparation, automatic wiring and reporting |
| `testPlugin` / `-Dteacoco.testPlugin` | `surefire`; select `failsafe` for browser integration tests |
| `testExecutionId` / `-Dteacoco.testExecutionId` | All test executions of the selected plugin; set an execution ID to restrict wiring |
| `sourceRoots` | Module compile source roots |
| `dataDirectory` | `target/teavm-coverage` |
| Prepared test classpath | `target/teavm-coverage/jacoco/classes` |
| Report inputs | `target/surefire-reports` and `target/failsafe-reports` |
| `executionData` | None; optional additional raw `.exec` files |
| `outputFile` | `target/teavm-coverage.xml` |
| Raw result | XML path plus `.exec` |
| Compiler diagnostics | `target/compiler-evidence.json` |

For tests in a separate module, select the library with `dependencyIncludes` or
`classRoots` and supply its matching `sourceRoots`. See the
[JaCoCo mode guide](jacoco-mode.md#maven-configuration) for that configuration.
There is no automatic cross-module aggregation goal.

A profile controls whether coverage goals are bound at all. To keep a profile
active but skip coverage, run `mvn -Pbrowser-coverage -Dteacoco.skip=true clean verify`.
With the minimal recipe above, the tests then run with their ordinary classpath,
without coverage properties or new reports. Remove old manually supplied coverage
settings when migrating; those explicit settings remain your configuration.

Import the XML with `sonar.coverage.jacoco.xmlReportPaths`. For mixed execution,
use the single merged report for its measured sources. No exclusions or quality
gates need to change.

Browser tests flush coverage on ordinary completion and failure. A terminated
browser may be unable to supply evidence; missing or malformed evidence makes
reporting fail. Keep non-incremental compilation, JUnit stdout, the data directory
and the original failed test status. If Surefire stopped Maven before reporting:

```sh
mvn -Pbrowser-coverage io.instanto:teacoco-maven-plugin:0.1.0-SNAPSHOT:report
```

Do not run `clean` or `prepare` first: preparation starts a fresh coverage run.
For the mixed recipe, invoke the named execution with
`mvn io.instanto:teacoco-maven-plugin:0.1.0-SNAPSHOT:report@merged-report`
so its `executionData` input is retained. A report command must not replace the
failed test command's status in CI.
