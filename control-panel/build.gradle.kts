import dev.detekt.gradle.extensions.FailOnSeverity
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

group = rootProject.group
version = rootProject.version

base {
    archivesName.set("afc-gate-control-panel")
}

repositories {
    google()
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(project(":"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.resources)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.swing)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

compose.desktop {
    application {
        mainClass = "com.qurkos.gate.controlpanel.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "AFC Gate Control Panel"
            // Native packagers accept stable numeric versions but not Gradle prerelease suffixes.
            packageVersion = project.version.toString().substringBefore('-')
            description = "Desktop control panel for physical AFC gate controllers"
            vendor = "Qurkos"
        }
    }
}

dependencyLocking {
    lockAllConfigurations()
}

tasks.test {
    useJUnitPlatform()
}

ktlint {
    verbose.set(true)
    outputToConsole.set(true)
    filter {
        exclude { entry -> entry.file.path.contains("/build/generated/") }
    }
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    failOnSeverity.set(FailOnSeverity.Warning)
    config.setFrom(files(rootProject.file("config/detekt/detekt.yml")))
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
