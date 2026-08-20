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
            copyright = "Copyright © 2026 Qurkos"
            licenseFile.set(rootProject.layout.projectDirectory.file("LICENSE"))

            windows {
                iconFile.set(project.layout.projectDirectory.file("src/main/packaging/icons/app-icon.ico"))
                menu = true
                menuGroup = "Qurkos"
                shortcut = true
                dirChooser = true
                perUserInstall = false
                // This must remain stable so MSI upgrades replace earlier installations.
                upgradeUuid = "5754EEE4-5D17-4223-8766-626E11E7AC02"
            }

            linux {
                iconFile.set(project.layout.projectDirectory.file("src/main/packaging/icons/app-icon.png"))
                packageName = "afc-gate-control-panel"
                shortcut = true
                menuGroup = "Utility"
                appCategory = "Utility"
                appRelease = "1"
                debMaintainer = "Qurkos <support@qurkos.com>"
            }

            macOS {
                iconFile.set(project.layout.projectDirectory.file("src/main/packaging/icons/app-icon.icns"))
                packageName = "AFC Gate Control Panel"
                bundleID = "com.qurkos.afc.gate-control-panel"
                dockName = "AFC Gate Control Panel"
                appCategory = "public.app-category.utilities"
                minimumSystemVersion = "12.0"
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
    systemProperty(
        "afc.gate.log.dir",
        layout.buildDirectory
            .dir("test-logs")
            .get()
            .asFile.absolutePath,
    )
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
