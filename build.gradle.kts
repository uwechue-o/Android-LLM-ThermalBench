// Root build — declares plugin versions once; modules apply convention plugins.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.aboutlibraries) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
    alias(libs.plugins.bcv)

    // Enforces the inward-only dependency rule (and harness never seeing app)
    // across every subproject.
    alias(libs.plugins.thermalbench.dependency.rules)
}

// ---- Static analysis ---------------------------------------------------------
detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    source.setFrom(
        subprojects.flatMap { p ->
            listOf("src/main/kotlin", "src/main/java", "src/test/kotlin").map { p.file(it) }
        },
    )
}

// ---- Coverage: aggregate every module into the root report ------------------
dependencies {
    subprojects.forEach { sub ->
        sub.plugins.withId("org.jetbrains.kotlinx.kover") {
            kover(sub)
        }
    }
}

// ---- Public API surface: the result schema is a public contract (GUIDE.md §7) --
apiValidation {
    // Only the pure core modules publish a checked API. Everything else is an
    // implementation detail and may change freely.
    ignoredProjects.addAll(
        listOf(
            "backend-api", "backend-litert", "backend-llamacpp", "backend-mediapipe",
            "backend-executorch", "telemetry", "persistence", "leaderboard", "engine",
            "harness", "app", "cli", "analysis-cli",
        ),
    )
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
