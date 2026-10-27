package com.thermalbench.buildlogic

/**
 * The Clean Architecture layering rule, as pure functions over module paths.
 *
 * Kept separate from [DependencyRulesPlugin] so the rule that guards the
 * project's central architectural guarantee can be unit tested without
 * standing up a Gradle build. The plugin is the enforcement mechanism; this is
 * the decision.
 */
internal object LayerRules {

    /**
     * A layer is either a *group* of modules, written with a trailing colon, or
     * a single named module.
     *
     * The distinction matters: matching a single module by bare prefix would
     * make `:data:engineering-tools` count as the engine composition root and
     * inherit its permission to reach sideways across the data layer, and would
     * make `:appwidget` count as `:app`. A named module matches itself and its
     * own subprojects, nothing else.
     */
    enum class Layer(val prefix: String, private val isGroup: Boolean) {
        CORE(":core:", true),

        // Listed before DATA so the more specific path wins the match.
        ENGINE(":data:engine", false),
        DATA(":data:", true),
        HARNESS(":harness", false),
        APP(":app", false),
        TOOLS(":tools:", true),
        ;

        fun matches(path: String): Boolean =
            if (isGroup) path.startsWith(prefix) else path == prefix || path.startsWith("$prefix:")

        companion object {
            // values(), not entries: build-logic compiles against Gradle's
            // embedded Kotlin, whose language level predates enum entries.
            fun of(path: String): Layer? = values().firstOrNull { it.matches(path) }
        }
    }

    /** Configurations that actually carry a compile or runtime dependency. */
    val CHECKED_CONFIGURATIONS: Set<String> = setOf(
        "api", "implementation", "compileOnly", "runtimeOnly",
        "debugImplementation", "releaseImplementation", "ksp",
    )

    /**
     * True when a module in [from] may depend on the module at [toPath].
     *
     * Arrows point inward only. The edge that matters most is `harness ↛ app`:
     * if the headless harness ever depends on the UI process, the headless
     * execution path is lost and with it the credibility of every measurement.
     */
    fun isAllowed(from: Layer, to: Layer, toPath: String): Boolean = when (from) {
        Layer.CORE -> to == Layer.CORE
        Layer.DATA -> to == Layer.CORE || toPath == ENGINE_FREE_SHARED_MODULE
        // The execution-engine composition root is the one module permitted to
        // reach sideways across the data layer.
        Layer.ENGINE -> to == Layer.CORE || to == Layer.DATA || to == Layer.ENGINE
        Layer.HARNESS -> to == Layer.CORE || to == Layer.DATA || to == Layer.ENGINE
        Layer.APP -> to != Layer.TOOLS
        Layer.TOOLS -> to == Layer.CORE || to == Layer.TOOLS
    }

    /** Convenience for callers holding raw paths. Unknown paths are not policed. */
    fun isAllowed(fromPath: String, toPath: String): Boolean {
        val from = Layer.of(fromPath) ?: return true
        val to = Layer.of(toPath) ?: return true
        return isAllowed(from, to, toPath)
    }

    /** The shared port module every data adapter is allowed to depend on. */
    const val ENGINE_FREE_SHARED_MODULE = ":data:backend-api"
}
