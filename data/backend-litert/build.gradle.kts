// data/backend-litert — LiteRT-LM adapter (Gemma family, .litertlm bundles).
plugins {
    alias(libs.plugins.thermalbench.android.library)
    alias(libs.plugins.thermalbench.android.hilt)
}

// NPU runtime, off by default (thermalbench.npuLibsDir). The Maven LiteRT-LM
// package has no NPU dispatch library, and the NPU vendor's runtime cannot be
// redistributed, so both come from a folder the developer assembles: on a
// Snapdragon, LiteRT's libLiteRtDispatch_Qualcomm.so plus the QAIRT libraries
// for the target chip, under <dir>/arm64-v8a/. Without it the backend offers
// CPU and GPU only.
val npuLibsDir: String? = providers.gradleProperty("thermalbench.npuLibsDir").orNull
if (npuLibsDir != null) {
    val abiDir = file("$npuLibsDir/arm64-v8a")
    val dispatch = abiDir.listFiles { f -> f.name.startsWith("libLiteRtDispatch_") }.orEmpty()
    if (dispatch.isEmpty()) {
        throw GradleException(
            "thermalbench.npuLibsDir='$npuLibsDir' has no arm64-v8a/libLiteRtDispatch_*.so. Put the " +
                "LiteRT dispatch library and the vendor runtime libraries in $abiDir, or unset the property.",
        )
    }
}

android {
    namespace = "com.thermalbench.backend.litert"

    if (npuLibsDir != null) {
        sourceSets["main"].jniLibs.srcDir(npuLibsDir)
    }

    buildFeatures.buildConfig = true

    // The runtime version is recorded in every result document. Taking it from
    // the catalog means the reported version cannot drift from the one linked.
    defaultConfig {
        buildConfigField("String", "LITERT_LM_VERSION", "\"${libs.versions.litertLm.get()}\"")
    }
}

dependencies {
    api(project(":core:domain"))
    implementation(project(":data:backend-api"))
    implementation(libs.litert.lm)
    implementation(libs.androidx.tracing.ktx)
}
