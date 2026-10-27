package com.thermalbench.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** Shorthand for the shared version catalog inside convention plugins. */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(alias: String): String = findVersion(alias).get().requiredVersion

internal fun VersionCatalog.versionInt(alias: String): Int = version(alias).toInt()

/**
 * Reproducible builds: a reproducibility tool should itself build
 * reproducibly (GUIDE.md §7, Build & quality).
 */
internal fun Project.configureReproducibleArchives() {
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

/** Common Android configuration shared by library and application modules. */
internal fun Project.configureAndroidCommon(extension: CommonExtension<*, *, *, *, *, *>) {
    val catalog = libs
    extension.apply {
        compileSdk = catalog.versionInt("compileSdk")
        // Pinned so the llama.cpp native build is reproducible instead of
        // depending on whichever NDK happens to be installed.
        ndkVersion = catalog.version("ndk")

        defaultConfig {
            minSdk = catalog.versionInt("minSdk")
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        @Suppress("UnstableApiUsage")
        testOptions {
            unitTests.isIncludeAndroidResources = true
            unitTests.isReturnDefaultValues = true
        }

        @Suppress("UnstableApiUsage")
        lint {
            // Every check stays on. The androidx lint checks used to crash here
            // ("Found class Ka…, but interface was expected") because they are
            // built against a newer Kotlin Analysis API than AGP 8.7.3's lint;
            // gradle.properties pins a newer standalone lint instead, so no
            // detector has to be switched off to get a green build.
            abortOnError = true
            warningsAsErrors = false
        }

        packaging {
            resources.excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }

    extensions.configure(KotlinAndroidProjectExtension::class.java) {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            allWarningsAsErrors.set(false)
            freeCompilerArgs.addAll(
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            )
        }
    }
}

internal fun Project.configureKotlinJvm() {
    extensions.configure(KotlinJvmProjectExtension::class.java) {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            )
        }
    }
}
