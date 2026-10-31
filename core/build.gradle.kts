// Classification, the OfficeFloor cost and split, over SinewSlim's facts and beside its cost.
description = "OfficeFloorSlim core: classification, the OfficeFloor cost and split"

val sinewslim = "net.officefloor.sinewslim"
val sinewslimVersion = rootProject.property("sinewslimVersion") as String

dependencies {
    api("$sinewslim:sinewslim-facts:$sinewslimVersion")
    api("$sinewslim:sinewslim-core:$sinewslimVersion")
    // api: the extractors here are JavaParser extractors
    api("$sinewslim:sinewslim-javaparser:$sinewslimVersion")
    implementation("$sinewslim:sinewslim-scan:$sinewslimVersion")
    implementation(project(":officefloorslim-graph"))
}
