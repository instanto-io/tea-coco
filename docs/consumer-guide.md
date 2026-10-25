# Using tea-coco in a library or application

tea-coco measures Java lines executed **in the browser** after TeaVM compiles them to JavaScript.
It does not require your GWT/browser library to run on the JVM. The JVM hosts the compiler, test orchestration
and reporting; the browser executes the instrumented code.

## What you supply

| Input | Library/app responsibility |
| --- | --- |
| Executable tests | Existing TeaVM JUnit tests, or a browser harness that loads the instrumented application and waits for its work to finish. Ordinary JVM-only JUnit tests do not generate browser coverage. |
| Supported compiler | Upstream TeaVM 0.16.0, JavaScript backend. Keep compiler, class library, JUnit and Gradle plugin versions aligned. No TeaVM fork is required. |
| Source locations | Java class files with source filenames and line-number tables. Normal javac builds include these; do not strip them or use `-g:none`. |
| Source files | Accessible source roots for the exact revision of the measured classes. Supply directories above package paths, not individual files or source JARs. |
| Compiler classpath | The tea-coco extension plus its runtime dependencies in the process/classpath that actually runs TeaVM. For stock TeaVMTestRunner this is the test runtime classpath. |
| Invocation configuration | Coverage enabled, chosen source roots and a fresh writable baseline directory. Disable TeaVM incremental AST caching. |
| Browser evidence | Captured stdout in standard JUnit XML for stock runner tests; explicit snapshot collection for a custom harness. |
| Report step | Run the reporter after the tests with matching baselines, results and source roots. Keep these together when transferring work between CI jobs. |

There are **no new annotations, interfaces, inheritance requirements or probes to add to library/application
source**. Stock runner tests keep `@RunWith(TeaVMTestRunner.class)` and their existing platform annotations.
A library cannot make itself covered just by depending on tea-coco: a test must call its code in a compiled build.

Source maps and TeaVM debugger output are optional. tea-coco gets source locations directly from the compiler IR.
Native JavaScript bodies and generated intrinsic internals are outside Java line coverage.

## Opt-in Maven profile

First configure [the internal registry](internal-publishing.md) or install the artifacts locally. Add this profile
in the module that owns the browser tests. Retain its existing TeaVM dependencies, browser selection, test includes,
JVM arguments and Surefire/Failsafe configuration; this profile adds coverage settings only.

```xml
<profile>
  <id>tea-coco</id>
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
        <configuration>
          <systemPropertyVariables>
            <teavm.coverage.enabled>true</teavm.coverage.enabled>
            <teavm.coverage.sourceRoots>${project.basedir}/src/main/java</teavm.coverage.sourceRoots>
            <teavm.coverage.dataDirectory>${project.build.directory}/teavm-coverage</teavm.coverage.dataDirectory>
          </systemPropertyVariables>
        </configuration>
      </plugin>
      <plugin>
        <groupId>io.instanto</groupId>
        <artifactId>tea-coco-maven-plugin</artifactId>
        <version>0.1.0-SNAPSHOT</version>
        <executions><execution><goals><goal>report</goal></goals></execution></executions>
      </plugin>
    </plugins>
  </build>
</profile>
```

Run `mvn -U -Ptea-coco clean verify` using the repository's normal Maven settings and browser environment.
If Failsafe owns the browser tests, put the system properties in Failsafe instead. The report plugin reads both
`target/surefire-reports` and `target/failsafe-reports` by default. Keep its verify execution after your integration
tests; the usual Failsafe integration-test phase already precedes verify.

The [working example](../example/pom.xml) provides the complete stock TeaVM dependency and runner setup for a new project.

## Separate test modules, dependency libraries and generated sources

A dedicated test module often has no `src/main/java`. Point **both** the compiler and reporter at the library
sources it exercises. For example, in a Unix build with two source roots:

```xml
<!-- In the test plugin's systemPropertyVariables -->
<teavm.coverage.sourceRoots>${project.basedir}/../library-a/src/main/java:${project.basedir}/../library-b/src/main/java</teavm.coverage.sourceRoots>

<!-- In tea-coco-maven-plugin's configuration -->
<sourceRoots>
  <sourceRoot>${project.basedir}/../library-a/src/main/java</sourceRoot>
  <sourceRoot>${project.basedir}/../library-b/src/main/java</sourceRoot>
</sourceRoots>
```

The compiler property uses the OS path separator (`:` Unix, `;` Windows); the reporter's Maven list does not.
For an external library, unpack its matching sources JAR into a directory and add that directory explicitly.
Generated Java sources must exist before compilation/reporting, correspond to the compiled classes, and have their
own selected source root. Do not select test sources unless measuring the test code itself is intended.

