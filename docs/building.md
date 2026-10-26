# Build and install the alpha

Tea-Coco is an alpha project. Maven Central publication is planned; the current
`0.1.0-SNAPSHOT` can be built and installed from source.

Source repository: [instanto-io/tea-coco](https://github.com/instanto-io/tea-coco).

## Artifacts

| Coordinates | Purpose |
| --- | --- |
| io.instanto:tea-coco-parent | Maven metadata parent; consumers do not need to inherit it. |
| io.instanto:tea-coco | Compiler extension, browser runtime and standalone reporter. |
| io.instanto:tea-coco-maven-plugin | Maven report goal. |
| io.instanto:tea-coco-gradle-plugin | Gradle integration. |
| io.instanto.tea-coco:io.instanto.tea-coco.gradle.plugin | Gradle plugin marker for the plugins DSL. |

All currently use version `0.1.0-SNAPSHOT`. Source JARs are included for the
library and plugin implementations. The example is a verification fixture.
The project uses a standalone parent and the stock TeaVM compiler.

## Maven

Use JDK 21. From the repository root, build and install the parent, compiler
extension and Maven plugin in your local Maven repository:

```sh
mvn -B -ntp -s central-settings.xml clean install
python3 scripts/smoke_consumer.py
```

The smoke test checks an independent Maven consumer. Continue with the
[consumer guide](consumer-guide.md) to add coverage to an existing application.

## Gradle

After installing the Maven artifacts above, build the Gradle adapter and install
its implementation and plugin marker locally:

```sh
./gradlew --no-daemon check publishToMavenLocal
python3 scripts/smoke_gradle_consumer.py
python3 scripts/smoke_jacoco_gradle_consumer.py
```

The Gradle wrapper is included. Configure `mavenLocal()` for both plugin and
dependency resolution as shown in the [Gradle guide](gradle.md).

## Verification and publication

CI verifies Maven tests, browser behavior, the independent Maven consumer, Gradle
plugin tests and independent Gradle consumers before publishing snapshots from
`main`. Pull requests verify without publishing. Publication uses the verified
source revision and is followed by consumer checks with fresh dependency caches.

For an evaluation, record the source commit, workflow result and artifact versions
alongside the TeaVM and browser versions used by the consumer.
