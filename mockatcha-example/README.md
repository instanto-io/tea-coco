# Testing a timesheet service with Mockatcha and TeaCoCo

This example tests a small Java service in two places: on the JVM and in a
browser. Mockatcha lets each test choose the data the service receives and check
what it asks other components to do. JaCoCo measures the JVM tests; TeaCoCo
measures the browser tests and brings both results into one coverage report.

Start with the tests, then look at [pom.xml](pom.xml) to see how Maven runs them.
You can use the same arrangement in your own project without changing your
application code to support coverage.

## Run the example

You need JDK 21, Maven and Chrome/Chromium. Install the TeaCoCo and Mockatcha
snapshot dependencies locally, following their build instructions, then run
this command from the `mockatcha-example` directory:

```sh
mvn clean verify
```

This runs the tests and generates the coverage reports. No Python is required.
For TeaCoCo installation, see [building from source](../docs/building.md).

## What the service should do

[TimesheetService](src/main/java/io/instanto/mockatcha/examples/TimesheetService.java)
receives an employee ID and asks a repository for that employee's draft timesheet.
Its rules are small enough to read alongside the tests:

- A missing draft is an error. There is nothing to submit.
- A draft is marked as submitted, regardless of how many hours it contains.
- More than 40 hours requires a review. Exactly 40 hours does not.

The service takes a repository and an approval gateway in its constructor. Tests
can therefore supply mocks instead of a database or an external approval service.
The service itself is real Java code throughout these tests.

## Read the tests

| Test class | Runs in | What it checks |
| --- | --- | --- |
| [TimesheetJvmTest](src/test/java/io/instanto/mockatcha/examples/TimesheetJvmTest.java) | JVM | A missing draft raises an error before submission. |
| [TimesheetCoreBrowserIT](src/test/java/io/instanto/mockatcha/examples/TimesheetCoreBrowserIT.java) | Chromium | A 38-hour week is submitted without review; 40 hours is still below the review threshold. |
| [TimesheetBddBrowserIT](src/test/java/io/instanto/mockatcha/examples/TimesheetBddBrowserIT.java) | Chromium | A 46-hour week sends the employee ID and hours for review; a repository spy still records a real submission. |

The split is deliberate: the JVM test checks the missing-draft case, while the
browser tests check successful submissions. Neither group exercises all the
service's decisions alone. That makes it easy to see what the combined coverage
report adds. In a real project, choose the execution environment each test needs;
you do not have to divide your cases in this particular way.

### An ordinary week: set up, submit, check

The first browser test uses Mockatcha's core API. It creates two mocks and tells
the repository what to return for employee `A-17`:

```java
TimesheetRepository repository = mock(TimesheetRepository.class);
ApprovalGateway approvals = mock(ApprovalGateway.class);
when(repository.findDraft("A-17")).thenReturn(new Timesheet("A-17", 38));

Submission result = new TimesheetService(repository, approvals).submit("A-17");

assertEquals(38, result.hours());
assertFalse(result.reviewRequired());
verify(repository).markSubmitted("A-17");
verify(approvals, never()).requestReview(anyString(), anyInt());
```

The assertions check the returned result. The `verify` calls check the service's
requests to its collaborators: it must save the submission, and it must not ask
for a review. The second test uses 40 hours to check the boundary explicitly,
rather than relying only on an ordinary 38-hour example.

### Overtime: check the review request

The BDD test uses Mockatcha's alternative `spyOn` and `calls` API to describe the
same sort of setup and checks. It returns a 46-hour draft, submits it, then checks
that the approval gateway was called once with `A-17` and `46`.

Its second test introduces a **spy around a real in-memory repository**. Only
`findDraft` is replaced; the other methods keep their real behavior:

```java
InMemoryTimesheetRepository real = new InMemoryTimesheetRepository();
real.addDraft(new Timesheet("A-17", 38));
InMemoryTimesheetRepository repository = spy(InMemoryTimesheetRepository.class, real);
ApprovalGateway approvals = mock(ApprovalGateway.class);
spyOn(repository, "findDraft").withArgs("A-17").andReturn(new Timesheet("A-17", 46));

new TimesheetService(repository, approvals).submit("A-17");

assertEquals(List.of("A-17"), real.submitted());
assertEquals(List.of("A-17", 46), calls(approvals, "requestReview").mostRecent().arguments());
```

