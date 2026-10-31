rootProject.name = "officefloorslim"

// Build against the SinewSlim checkout beside this one when it is there (DESIGN.md, Local
// development). Its library projects carry their artifact names, so the dependencies on
// net.officefloor.sinewslim:sinewslim-* below resolve to its projects with no substitution
// block, whatever version is asked for. Without the checkout they come from Maven Central.
val sinewSlim = file("../SinewSlim")
if (sinewSlim.isDirectory) {
    includeBuild(sinewSlim)
}

// The project names are the artifact names; the directories keep short ones.
val modules = mapOf(
    "officefloorslim-core" to "core",
)
for ((name, dir) in modules) {
    include(name)
    project(":$name").projectDir = file(dir)
}
