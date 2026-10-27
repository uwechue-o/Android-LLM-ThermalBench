// app — Compose MVVM operator console. A convenience wrapper around the
// harness; the in-app runner is a foreground service (GUIDE.md §4.2), never
// WorkManager.
plugins {
    alias(libs.plugins.thermalbench.android.application)
    alias(libs.plugins.thermalbench.android.compose)
    alias(libs.plugins.thermalbench.android.hilt)
    alias(libs.plugins.kotlin.serialization)
    // Generates the open-source licences list from the dependencies actually
    // packaged, so it cannot drift from the build.
    alias(libs.plugins.aboutlibraries)
}

android {
    namespace = "com.thermalbench"

    defaultConfig {
        applicationId = "com.thermalbench"
        versionCode = 1
        versionName = "0.1.0"
        vectorDrawables.useSupportLibrary = true
    }

    // The OpenCL library llama.cpp links against is the GPU vendor's, loaded
    // from the device. The build's stand-in exists only to link with; packaged,
    // it would shadow the vendor's and every OpenCL call would trap.
    packaging {
        jniLibs {
            excludes += "**/libOpenCL.so"
        }
    }

    // With NPU libraries packaged, native libraries are extracted to disk: the
    // vendor runtime loads its DSP-side libraries by directory path, which a
    // library left inside the APK does not have.
    if (providers.gradleProperty("thermalbench.npuLibsDir").isPresent ||
        providers.gradleProperty("thermalbench.qairtDir").isPresent
    ) {
        packaging.jniLibs.useLegacyPackaging = true
    }
    // LiteRT's NPU folder and the QAIRT SDK can both supply QNN libraries.
    packaging.jniLibs.pickFirsts += "**/libQnn*.so"
}

// The project's own LICENSE, shipped for the licences screen. Copied at build
// time so the screen always shows the file at the repository root.
val licenceAssets = layout.buildDirectory.dir("generated/licenceAssets")
val stageProjectLicence = tasks.register<Sync>("stageProjectLicence") {
    into(licenceAssets.map { it.dir("licences") })
    from(rootProject.file("LICENSE")) { rename { "LICENSE.txt" } }
}
android.sourceSets["main"].assets.srcDir(licenceAssets)
tasks.matching { it.name.matches(Regex("(merge|package).*Assets")) }.configureEach {
    dependsOn(stageProjectLicence)
}

aboutLibraries {
    android {
        // Generates res/raw/aboutlibraries.json for the licences screen.
        registerAndroidTasks.set(true)
    }
    collect {
        // Components that are not Maven dependencies: llama.cpp and the
        // Khronos headers it is built with.
        configPath.set(file("config/aboutlibraries"))
    }
    library {
        // Kotlin Multiplatform libraries publish a common and an -android
        // artifact under one name; list each library once.
        duplicationMode.set(com.mikepenz.aboutlibraries.plugin.DuplicateMode.MERGE)
        duplicationRule.set(com.mikepenz.aboutlibraries.plugin.DuplicateRule.SIMPLE)
        // AndroidX and Jetpack Compose (Google's, and JetBrains' multiplatform
        // build of it) are left off the screen at the project's request. They
        // are Apache-2.0; that licence's text remains on the screen through
        // the other Apache-2.0 libraries listed.
        exclusionPatterns.addAll(
            Regex("""androidx\..*""").toPattern(),
            Regex("""org\.jetbrains\.compose\..*""").toPattern(),
        )
    }
    // Reproducible builds: licence data comes from the dependencies' own POMs
    // and the config above, never from the network.
    offlineMode.set(true)
}

dependencies {
    implementation(project(":data:engine"))
    implementation(project(":data:leaderboard"))

    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.tracing.ktx)
    implementation(libs.aboutlibraries.compose.m3)
}
