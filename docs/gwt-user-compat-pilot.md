# GWT compatibility pilot — 2026-10-26

Measured `gwt-user-compat` using its existing `gwt-user-compat-tests` module at
[`dfacf75d646ead076bf3de6297e43e6cc3a9b281`](https://github.com/instanto-io/teavm-compat/commit/dfacf75d646ead076bf3de6297e43e6cc3a9b281).
All **67 tests in 14 TeaVMTestRunner suites passed** in both the baseline and
instrumented runs. They exercise widgets, lifecycle, scheduling, browser APIs
and bindings, and use `@SkipJVM`. JaCoCo 0.8.15 and TeaVM 0.16.0 were used with
JDK 21 and Chrome. Work ran in an isolated export; the library checkout,
production sources, tests and existing Sonar configuration were unchanged.

| Original-bytecode counter | JVM-only coverage of this test run | JVM/browser raw union |
| --- | ---: | ---: |
| Lines | 0 / 6,344 | 1,764 / 6,344 (27.8%) |
| Branch outcomes | 0 / 2,230 | 499 / 2,230 (22.4%) |
| Instructions | 0 / 24,266 | 6,517 / 24,266 |
| Methods | 0 / 2,707 | 641 / 2,707 |
| Classes with executable code | 0 / 326 | 120 / 326 |

This demonstrates coverage of Java executed by TeaVM in the browser; it does not
make GWT APIs executable on the JVM. Because the JVM baseline has no hits in this
scope, this pilot alone does not demonstrate complementary branch merging. That
property is independently checked with real JVM-agent/browser probes in
`scripts/check_jacoco_teavm.py` and the integration tests.

The browser run retains every missed original line and branch. Whole methods
and source locations that TeaVM does not reach are recorded separately in
`compiler-evidence.json`; they are not used to raise the coverage percentage.
The source revision differs from the earlier exploratory 6,341-line baseline.

## Indicative overhead

A single paired warm run, deleting the TeaVM test compilation output before each
measurement, gave:

| Measurement | Plain | Instrumented | Change |
| --- | ---: | ---: | ---: |
| Whole reactor build/test time | 48.371 s | 49.364 s | +2.1% |
| Generated JavaScript, 14 files | 6,184,781 bytes | 7,272,744 bytes | +17.6% |

Both runs disabled the JVM agent for this timing comparison. These are **one
warm pair**, not a statistically stable benchmark or isolated runtime cost. The
reactor includes other verification work. Sizes are raw generated test scripts,
not compressed production bundles. Instrumentation is intended for test builds.
Machine-readable counts are in [the result record](gwt-user-compat-pilot-results.json).

## Reproduce

Install this TeaCoCo revision with `mvn clean install` first. Export the pinned
teavm-compat revision to a new directory (do not reuse a working checkout):

```sh
mkdir /tmp/tea-coco-gwt-reproduction
git -C /path/to/teavm-compat archive dfacf75d646ead076bf3de6297e43e6cc3a9b281 \
  | tar -x -C /tmp/tea-coco-gwt-reproduction
cd /tmp/tea-coco-gwt-reproduction
mvn -B -ntp -pl gwt-user-compat-tests -am test -Dteavm.coverage.enabled=false
mkdir -p pilot-evidence/baseline
cp gwt-user-compat-tests/target/jacoco.exec pilot-evidence/baseline/jvm.exec
mvn -B -ntp -pl gwt-user-compat org.jacoco:jacoco-maven-plugin:0.8.15:report \
  -Djacoco.dataFile="$PWD/pilot-evidence/baseline/jvm.exec"
cp gwt-user-compat/target/site/jacoco/jacoco.xml pilot-evidence/baseline/jacoco.xml

git apply /path/to/tea-coco/docs/gwt-user-compat-pilot.patch
mvn -B -ntp -pl gwt-user-compat-tests -am test -Djacoco.skip=true
mvn -B -ntp -pl gwt-user-compat-tests \
  io.instanto:tea-coco-maven-plugin:0.1.0-SNAPSHOT:report
```

Use the normal authenticated Instanto Maven settings and the pinned project's
required prerequisites. The recorded run used available cached dependencies
(`-o`); reproductions that resolve moving SNAPSHOT dependencies should record
those versions too. This is a pinned source comparison, not a hermetic release
benchmark. The patch selects the resolved `io.instanto:gwt-user-compat` dependency
and its source root, and preserves the baseline `.exec` for merging.

Read `gwt-user-compat-tests/target/teavm-coverage.xml`, its `.exec` sibling and
`target/compiler-evidence.json`. For a plain timing pass after applying the patch,
add `-Dtea-coco.mode=lines -Dteavm.coverage.enabled=false` and set
`-Dpilot.classesDirectory` to the test module's absolute `target/classes` path;
do not run the coverage report for that plain pass. Delete only
`gwt-user-compat-tests/target/teavm-tests` before each timing run.

The pilot report has not been substituted for teavm-compat's live Sonar report.
A dedicated TeaCoCo Sonar workflow checks the standard import separately;
existing repository gates stay unchanged.
