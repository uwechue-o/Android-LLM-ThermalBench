// data/leaderboard — opt-in, consent-gated submission client.
plugins {
    alias(libs.plugins.thermalbench.android.library)
    alias(libs.plugins.thermalbench.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.thermalbench.leaderboard"
}

dependencies {
    api(project(":core:domain"))
    implementation(project(":core:schema"))
    implementation(libs.bundles.ktor.client)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
}
