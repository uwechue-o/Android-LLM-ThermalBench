// data/telemetry — thermal, power, frequency, memory and device sources.
// Every source degrades to null where the platform withholds data; nothing is
// ever fabricated (GUIDE.md §6).
plugins {
    alias(libs.plugins.thermalbench.android.library)
    alias(libs.plugins.thermalbench.android.hilt)
}

android {
    namespace = "com.thermalbench.telemetry"
}

dependencies {
    api(project(":core:domain"))
    implementation(libs.androidx.core.ktx)
}