Avoid duplicate package-relative paths across roots. The reporter rejects ambiguous matches rather than attaching
hits to the wrong version of a source file. Each reactor module needs independent data/result/report directories.
There is no automatic cross-module aggregation task in this first release.

## Applications with Playwright or another browser harness

Adding a dependency to a JVM Playwright test does not instrument an application compiled in a separate task.
Load tea-coco on the **application compiler's** classpath and pass the three `teavm.coverage.*` properties into that
TeaVM compilation. Keep the runtime available to TeaVM's class source as well. Use a dedicated coverage build;
do not ship these instrumented bundles as production artifacts.

After navigating to the instrumented application, await your assertions and any asynchronous work being measured.
Before closing the page, capture its counters and print the prefixed JSON from the host JVM test so JUnit records it:

```java
// page is the Playwright Page for this instrumented application.
try {
    // Existing navigation, interactions and assertions, including awaited async work.
} finally {
    Object snapshot = page.evaluate("JSON.stringify(globalThis.__teavmCoverageV1 || {})");
    System.out.println("__TEAVM_COVERAGE_V1__:" + snapshot);
    // Close the page only after capture. Do not turn a test failure into success.
}
```

Capture every page/frame/worker realm that owns an instrumented bundle; the global is not shared across realms.
Capture before navigation replaces a realm. Merge snapshots by printing one record per capture, and preserve all
matching compiler baselines. If no usable browser evidence is collected, reporting fails. A lost individual snapshot can instead leave its
compiled lines uncovered when other snapshots exist, so the harness must treat failed collection as a failed test. Do not let a capture exception obscure an earlier test failure in your harness.
`CoverageRuntime.flush()` is an alternative for explicit Java-side collection inside the browser.

The Gradle plugin currently automates **JUnit Test tasks**, not arbitrary `generateJavaScript` application tasks.
Such application pipelines use the compiler properties and collection contract above. This is also the route for
Charba/Tealeaf/Scene end-to-end tests that compile a demo before launching Playwright.

## Failures, caches and Sonar

Keep JUnit `system-out`; the runner transports counters there. Do not truncate its lines. The reporter merges hits
across compilations, browsers and cached frames, and seeds emitted-but-unexecuted lines as uncovered.
Use a clean build with fresh compiler data. Reusing a browser compilation without its matching baseline is invalid.
Gradle's integration reruns the selected coverage test task and clears its previous baselines automatically.

Stock runner failures flush coverage too. Maven stops before verify when tests fail; you can run
`mvn io.instanto:tea-coco-maven-plugin:0.1.0-SNAPSHOT:report` separately to inspect evidence, with the same configuration.
The command does not fix or suppress the original failing test. The Gradle plugin finalizes its selected test task
with reporting and preserves Gradle's failure result.

For a Maven module, import `target/teavm-coverage.xml` with `sonar.coverageReportPaths`.
For Gradle, use `build/reports/tea-coco/coverage.xml`. Ensure the scanner analyzes the same source checkout and can
resolve the absolute source paths. If you also use JaCoCo, keep its XML in `sonar.coverage.jacoco.xmlReportPaths`;
do not put tea-coco's generic XML there. Validate scanner imports before changing coverage gates or combining metrics.
The current tea-coco version adds line hits only. For overlapping reports, Sonar ORs line hits but uses maximum
branch counts; this is not an exact union of complementary branch executions. See the
[branch coverage analysis](branch-coverage-design.md#sonar-thresholds-and-the-limits-of-merging-xml).

`not-emitted.json` is separate diagnostic data. Code removed by compilation is not marked as a zero-hit compiled
line. Classes never reached by dependency analysis are not inventoried. Sonar may still count other analyzed sources
according to its own scope; tea-coco does not alter exclusions or quality gates. No branch coverage is claimed.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| No baselines | Extension missing from compiler classpath, disabled property, non-JS backend, wrong source roots, or class files without line metadata. |
| Baselines but no snapshots | Browser tests did not run, wrong runner version, stdout stripped, or custom harness missing collection. |
| Unknown build ID / probe mismatch | Results and baselines came from different builds, or stale directories were combined. Clean and rerun together. |
| Missing/ambiguous source | Supply the exact matching source checkout and unique package-relative paths. |
| No coverage for dependency code | Add its matching source roots and make sure the tested application actually reaches the code. |
| Registry 401/403 | The CI repository needs access to the package credentials; this is not a compiler/test failure. |
