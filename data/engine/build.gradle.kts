// data/engine — composition root of the execution engine (GUIDE.md §4).
//
// The headless harness and the Compose app both need the same run engine built
// from the same backend registry, telemetry sources and repositories. Neither
// can depend on the other (harness must never see app, and both are Android
// application modules), so the wiring lives here and is consumed by both. It is
// the one module permitted to reach sideways across the data layer; the
// dependency-rules convention plugin enforces that nothing else does.
plugins {
    alias(libs.plugins.thermalbench.android.library)
    alias(libs.plugins.thermalbench.android.hilt)
}

android {
    namespace = "com.thermalbench.engine"
}

dependencies {
    api(project(":core:domain"))
    api(project(":core:analysis"))
    api(project(":core:schema"))
    api(project(":data:backend-api"))
    api(project(":data:telemetry"))
    api(project(":data:persistence"))

    implementation(project(":data:backend-litert"))
    implementation(project(":data:backend-llamacpp"))
    implementation(project(":data:backend-genie"))
    implementation(project(":data:backend-mediapipe"))
    implementation(project(":data:backend-executorch"))
}
