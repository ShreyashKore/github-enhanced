import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "github-enhanced"

pluginManagement {
    plugins {
        // Must not exceed the kotlin-stdlib bundled in the target IDE. Kotlin 2.3 emits
        // @DebugMetadata version 2, which a 2.2.x stdlib rejects with "Debug metadata version
        // mismatch" the moment any coroutine is cancelled. IC 252 ships stdlib 2.2.0 and
        // IU 253 ships 2.2.20, so 2.2.x is the ceiling. See RISKS.md.
        id("org.jetbrains.kotlin.jvm") version "2.2.20"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20"
        id("org.jetbrains.changelog") version "2.5.0"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.18.1"
}

dependencyResolutionManagement {
    // Configure all projects' repositories
    repositories {
        mavenCentral()

        // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
        intellijPlatform {
            defaultRepositories()
        }
    }
}
