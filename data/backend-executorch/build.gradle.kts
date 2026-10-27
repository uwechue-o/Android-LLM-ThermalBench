// data/backend-executorch — ExecuTorch .pte adapter (phase 2, optional).
//
// The module exists so the topology and DI wiring are complete; the adapter
// registers itself and reports the backend as unavailable until the LLaMA
// runner is wired.
//
// The ExecuTorch runtime itself is deliberately NOT a dependency yet: it adds
// an 8.4 MB native library to every APK, and shipping a runtime the adapter
// cannot call would inflate the harness that has to be pushed to a fleet for no
// benefit. Add `implementation(libs.executorch.android)` back in phase 2, along
// with the code that uses it.
plugins {
    alias(libs.plugins.thermalbench.android.library)
    alias(libs.plugins.thermalbench.android.hilt)
}

android {
    namespace = "com.thermalbench.backend.executorch"

    buildFeatures.buildConfig = true

    defaultConfig {
        buildConfigField("String", "EXECUTORCH_VERSION", "\"${libs.versions.executorch.get()}\"")
    }
}

dependencies {
    api(project(":core:domain"))
    implementation(project(":data:backend-api"))
    implementation(libs.androidx.tracing.ktx)
}
