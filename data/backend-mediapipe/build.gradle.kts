// data/backend-mediapipe — MediaPipe Tasks GenAI adapter (.task / .bin bundles).
plugins {
    alias(libs.plugins.thermalbench.android.library)
    alias(libs.plugins.thermalbench.android.hilt)
}

android {
    namespace = "com.thermalbench.backend.mediapipe"

    buildFeatures.buildConfig = true

    defaultConfig {
        buildConfigField("String", "MEDIAPIPE_GENAI_VERSION", "\"${libs.versions.mediapipeGenai.get()}\"")
    }
}

dependencies {
    api(project(":core:domain"))
    implementation(project(":data:backend-api"))
    implementation(libs.mediapipe.tasks.genai)
    implementation(libs.androidx.tracing.ktx)
}
