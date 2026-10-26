# Mockatcha + TeaVMTestRunner + Tea-Coco

A standalone Maven project using the existing Mockatcha **timesheet example**.
It combines ordinary Mockatcha mocks, mockatcha-bdd stubbing/call logs/matchers,
stock TeaVMTestRunner browser execution and Tea-Coco line/branch coverage.
No organisation parent or patched TeaVM runner is required by this project.

The source is maintained in [tea-coco/mockatcha-example](https://github.com/instanto-io/tea-coco/tree/main/mockatcha-example)
and also available as [a standalone demo repository](https://github.com/instanto-io/mockatcha-tea-coco-demo).
Application classes are copied, with their Apache-2.0 notices, from
[Mockatcha's examples at 4357c2b](https://github.com/instanto-io/mockatcha/tree/4357c2b3a122ca12b6b2a15ff20267d11276ee62/mockatcha-examples).
The tests here add a deliberately complementary JVM/browser coverage exercise.

## Run

Use JDK 21, Maven and Chrome. With the snapshot dependencies already installed
locally, run:

```sh
mvn clean verify
python3 verify_coverage.py
```

For Instanto snapshot consumption, use the included `settings.xml`. Provide
`FORGEJO_PACKAGE_USER` and `FORGEJO_PACKAGE_TOKEN` through your environment or CI
secrets, then run `mvn -s settings.xml -U clean verify`. These resolve the existing
Mockatcha and Tea-Coco libraries; this project does not publish a library artifact.
The source libraries can also be built locally following their own setup guides.

## Read the example

1. `TimesheetService` asks the repository for a draft, submits it, and requests
   approval when hours exceed 40. Missing drafts fail before submission.
2. `TimesheetJvmTest` uses Mockatcha on the JVM to exercise the missing-draft path.
3. `TimesheetCoreBrowserIT` uses core `mock`, `when`, `verify` and argument matchers
   for ordinary and boundary-hour weeks, compiled by TeaVM into browser tests.
4. `TimesheetBddBrowserIT` uses mockatcha-bdd `spyOn`, `calls` and `stringContaining`
   for overtime and a concrete repository spy. Real storage behavior is retained
   while the draft lookup is overridden.
5. `verify_coverage.py` proves the raw JVM/browser union improves both line and
   branch coverage and that packaged application classes retain their original bytes.

The browser classes use `@SkipJVM` and `@After` cleanup. They deliberately avoid
`@Rule` so they work with stock TeaVMTestRunner, without Instanto's rule extension.
This does not prevent applications from using that extension where desired.
Each browser scenario runs in ordinary, optimized and minified JavaScript modes.
The mocks are test collaborators; Tea-Coco measures only `src/main/java` application
classes, not mock implementations or the testing libraries.

## Expected result

| Runtime | Lines | Branch outcomes |
| --- | ---: | ---: |
| JVM | 7 / 29 | 1 / 6 |
| Browser | 27 / 29 | 5 / 6 |
| Merged | 28 / 29 | 6 / 6 |

The remaining missed application line is not hidden. The browser's five outcomes
and the JVM's one outcome are complementary, so identity-aware merging reaches
six. Importing two XML count summaries would not establish that union.

Surefire runs the JVM test against original classes with JaCoCo's JVM agent.
Tea-Coco then prepares an overlay in `pre-integration-test`; Failsafe runs browser
tests against it **without** the agent. The normal application JAR is already
packaged from originals. Reports are written before Failsafe's final failure check,
so available coverage is retained without changing a failing build into success.

| Output | Contents |
| --- | --- |
| `target/site/jacoco/index.html` | JVM-only JaCoCo HTML report |
| `target/browser-coverage.xml` | Browser-only standard JaCoCo XML |
| `target/teavm-coverage.xml` | Merged standard JaCoCo XML for Sonar |
| `target/teavm-coverage.xml.exec` | Matching raw merged probe data |
| `target/compiler-evidence.json` | Instrumented-build reachability diagnostics |
| `target/coverage-comparison.md` | Checked side-by-side totals |

For Sonar, import only `target/teavm-coverage.xml` with
`sonar.coverage.jacoco.xmlReportPaths` for these application sources. Keep any
quality-gate changes separate. JavaScript is the supported coverage backend here;
compiler diagnostics do not subtract omitted code from the denominator.

## CI and syncing the standalone copy

The standalone workflow verifies published snapshots from an isolated Maven cache,
then uploads diagnostic reports to `packages.instanto.io` and verifies their SHA-256
on readback. The repository needs the organisation Forgejo package-user and
package-write token secrets and the `instanto-container` runner. Tea-Coco runs the
same example after its own snapshot publication. Neither workflow deploys this
application JAR.

The Tea-Coco directory is the source of truth. Export a reviewed revision with
`git archive <revision>:mockatcha-example` into the standalone checkout, preserve
its `.git` directory, and record the source revision in `ORIGIN.md`. Build outputs
and credentials must never be copied.
