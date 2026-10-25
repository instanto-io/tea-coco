# tea-coco

Java source line coverage from real browser execution, using **unmodified TeaVM 0.16.0**.
No compiler fork, patched JAR, Java agent, or replacement JUnit runner is needed.

This is an experimental extension. It is not yet published to Maven Central.
The standalone source build uses only public Maven Central dependencies; it has no private organisation parent POM.

## Try it

Requires JDK 21+, Maven 3.9+, and Google Chrome (`google-chrome-stable` on Linux).

```sh
git clone https://github.com/instanto-io/tea-coco.git
cd tea-coco
mvn -B -ntp -s central-settings.xml clean install
python3 scripts/smoke_consumer.py
```

The example uses the ordinary `@RunWith(TeaVMTestRunner.class)` annotation. It executes real Chrome tests
in ordinary, minified and optimised configurations, then checks the coverage report against deliberately
covered, unexecuted, short-circuited and omitted application code. Firefox can be selected with
`-Dcoverage.browser=browser-firefox` when installed.

Outputs:

* `example/target/teavm-coverage.xml`: Sonar generic coverage XML.
* `example/target/not-emitted.json`: source lines examined but absent from all compiled test configurations.
* `example/target/teavm-coverage/*.json`: per-compilation probe baselines.

## Add to an existing Maven project

After installing locally or configuring the [internal snapshot repository](docs/internal-publishing.md), add this **test dependency**
alongside your existing TeaVM JUnit/class-library dependencies. Keep TeaVM's own version at `0.16.0`.

```xml
<dependency>
  <groupId>io.instanto</groupId>
  <artifactId>tea-coco</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <scope>test</scope>
</dependency>
```

Add the following to your existing Surefire `systemPropertyVariables`. Coverage is disabled unless explicitly enabled.
The source roots select your application sources, avoiding coverage of TeaVM/JUnit/dependencies.

```xml
<teavm.coverage.enabled>true</teavm.coverage.enabled>
<teavm.coverage.sourceRoots>${project.basedir}/src/main/java</teavm.coverage.sourceRoots>
<teavm.coverage.dataDirectory>${project.build.directory}/teavm-coverage</teavm.coverage.dataDirectory>
```

Multiple roots are separated by the platform path separator (`:` on Unix; `;` on Windows).
Keep the usual `teavm.junit.target`, JS runner and backend settings. Do not remove JUnit's captured stdout.
Do not enable incremental AST caching for a coverage build.

Add the reporting goal:

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

Run `mvn clean verify`. Set `sonar.coverageReportPaths=target/teavm-coverage.xml` when analysing that module.
The plugin defaults to Maven's compile source roots and reads both Surefire and Failsafe XML. Override its
`sourceRoots`, `dataDirectory`, `surefireReports`, `failsafeReports` or `outputFile` if needed.
Each reactor module should own its data and report directories.

A failed test still flushes its coverage before the stock runner reports the failure. Maven normally stops before
`verify` on test failure; the report goal may be invoked separately to inspect those diagnostics. It does not
convert failing tests into passing tests. Missing/malformed browser evidence fails reporting.

## Gradle plugin and custom browser harnesses

Use the [Gradle plugin](docs/gradle.md), `io.instanto.tea-coco`, alongside TeaVM's library or application plugin.
It configures the selected browser test task and provides `teaCocoReport`. The Gradle and Maven adapters share the
same compiler extension and report format.

For custom integration, the compiler extension is discovered through `META-INF/services/org.teavm.vm.spi.TeaVMPlugin`.
It accepts the same three properties from the test JVM or TeaVM compiler properties. Your tests can keep using
TeaVMTestRunner. After Gradle's test task, use `CoverageReporter.generate(...)` or the CLI:

```text
io.instanto.teavm.coverage.CoverageReporter DATA_DIRECTORY OUTPUT_XML SOURCE_ROOTS JUNIT_REPORT_DIRECTORY...
```

