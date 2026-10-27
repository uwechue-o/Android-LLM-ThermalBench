// data/persistence — Room (samples, timelines, run history), DataStore
// (settings, consent flags) and the on-disk artifact store for result documents.
plugins {
    alias(libs.plugins.thermalbench.android.library)
    alias(libs.plugins.thermalbench.android.hilt)
    alias(libs.plugins.room)
}

android {
    namespace = "com.thermalbench.persistence"
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":core:domain"))
    implementation(project(":core:schema"))
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okio)
}
