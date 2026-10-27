plugins {
    `kotlin-dsl`
}

group = "com.thermalbench.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    compileOnly(libs.plugin.android.gradle)
    compileOnly(libs.plugin.kotlin.gradle)
    compileOnly(libs.plugin.kotlin.compose)
    compileOnly(libs.plugin.ksp.gradle)
    compileOnly(libs.plugin.hilt.gradle)

    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

gradlePlugin {
    plugins {
        register("kotlinJvm") {
            id = "thermalbench.kotlin.jvm"
            implementationClass = "com.thermalbench.buildlogic.KotlinJvmConventionPlugin"
        }
        register("androidLibrary") {
            id = "thermalbench.android.library"
            implementationClass = "com.thermalbench.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidApplication") {
            id = "thermalbench.android.application"
            implementationClass = "com.thermalbench.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidCompose") {
            id = "thermalbench.android.compose"
            implementationClass = "com.thermalbench.buildlogic.AndroidComposeConventionPlugin"
        }
        register("androidHilt") {
            id = "thermalbench.android.hilt"
            implementationClass = "com.thermalbench.buildlogic.AndroidHiltConventionPlugin"
        }
        register("dependencyRules") {
            id = "thermalbench.dependency.rules"
            implementationClass = "com.thermalbench.buildlogic.DependencyRulesPlugin"
        }
    }
}