This checks two things together: the supplied draft triggers an overtime review,
and the real repository still records the submission. Use a mock when you want
to control a collaborator completely; use a spy when retaining some real behavior
helps the test.

Each test class releases its mocks in an `@After` method. The browser classes also
have `@RunWith(TeaVMTestRunner.class)` and `@SkipJVM`: they run through TeaVM in the
browser, rather than being run a second time on the JVM. The POM exercises their
ordinary, optimized and minified JavaScript builds.

## How Maven chooses the tests and collects coverage

The [POM](pom.xml) separates JVM and browser tests by their filenames:

| Configuration | What it does |
| --- | --- |
| Surefire includes `**/*JvmTest.java` | Runs the JVM tests with ordinary JaCoCo coverage. |
| Failsafe includes `**/*BrowserIT.java` | Runs the browser tests through TeaVMTestRunner. |
| TeaCoCo selects `testPlugin=failsafe` | Adds coverage to those browser tests, leaving the JVM test execution alone. |

The suffixes are this example's convention. When adapting the POM, change the
includes to match your own test names so that every intended test is selected.

JaCoCo's `prepare-agent` goal supplies the JVM coverage settings through
`jvmCoverageArgs`, which Surefire uses. TeaCoCo's `prepare` goal is scheduled just
before the browser integration tests; its `report` goal runs after them. The
important part of the TeaCoCo configuration is:

```xml
<plugin>
  <groupId>io.instanto</groupId>
  <artifactId>teacoco-maven-plugin</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <configuration><testPlugin>failsafe</testPlugin></configuration>
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

Binding these goals enables coverage. JaCoCo mode is the default, and the plugin
supplies its own runtime and browser coverage settings. There is no separate
enable flag or additional TeaCoCo test dependency to configure.

Here, `executionData` adds the results from the JVM tests to the browser results
that TeaCoCo collects automatically. More generally, it accepts compatible
JaCoCo `.exec` files, including earlier TeaCoCo results. For a browser-only
report, leave it out. The full POM adds a second report execution without
`executionData` so you can compare browser-only and combined coverage.

## Find and read the reports

After `mvn clean verify`, these are the useful starting points:

| File | What it shows |
| --- | --- |
| `target/site/jacoco/index.html` | An HTML view of the JVM tests alone. |
| `target/browser-coverage.xml` | Coverage from the browser tests alone. |
| `target/teavm-coverage.xml` | Combined JVM and browser coverage, ready for Sonar. |

To view the **combined** result as HTML, run:

```sh
mvn org.jacoco:jacoco-maven-plugin:0.8.15:report \
  -Djacoco.dataFile=target/teavm-coverage.xml.exec
```

Then open `target/site/jacoco/index.html` again. It now shows the combined result
instead of the earlier JVM-only result. Do not run `clean` between these commands.

The example's expected results are:

| Tests included | Covered Java lines | Covered branch outcomes |
| --- | ---: | ---: |
| JVM only | 7 / 29 | 1 / 6 |
| Browser only | 27 / 29 | 5 / 6 |
| Combined | 28 / 29 | 6 / 6 |

A branch outcome is one side of a decision, such as finding a draft or not finding
one. The JVM test covers an outcome the browser tests miss, so combining them
covers all six outcomes. It does not cover every line: the remaining missed line
is still shown. Coverage measures which code ran; the assertions above establish
whether that code behaved as expected.

For Sonar, set `sonar.coverage.jacoco.xmlReportPaths=target/teavm-coverage.xml`.
Use this combined report for these application sources. A failing test still
fails the build, even if a report is available.

## Apply this to your own project

Start with tests that already run in their intended environment. Keep the
Mockatcha and TeaVM dependencies your tests need, select the JVM and browser
suites separately, and add the coverage configuration shown above. If all your
tests run on the JVM, ordinary JaCoCo is enough. If they all run in Chromium,
TeaCoCo can generate the report without a JVM coverage input.

The [three Maven recipes](../docs/maven.md) cover those arrangements. For details
about combining results or choosing which library to measure, see the
[coverage reference](../docs/jacoco-mode.md).

The application classes come from [Mockatcha's timesheet example](https://github.com/instanto-io/mockatcha/tree/4357c2b3a122ca12b6b2a15ff20267d11276ee62/mockatcha-examples),
with their Apache-2.0 notices retained. This standalone example uses Mockatcha as
a dependency; it does not require an organisation parent or a modified TeaVM runner.
