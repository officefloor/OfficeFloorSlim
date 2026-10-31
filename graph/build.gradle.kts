// The OfficeFloor graph model: which functions a configuration wires, the class and method each
// is, and the flows between them. Shared by the plugin, which draws it, and the classification,
// which reads it. No IntelliJ dependency.
description = "OfficeFloorSlim graph: the OfficeFloor flow graph model and its YAML reader"

dependencies {
    // the reader outside the IDE; the plugin reads by IntelliJ's PSI and leaves this out
    implementation("org.yaml:snakeyaml:2.5")

    // the model's tests came with the plugin, in JUnit 4
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
}

tasks.test {
    // the plugin's sample configurations, read by the reader's tests
    systemProperty("officefloorslim.samples", rootProject.file("intellij/samples").absolutePath)
}
