plugins {
    `java-gradle-plugin`
    `maven-publish`
}

group = "io.instanto"
version = "0.1.0-SNAPSHOT"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
repositories { mavenCentral() }
dependencies { testImplementation("junit:junit:4.13.2") }

gradlePlugin {
    plugins {
        create("teaCoco") {
            id = "io.instanto.tea-coco"
            implementationClass = "io.instanto.teacoco.gradle.TeaCocoPlugin"
            displayName = "TeaCoCo browser coverage"
            description = "Java source coverage for unmodified TeaVM browser tests"
        }
    }
}

publishing {
    repositories {
        maven {
            name = "Instanto"
            url = uri("https://packages.instanto.io/api/packages/instanto-io/maven")
            credentials {
                username = providers.environmentVariable("FORGEJO_PACKAGE_USER").orNull
                password = providers.environmentVariable("FORGEJO_PACKAGE_TOKEN").orNull
            }
        }
    }
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("TeaCoCo Gradle integration")
            description.set("Optional Java line and branch coverage for unmodified TeaVM releases")
            url.set("https://github.com/instanto-io/tea-coco")
            licenses { license {
                name.set("Apache License 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            } }
            scm { url.set("https://github.com/instanto-io/tea-coco") }
        }
    }
}
