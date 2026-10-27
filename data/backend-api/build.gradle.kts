// data/backend-api — shared Android-side backend infrastructure: the backend
// registry, model file resolution/hashing, and the generation timer that turns
// streamed tokens into TTFT / prefill / decode figures on a monotonic clock.
plugins {
    alias(libs.plugins.thermalbench.android.library)
    alias(libs.plugins.thermalbench.android.hilt)
}

android {
    namespace = "com.thermalbench.backend.api"
}

dependencies {
    api(project(":core:domain"))
    implementation(libs.okio)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.tracing.ktx)
}
