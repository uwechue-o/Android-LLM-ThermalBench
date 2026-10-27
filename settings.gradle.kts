// ThermalBench — multi-module Gradle settings.
// Module topology mirrors GUIDE.md §2. Dependency direction (inward only, and
// harness never depending on app) is enforced mechanically by the
// `thermalbench.dependency.rules` convention plugin, not by review discipline.

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "ThermalBench"

// ---- core: pure Kotlin/JVM, zero Android ----------------------------------
include(":core:domain")
include(":core:analysis")
include(":core:schema")

// ---- data: ports & adapters -----------------------------------------------
include(":data:backend-api")
include(":data:backend-litert")
include(":data:backend-llamacpp")
include(":data:backend-genie")
include(":data:backend-mediapipe")
include(":data:backend-executorch")
include(":data:telemetry")
include(":data:persistence")
include(":data:leaderboard")

// Composition root of the execution engine, shared by :harness and :app.
include(":data:engine")

// ---- harness: HEADLESS runner (am instrument). Never depends on :app -------
include(":harness")

// ---- app: Compose MVVM operator console -----------------------------------
include(":app")

// ---- tools: JVM-only, headless -------------------------------------------
include(":tools:cli")
include(":tools:analysis-cli")
