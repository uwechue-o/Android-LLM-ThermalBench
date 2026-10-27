// data/backend-genie — Qualcomm Genie adapter: LLMs on the Qualcomm AI Runtime
// (QAIRT), on the Hexagon NPU or on Qualcomm's own CPU path.
//
// The QAIRT SDK is Qualcomm's, downloaded under their licence, and is never
// part of this repository. Point thermalbench.qairtDir (user-level
// ~/.gradle/gradle.properties) at an unpacked SDK to build the native half
// and package the runtime libraries it needs. Without it the Kotlin adapter
// still compiles and reports the backend as unavailable.
plugins {
    alias(libs.plugins.thermalbench.android.library)
    alias(libs.plugins.thermalbench.android.hilt)
}

val qairtDir: String? = providers.gradleProperty("thermalbench.qairtDir").orNull

// Hexagon versions to package NPU support for: v75 is the Snapdragon 8 Gen 3,
// v73 the 8 Gen 2, v79 the 8 Elite. Each adds a stub and a DSP-side skel.
val hexagonVersions: List<String> = providers.gradleProperty("thermalbench.qairtHexagon")
    .getOrElse("v75")
    .split(',')
    .map { it.trim().lowercase() }
    .filter { it.isNotEmpty() }

// The SDK's own version, recorded in every result as the runtime version.
val qairtVersion: String = qairtDir
    ?.let { file("$it/sdk.yaml") }
    ?.takeIf { it.isFile }
    ?.readLines()
    ?.firstOrNull { it.startsWith("version:") }
    ?.substringAfter(':')
    ?.trim()
    ?: "unknown"

if (qairtDir != null && !file("$qairtDir/include/Genie/GenieDialog.h").exists()) {
    throw GradleException(
        "thermalbench.qairtDir='$qairtDir' is not a QAIRT SDK: include/Genie/GenieDialog.h is missing.",
    )
}

val stagedLibraries = layout.buildDirectory.dir("qairt-jniLibs")
val stagedNotices = layout.buildDirectory.dir("qairt-assets")

android {
    namespace = "com.thermalbench.backend.genie"

    buildFeatures.buildConfig = true

    defaultConfig {
        buildConfigField("String", "QAIRT_VERSION", "\"$qairtVersion\"")
    }

    if (qairtDir != null) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
            }
        }
        defaultConfig {
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DQAIRT_DIR=$qairtDir",
                        "-DANDROID_STL=c++_shared",
                        "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                        "-DCMAKE_BUILD_TYPE=Release",
                    )
                    cppFlags += listOf("-std=c++17")
                }
            }
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
        }
        sourceSets["main"].jniLibs.srcDir(stagedLibraries)
        sourceSets["main"].assets.srcDir(stagedNotices)
    }
}

if (qairtDir != null) {
    // Only what running a prepared model needs. libQnnHtpPrepare.so (85 MB)
    // compiles graphs on the device; Genie bundles arrive already compiled.
    val stageQairtLibraries = tasks.register<Sync>("stageQairtLibraries") {
        into(stagedLibraries.map { it.dir("arm64-v8a") })
        from("$qairtDir/lib/aarch64-android") {
            include(
                "libGenie.so",
                "libQnnSystem.so",
                "libQnnHtp.so",
                "libQnnGenAiTransformer.so",
                "libQnnGenAiTransformerModel.so",
                "libQnnGenAiTransformerCpuOpPkg.so",
            )
            hexagonVersions.forEach { include("libQnnHtp${it.uppercase()}Stub.so") }
        }
        hexagonVersions.forEach { version ->
            from("$qairtDir/lib/hexagon-$version/unsigned") {
                include("libQnnHtp${version.uppercase()}Skel.so")
            }
        }
    }
    tasks.matching { it.name.matches(Regex("merge.*JniLibFolders")) }.configureEach {
        dependsOn(stageQairtLibraries)
    }

    // The third-party notices Qualcomm ships with these libraries travel with
    // them into the APK, and the licences screen shows them. Copied from the
    // SDK at build time, never committed.
    val stageQairtNotices = tasks.register<Sync>("stageQairtNotices") {
        into(stagedNotices.map { it.dir("qairt") })
        from(qairtDir) { include("NOTICE.txt") }
    }
    tasks.matching { it.name.matches(Regex("(merge|package).*Assets")) }.configureEach {
        dependsOn(stageQairtNotices)
    }
}

dependencies {
    api(project(":core:domain"))
    implementation(project(":data:backend-api"))
    implementation(libs.androidx.tracing.ktx)
}
