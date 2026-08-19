import dev.detekt.gradle.extensions.FailOnSeverity
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.jvm.tasks.Jar
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.dokka)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
    `maven-publish`
}

group = providers.gradleProperty("sdkGroup").get()
version = providers.gradleProperty("sdkVersion").get()

repositories {
    mavenCentral()
}

kotlin {
    explicitApi()
    jvmToolchain(17)
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
        filters {
            include {
                byNames.add("com.qurkos.gate.sdk.**")
            }
            exclude {
                byNames.add("com.qurkos.gate.sdk.internal.**")
            }
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            allWarningsAsErrors.set(true)
        }
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
            testLogging {
                events("failed", "skipped")
                exceptionFormat = TestExceptionFormat.FULL
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            implementation(libs.jserialcomm)
        }
    }
}

ktlint {
    verbose.set(true)
    outputToConsole.set(true)
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    failOnSeverity.set(FailOnSeverity.Warning)
    config.setFrom(files("config/detekt/detekt.yml"))
}

dokka {
    dokkaPublications.html {
        moduleName.set("AFC Gate SDK")
        moduleVersion.set(project.version.toString())
        outputDirectory.set(layout.buildDirectory.dir("documentation/html"))
        includes.from("README.md", "docs/API.md")
        failOnWarning.set(true)
        suppressObviousFunctions.set(true)
    }
    dokkaSourceSets.configureEach {
        documentedVisibilities.set(setOf(VisibilityModifier.Public, VisibilityModifier.Internal))
        reportUndocumented.set(true)
        skipEmptyPackages.set(true)
    }
}

kover {
    reports {
        filters {
            includes {
                classes("com.qurkos.gate.sdk.*")
            }
            excludes {
                classes(
                    "com.qurkos.gate.sdk.internal.JvmSerialPlatformKt",
                    "com.qurkos.gate.sdk.internal.jvm.JSerialCommTransport*",
                )
            }
        }
        verify {
            rule("Minimum production line coverage") {
                minBound(70)
            }
        }
    }
}

dependencyLocking {
    lockAllConfigurations()
}

val dokkaHtmlJar =
    tasks.register<Jar>("dokkaHtmlJar") {
        description = ""
        dependsOn(tasks.named("dokkaGeneratePublicationHtml"))
        from(layout.buildDirectory.dir("documentation/html"))
        archiveClassifier.set("javadoc")
    }

publishing {
    val githubRepository = providers.environmentVariable("GITHUB_REPOSITORY")
    if (githubRepository.isPresent) {
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/${githubRepository.get()}")
                credentials {
                    username = providers.environmentVariable("GITHUB_ACTOR").orNull
                    password = providers.environmentVariable("GITHUB_TOKEN").orNull
                }
            }
        }
    }
    publications.withType<MavenPublication>().configureEach {
        artifact(dokkaHtmlJar)
        pom {
            name.set("AFC Gate SDK")
            description.set("Unified Kotlin API for serial AFC gate controllers")
            url.set("https://github.com/GtechGovind/afc-gate-sdk")
            licenses {
                license {
                    name.set("Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("repo")
                }
            }
            scm {
                connection.set("scm:git:https://github.com/GtechGovind/afc-gate-sdk.git")
                developerConnection.set("scm:git:ssh://git@github.com/GtechGovind/afc-gate-sdk.git")
                url.set("https://github.com/GtechGovind/afc-gate-sdk")
            }
        }
    }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.named("check") {
    dependsOn(
        "detektCommonMainSourceSet",
        "detektCommonTestSourceSet",
        "detektJvmMainSourceSet",
        "detektJvmTestSourceSet",
        "koverVerify",
        "koverXmlReport",
    )
}
