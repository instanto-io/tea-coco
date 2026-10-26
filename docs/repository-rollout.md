# Evaluating TeaCoCo across Instanto repositories

Start with opt-in coverage jobs alongside existing tests. Keep the ordinary builds and existing Sonar gates intact
until the imports and source scope are understood. An isolated Maven/Gradle fixture passing is necessary, but does
not establish compatibility with every repository's generators, compiler extensions or browser harness.

## First evaluation targets

| Repository/test style | Integration required | Evidence to check |
| --- | --- | --- |
| teavm-compat: TeaVMTestRunner suites in separate test modules | Test dependency/profile; selected compatibility library source roots rather than the test module's empty main sources. | Generated bindings, async work and real browser-only GWT code; source files match compiled library revisions. |
| teavm-charba and tealeaf: demo + browser harness | Instrument the demo's TeaVM compilation and collect page counters before closing/navigation. | Rendering and user interactions hit library source lines; unchanged browser assertions still pass. |
| sarto-scene and its geo showcase: library and application tests | Choose stock-runner or application-harness path per suite; give each compilation/test job its own data. | Animation/skin/model mutation tests, omitted methods, and application initialization coverage. |
| Ports: consumer application tests | Integrate after the consumer itself builds; select matching sources for the libraries under test. | Distinguish pre-existing compilation/platform failures from coverage integration defects. |

These are rollout targets, **not a claim that all those suites have already passed with TeaCoCo**.
The standalone Maven example and Gradle independent consumers are the initial executable compatibility proofs.

## Procedure for each repository

1. Record the repository SHA, TeaVM/compiler/plugin versions, browser versions and resolved TeaCoCo snapshot.
2. Run the existing suite without coverage and keep its result. Resolve unrelated build/publication blockers first.
3. Enable coverage only for that suite, using a clean isolated output directory and the same assertions/browser.
4. Select matching Java source roots, including generated sources when relevant. Avoid ambiguous duplicate paths.
5. Verify a known executed line, an included-but-unexecuted line, and an omitted method manually against the report.
6. Compare test results and record build duration/bundle size overhead. Check failing-test and async completion capture.
7. Import the XML into the correct Sonar project/source scope and verify the scanner log. Do not lower gates or hide
   missing tests to make the new measurement pass.
8. Add that proven configuration as an opt-in profile/job. Promote it to routine CI after a stable evaluation period.

For suites already producing source-map/CDP coverage, compare reports first; do not replace a working pipeline or
combine measurements for overlapping Java files without understanding the differences in scope.

## What to retain

Keep the baseline JSON files, captured JUnit XML, Sonar XML, `not-emitted.json`, source revision, snapshot resolution
and CI/browser logs together. Counters are meaningful only with their compilation IDs and matching sources.
Do not feed data from different source revisions into one report. Publication and compatibility builds are separate
claims: an isolated cascade can pass using local installs even when remote package publication is still blocked.
