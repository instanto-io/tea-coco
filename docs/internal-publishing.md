# Internal snapshot publication

Source repository: **https://github.com/instanto-io/tea-coco**.
Initial package registry: **https://packages.instanto.io/api/packages/instanto-io/maven**.
Maven Central releases will be added later; no anonymous Central release is claimed by this snapshot setup.

## Published artifacts

All use version `0.1.0-SNAPSHOT` during initial evaluation:

| Coordinates | Purpose |
| --- | --- |
| io.instanto:tea-coco-parent | Maven metadata parent; no consumer has to inherit from it. |
| io.instanto:tea-coco | Compiler extension, browser runtime and standalone reporter. |
| io.instanto:tea-coco-maven-plugin | Maven report goal. |
| io.instanto:tea-coco-gradle-plugin | Gradle integration. |
| io.instanto.tea-coco:io.instanto.tea-coco.gradle.plugin | Gradle plugin marker for `plugins { id(...) }`. |

Source JARs are included for library/plugin implementations. The example is a verification fixture and is not deployed.
The project has its own standalone parent; building it does not require an Instanto private parent or TeaVM fork.

## Credentials and consumers

The CI repository must have access to the organisation's existing `FORGEJO_PACKAGE_USER` and
`FORGEJO_PACKAGE_TOKEN` secrets (package write permission). The workflow also accepts the existing
`FORGEJO_PACKAGES_TOKEN` fallback. Consumers need package read access; their token can be read-only.
Never commit token values to a POM, settings file or Gradle script.

Maven consumers may reuse [.github/maven-settings.xml](../.github/maven-settings.xml). It contains environment
variable references, not credentials, and declares both dependency and plugin repositories. Use:

```sh
# Set FORGEJO_PACKAGE_USER and FORGEJO_PACKAGE_TOKEN securely in your shell/CI.
mvn --settings /path/to/maven-settings.xml -U -Ptea-coco clean verify
```

The server ID is `forgejo-instanto` and must match the repository ID. Existing Instanto shared CI already uses this
ID; add the coverage profile to the consumer without replacing its other settings.
For Gradle plugin marker and dependency repositories, use the [Gradle guide](gradle.md).

## CI publication contract

Pushes and manual runs on `main` verify Maven tests, real-browser behavior, the independent Maven consumer, Gradle
plugin tests and independent Gradle consumers before deployment. Pull requests verify without publishing.
The checked-out, verified commit supplies the published snapshot artifacts; no publication-only path bypasses tests.

Maven publishes the parent, core and Maven plugin. Gradle publishes its implementation and marker. Then fresh-cache
consumer builds resolve the packages from the registry and execute real-browser coverage tests. A successful upload
alone is insufficient; a readback/consumer failure fails the workflow. Partial publication is possible if one registry
operation fails; the workflow must be rerun and verified before recommending that snapshot.

The workflow prints no token values. Missing repository secret access fails with a specific credential message
before deployment. Grant this repository access to the existing organisation secrets rather than inventing new
credentials in source control. Snapshot consumers use `-U` (Maven) / `--refresh-dependencies` (Gradle) when updating.
For evaluation records, retain the workflow URL, git SHA and resolved timestamped snapshot versions.

## Local maintainer verification

```sh
mvn -B -ntp -s central-settings.xml clean install
python3 scripts/smoke_consumer.py
./gradlew --no-daemon check publishToMavenLocal
python3 scripts/smoke_gradle_consumer.py
```

The same commands form the pre-publication gate. Internal packaging is independent of future Maven Central signing,
namespace verification and release staging; those will be configured when moving beyond internal evaluation.
