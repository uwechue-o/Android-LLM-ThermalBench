// tools/analysis-cli — offline re-analysis, cross-device comparison, plots and
// device reports. Uses the exact same core/analysis math, and the same chart
// painter, as the device.
plugins {
    alias(libs.plugins.thermalbench.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("com.thermalbench.tools.analysis.MainKt")
    applicationName = "thermalbench-analyze"
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:analysis"))
    implementation(project(":core:schema"))
    implementation(libs.clikt)
    implementation(libs.mordant)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlin.csv)
    implementation(libs.pdfbox)
    implementation(libs.kotlinx.html)
}
