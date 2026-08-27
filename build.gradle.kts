import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        // Community, per the plan's locked target (§2): Git4Idea is bundled here too, and an
        // unlicensed Community sandbox does not prompt for a licence on every runIde.
        // 2025.2.6.3 (build 252.28539.97) is the newest Community the Gradle plugin will resolve —
        // it refuses every IC coordinate from 253 onwards, since JetBrains stopped publishing it.
        intellijIdeaCommunity("2025.2.6.3")
        bundledPlugin("Git4Idea")
        testFramework(TestFrameworkType.Platform)
    }

    // kotlinx-serialization is pinned to the platform's own version (see libs.versions.toml).
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.vintage.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The IntelliJ Platform supplies kotlin-stdlib and kotlinx-coroutines at runtime, and the plugin's
// lib/ directory is built from runtimeClasspath. Shipping our own copies there shadows the
// platform's and surfaces as NoSuchMethodError (§3.3). Scoped to the runtime classpaths only:
// excluding these globally would break the Kotlin plugin's own internal resolutions.
listOf(configurations.runtimeClasspath, configurations.testRuntimeClasspath).forEach { configuration ->
    configuration.configure {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains", module = "annotations")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // The platform's own interfaces (e.g. ToolWindowFactory) rely on real JVM default
        // methods now. Without this, Kotlin falls back to DefaultImpls delegation, which makes
        // every implementing class synthesize forwarding overrides for every inherited default
        // method — including ones marked @ApiStatus.Internal — and that's what verifyPlugin was
        // flagging as internal API usage.
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            // Compiled against IntelliJ Platform 252 (IDEA Community 2025.2.6.3), so 252 is the
            // honest lower bound. This must track the target above: declaring a build the code was
            // not compiled against is how plugins ship NoSuchMethodError.
            sinceBuild = "252"
            untilBuild = provider { null }
        }
    }

    // Only resolved when publishPlugin/signPlugin actually run, so a local `./gradlew build`
    // (or CI when Marketplace secrets aren't configured yet) never needs these set.
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // A version suffix after '-' selects the Marketplace release channel, e.g.
        // "1.1.0-beta1" publishes to the "beta" channel instead of "default" (stable). Channels
        // besides "default" are opt-in on Marketplace, so this is the safe way to ship something
        // for testing without it reaching everyone on the stable channel.
        channels = providers.gradleProperty("version")
            .orElse(project.version.toString())
            .map { listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" }) }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
}

tasks {
    test {
        useJUnitPlatform()
    }
}
