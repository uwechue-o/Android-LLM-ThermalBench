package com.thermalbench.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Convention for Android application modules: the Compose operator console
 * (:app) and the headless instrumentation harness (:harness).
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("org.jetbrains.kotlin.android")
        pluginManager.apply("org.jetbrains.kotlinx.kover")

        extensions.configure<ApplicationExtension> {
            configureAndroidCommon(this)
            defaultConfig.targetSdk = libs.versionInt("targetSdk")

            // The inference runtimes ship native libraries for four ABIs, which
            // makes an APK that has to be pushed to a fleet several times larger
            // than it needs to be. Every device worth thermally profiling is
            // arm64; pass -Pthermalbench.includeX86=true to add x86_64 for an
            // emulator smoke test.
            val includeX86 = providers.gradleProperty("thermalbench.includeX86")
                .map { it.toBoolean() }
                .getOrElse(false)
            defaultConfig.ndk {
                abiFilters += buildList {
                    add("arm64-v8a")
                    if (includeX86) add("x86_64")
                }
            }
            buildTypes {
                release {
                    isMinifyEnabled = true
                    isShrinkResources = true
                    proguardFiles(
                        getDefaultProguardFile("proguard-android-optimize.txt"),
                        "proguard-rules.pro",
                    )
                }
                debug {
                    applicationIdSuffix = ".debug"
                }
            }
        }
        configureReproducibleArchives()

        dependencies {
            add("implementation", libs.findLibrary("kotlinx-coroutines-core").get())
            add("implementation", libs.findLibrary("kotlinx-coroutines-android").get())
            add("implementation", libs.findLibrary("androidx-core-ktx").get())
            add("testImplementation", libs.findLibrary("junit4").get())
            add("testImplementation", libs.findLibrary("robolectric").get())
            add("testImplementation", libs.findLibrary("truth").get())
            add("testImplementation", libs.findLibrary("mockk").get())
            add("testImplementation", libs.findLibrary("turbine").get())
            add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
            add("androidTestImplementation", libs.findLibrary("androidx-test-ext-junit").get())
            add("androidTestImplementation", libs.findLibrary("androidx-test-espresso-core").get())
        }
    }
}
