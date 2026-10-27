package com.thermalbench.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

/**
 * Convention for pure Kotlin/JVM modules (core/…, tools/…).
 *
 * - JDK 17 bytecode
 * - JUnit 5 platform for tests (Kotest runs on it)
 * - Kover coverage
 * - reproducible archives
 *
 * core/domain in particular must know nothing but the Kotlin stdlib and
 * coroutines (GUIDE.md §2); this plugin adds exactly that and nothing more.
 */
class KotlinJvmConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        pluginManager.apply("org.jetbrains.kotlinx.kover")

        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        configureKotlinJvm()
        configureReproducibleArchives()

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            maxHeapSize = "1g"
            // Determinism matters here: no test parallelism, stable ordering.
            maxParallelForks = 1
            systemProperty("user.timezone", "UTC")
            systemProperty("file.encoding", "UTF-8")
        }

        dependencies {
            add("implementation", libs.findLibrary("kotlinx-coroutines-core").get())
            add("testImplementation", libs.findBundle("jvm-test").get())
            add("testRuntimeOnly", libs.findLibrary("junit-platform-launcher").get())
        }
    }
}
