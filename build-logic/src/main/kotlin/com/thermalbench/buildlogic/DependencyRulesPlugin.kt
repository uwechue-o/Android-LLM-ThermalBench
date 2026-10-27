package com.thermalbench.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency

/**
 * Mechanical enforcement of the Clean Architecture dependency rule
 * (GUIDE.md §2): arrows point inward only.
 *
 * ```
 *   core/…       -> core/…
 *   data/…       -> core/…, data/backend-api
 *   data/engine  -> core/…, any data/…       (the execution-engine composition root)
 *   harness      -> core/…, data/…           (NEVER app)
 *   app          -> core/…, data/…, harness
 *   tools/…      -> core/…, tools/…
 * ```
 *
 * `data/engine` is the one module allowed to reach sideways across the data
 * layer. It assembles the run engine from the backend registry, the telemetry
 * sources and the repositories, and exists so the headless harness and the app
 * share one wiring instead of two that drift apart. Nothing depends on it
 * except those two entry points.
 *
 * The critical edge is `harness ↛ app`. If the harness ever depends on the UI
 * process, the headless execution path is lost and with it the credibility of
 * every number. Violations fail the build at configuration time, so there is
 * no way to merge a change that breaks the layering.
 */
class DependencyRulesPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        require(target == target.rootProject) {
            "thermalbench.dependency.rules must be applied to the root project"
        }

        target.subprojects.forEach { sub ->
            sub.afterEvaluate { verify(sub) }
        }

        target.tasks.register("verifyDependencyRules") {
            group = "verification"
            description = "Fails if any module depends on a module in an outer layer (harness must never see app)."
            doLast {
                target.subprojects.forEach { verify(it) }
                logger.lifecycle("Dependency rules verified for ${target.subprojects.size} modules.")
            }
        }
    }

    private fun verify(project: Project) {
        val fromLayer = LayerRules.Layer.of(project.path) ?: return
        val violations = mutableListOf<String>()

        project.configurations
            .filter { it.name in LayerRules.CHECKED_CONFIGURATIONS }
            .forEach { configuration ->
                configuration.dependencies
                    .filterIsInstance<ProjectDependency>()
                    .forEach { dependency ->
                        val toPath = dependency.path
                        val toLayer = LayerRules.Layer.of(toPath) ?: return@forEach
                        if (!LayerRules.isAllowed(fromLayer, toLayer, toPath)) {
                            violations += "${project.path} (${configuration.name}) -> $toPath"
                        }
                    }
            }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "Dependency rule violation — arrows must point inward only:\n" +
                    violations.joinToString("\n") { "  $it" },
            )
        }
    }

}
