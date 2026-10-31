// Every module is plain Java 17, as SinewSlim: the IntelliJ Platform's floor for the 2024.2 line.
// Group net.officefloor.sinewslim, as SinewSlim's; the officefloorslim-* names keep them apart.
// Nothing here is published to Central.
subprojects {
    apply(plugin = "java-library")

    group = "net.officefloor.sinewslim"
    version = rootProject.property("version") as String

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.13.4"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(17)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // SinewSlim's parser and search recurse with the code's nesting
        jvmArgs("-Xss64m")
        maxHeapSize = "2g"
    }
}
