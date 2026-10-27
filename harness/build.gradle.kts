// harness — the HEADLESS runner. The primary execution path (GUIDE.md §4.1).
//
// It is an application with no launcher activity whose *main* source set
// declares an `<instrumentation>` targeting itself, so it is driven with:
//
//   adb shell am instrument -w \
//     -e specPath /sdcard/specs/gemma3n-q4-gpu.json \
//     -e outputDir /sdcard/thermalbench \
//     com.thermalbench.harness/androidx.test.runner.AndroidJUnitRunner
//
// No UI process, no rendering load. This module must NEVER depend on :app —
// the dependency-rules convention plugin fails the build if it does.
plugins {
    alias(libs.plugins.thermalbench.android.application)
    alias(libs.plugins.thermalbench.android.hilt)
}

android {
    namespace = "com.thermalbench.harness"

    defaultConfig {
        applicationId = "com.thermalbench.harness"
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        // The harness is a measuring instrument; keep debug identical to release
        // so numbers do not differ by package name.
        debug {
            applicationIdSuffix = null
        }
        release {
            // No shrinking: JUnit + the test runner are loaded reflectively.
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    // As in :app: the OpenCL stand-in is for linking only; the device's own
    // libOpenCL.so must be the one loaded.
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

dependencies {
    // One dependency: the engine composition root brings in the domain, the
    // analysis, the schema, every backend adapter, telemetry and persistence.
    implementation(project(":data:engine"))

    // Instrumentation entry point lives in the main source set on purpose.
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.rules)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.junit4)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.tracing.ktx)
}
