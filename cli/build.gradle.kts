// The command line: scan a repository, report as versioned JSON or Markdown.
description = "OfficeFloorSlim command line"

dependencies {
    implementation(project(":officefloorslim-core"))
    implementation("info.picocli:picocli:4.7.7")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.20.2")
}

// One jar with every dependency in it: java -jar cli/build/libs/officefloorslim.jar scan
val fatJar by tasks.registering(Jar::class) {
    archiveFileName.set("officefloorslim.jar")
    manifest {
        attributes(
            "Main-Class" to "net.officefloor.officefloorslim.cli.OfficeFloorSlim",
            "Implementation-Title" to "officefloorslim",
            "Implementation-Version" to project.version,
        )
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/*/module-info.class", "module-info.class")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.named("assemble") { dependsOn(fatJar) }
