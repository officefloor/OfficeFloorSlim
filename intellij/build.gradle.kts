import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

// The OfficeFloor IntelliJ plugin (Marketplace ID net.officefloor.ide.rest.plugin), copied in
// from officefloor/OfficeFloor-IntelliJ-Plugin at 7bc06d4. Java 17, the group and the version
// (-Pversion, from the tag) come from the root build.
plugins {
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // the graph model, shared with the classification; the plugin reads the YAML by IntelliJ's
    // PSI, so the SnakeYAML reader's library stays out of the plugin
    implementation(project(":officefloorslim-graph")) {
        exclude(group = "org.yaml", module = "snakeyaml")
    }

    intellijPlatform {
        create(
            IntelliJPlatformType.fromCode(providers.gradleProperty("platformType").get()),
            providers.gradleProperty("platformVersion").get()
        )

        // We parse and navigate the YAML via the bundled YAML plugin's PSI.
        bundledPlugin("org.jetbrains.plugins.yaml")

        // Needed to resolve Java references for "jump to Java class".
        bundledPlugin("com.intellij.java")

        pluginVerifier()
        // the PSI reader's parity with the graph module's SnakeYAML reader runs in the platform
        testFramework(TestFrameworkType.Platform)
    }

    // the tests are JUnit 4; the root build runs tests on the JUnit Platform
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
    // the SnakeYAML reader, to compare the PSI reader with
    testImplementation("org.yaml:snakeyaml:2.5")
}

intellijPlatform {
    // the distribution's name, and the plugin's directory in the IDE
    projectName = "officefloor"
    pluginConfiguration {
        version = project.version.toString()
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }

    // Plugin signing. JetBrains Marketplace strongly recommends signed plugins.
    // The Release workflow supplies these from the OfficeFloor organisation's
    // secrets (see officefloor/OfficeFloorMaintenance). A secret that is not set
    // arrives as an empty variable, which counts as unset; publishPlugin refuses
    // to run without all of them (below), so nothing is published unsigned.
    signing {
        certificateChain = secret("CERTIFICATE_CHAIN")
        privateKey = secret("PRIVATE_KEY")
        password = secret("PRIVATE_KEY_PASSWORD")
    }

    // Publishing via `./gradlew publishPlugin`. PUBLISH_TOKEN is a Marketplace
    // permanent token, from the OfficeFloor organisation's secrets as well.
    publishing {
        token = secret("PUBLISH_TOKEN")
    }

    // The JetBrains Plugin Verifier. -PverifyIde=IC:2024.2.6 or
    // IntellijIdea:2026.2.3 verifies against that one IDE: the IDEs together do not
    // fit on one runner's disk, so the Verify plugin workflow runs one per job, and
    // the Release workflow publishes only after all pass. Without it, every IDE the
    // platform recommends for the plugin's compatibility range.
    pluginVerification {
        ides {
            val one = providers.gradleProperty("verifyIde").orNull
            if (one != null) {
                val (type, version) = one.split(":", limit = 2)
                // a type by its name (IntellijIdea, the unified IDE from 2025.3) or its code (IC)
                create(IntelliJPlatformType.values().firstOrNull { it.name == type } ?: IntelliJPlatformType.fromCode(type), version)
            } else {
                recommended()
            }
        }
    }
}

fun secret(name: String) = providers.environmentVariable(name).filter { it.isNotBlank() }
val publishingSecrets = listOf("PUBLISH_TOKEN", "CERTIFICATE_CHAIN", "PRIVATE_KEY", "PRIVATE_KEY_PASSWORD")

// IDEA 2024.2.4 predates Java 25. On startup its bundled Gradle plugin
// (com.intellij.gradle) downloads JetBrains' current Gradle/JVM compatibility
// matrix, which now lists Java 25, and 2024.2.4's JavaVersion.parse("25") throws
// (IllegalArgumentException: 25), also provoking a JreHiDpiUtil startup assertion.
// This plugin has no dependency on Gradle integration, so we disable that bundled
// plugin in the runIde sandbox to keep the sandbox IDE clean. Remove this once the
// development platformVersion is a build that understands Java 25.
val sandboxConfigDir = layout.buildDirectory.dir(
    "idea-sandbox/${providers.gradleProperty("platformType").get()}-${providers.gradleProperty("platformVersion").get()}/config"
)
val disableBundledGradlePlugin by tasks.registering {
    val disabledPluginsFile = sandboxConfigDir.map { it.file("disabled_plugins.txt") }
    outputs.file(disabledPluginsFile)
    doLast {
        val file = disabledPluginsFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText("com.intellij.gradle\n")
    }
}

tasks {
    named("runIde") {
        dependsOn(disableBundledGradlePlugin)
    }
    named("disableBundledGradlePlugin") {
        mustRunAfter("prepareSandbox")
    }
    // publishPlugin does not run verifyPlugin itself (every IDE does not fit on one
    // runner); the Release workflow publishes only after the per-IDE verify jobs
    // pass. It never publishes without the token, or unsigned.
    named("publishPlugin") {
        val secrets = publishingSecrets.associateWith { secret(it) }
        doFirst {
            val missing = secrets.filterValues { !it.isPresent }.keys
            if (missing.isNotEmpty()) {
                throw GradleException("publishPlugin needs ${missing.joinToString()} (nothing was published)")
            }
        }
    }
}
