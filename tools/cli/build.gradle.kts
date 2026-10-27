// tools/cli — JVM fleet orchestrator over ADB. Pushes specs, launches the
// headless harness on every attached device, pulls result documents.
plugins {
    alias(libs.plugins.thermalbench.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("com.thermalbench.tools.cli.MainKt")
    applicationName = "thermalbench"
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:schema"))
    implementation(libs.clikt)
    implementation(libs.mordant)
    implementation(libs.adam)
    implementation(libs.kotlinx.serialization.json)
}