Put `io.instanto:tea-coco` and its runtime dependencies on the reporting task's classpath.
The reporter does not require TeaVM compiler classes. A Gradle `JavaExec` reporting task should depend on
`test` and read `build/test-results/test`. Source roots use the platform path separator as above.

For a custom application harness, enable the compiler extension and collect
`JSON.stringify(globalThis.__teavmCoverageV1)` after the awaited application/test work. Print it from the JVM
JUnit test with the prefix `__TEAVM_COVERAGE_V1__:` so the same reporter can consume it. Alternatively Java
browser code may explicitly call `CoverageRuntime.flush()`. Preserve the baselines from the matching compilation.
This adapter is tested with stock TeaVMTestRunner; other harnesses must verify their completion and failure paths.

## What the numbers mean

The plugin inventories source locations during dependency analysis and inserts probes through the public renderer
listener immediately before JavaScript generation, **after TeaVM's IR optimisation**. It does not make previously
unreachable application methods reachable. The probe runtime itself is explicitly linked as tooling.

* **Covered:** an included source line had at least one probe hit.
* **Uncovered:** the compiled build includes a probe for that line, but no collected test hit it.
* **Not emitted:** a source location in an examined class had no probe in any of the compiled configurations.

Not-emitted is diagnostic information, not a zero-hit entry in the Sonar XML. It includes optimisation effects,
so it is not always evidence of an unused API. Classes never reached by dependency analysis are outside this inventory.
A line shared by several instructions is covered when any of them runs; this is not branch coverage.
The metadata is captured directly from the compiler, so source maps/debugger metadata are optional.

Immutable compilation IDs prevent collisions between concurrent JVM forks. Browser snapshots retain class-init
hits and merge by OR across repeated/cached tests. Start with `clean` to avoid mixing different invocations.
The reporter rejects unknown build IDs, mismatched probe counts, missing sources and ambiguous source roots.

## Current limits

* Supported/tested compiler and stock runner: upstream TeaVM **0.16.0**, JavaScript backend, non-incremental builds.
* Initial browser validation: Chrome. No dependency on Chrome DevTools coverage APIs.
* WebAssembly, C and branch coverage are not implemented.
* Native JS bodies, generated intrinsic internals and code without source lines are outside Java line coverage.
* Instrumentation adds overhead and is intended for coverage test builds, not production bundles.
* The stock runner adapter targets its 0.16.0 entry point; later TeaVM versions need compatibility tests.
* Sonar's analysis of sources outside the imported report depends on scanner configuration. The diagnostics do not
  automatically change exclusions or quality gates.

The implementation uses TeaVM's public plugin SPI and public IR model. It replaces none of TeaVM's classes.
The baseline/probe approach is inspired by GWT's compiler coverage design; it does not include copied GWT compiler code.

## Integration and internal evaluation

* [Gradle plugin](docs/gradle.md): plugin DSL, internal resolution and task behavior.
* [Consumer guide](docs/consumer-guide.md): what libraries/apps supply, a copyable opt-in Maven profile,
  source metadata, separate test modules, browser harnesses, failure handling and Sonar imports.
* [Internal publication](docs/internal-publishing.md): authenticated snapshots at `packages.instanto.io`,
  CI credentials and verification from a fresh consumer cache.
* [Branch coverage design](docs/branch-coverage-design.md): measured JaCoCo semantics, compiler identities and Sonar merging constraints.
* [Repository rollout](docs/repository-rollout.md): staged evaluation against the existing Instanto test suites.

The initial distribution is internal snapshots, to test tea-coco against our existing repositories before a public
package release. The source remains public and builds from Maven Central without credentials or a TeaVM fork.
The internal registry requires authentication. Maven Central publication is deferred.

The Maven coordinates and repository are named tea-coco. The Java package `io.instanto.teavm.coverage`,
`teavm.coverage.*` properties, `__TEAVM_COVERAGE_V1__` transport and existing output filenames remain stable.

## License

Apache License 2.0; see [LICENSE](LICENSE).
