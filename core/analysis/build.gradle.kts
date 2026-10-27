// core/analysis — decay fitting, steady-state detection, statistics.
// Pure Kotlin so the exact same math powers the live on-device chart and the
// offline cross-device report (GUIDE.md §2).
plugins {
    alias(libs.plugins.thermalbench.kotlin.jvm)
}

dependencies {
    api(project(":core:domain"))
    implementation(libs.commons.math3)
}
