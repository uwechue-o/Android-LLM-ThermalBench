// core/schema — result document model + canonical serialization + CSV export.
// The canonical encoder is the one piece that is expensive to retrofit and
// free to get right up front (GUIDE.md §9).
plugins {
    alias(libs.plugins.thermalbench.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:domain"))
    api(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.okio)
    implementation(libs.kotlin.csv)
}
