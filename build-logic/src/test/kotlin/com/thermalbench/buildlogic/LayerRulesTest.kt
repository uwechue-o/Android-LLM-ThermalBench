package com.thermalbench.buildlogic

import com.thermalbench.buildlogic.LayerRules.Layer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the layering rule that guards the project's central architectural
 * guarantee. The plugin that applies this rule fails the build, so getting the
 * rule itself wrong would either block legitimate work or silently permit the
 * one dependency the design cannot survive.
 */
class LayerRulesTest {

    @Test
    fun `module paths resolve to the right layer`() {
        assertEquals(Layer.CORE, Layer.of(":core:domain"))
        assertEquals(Layer.DATA, Layer.of(":data:telemetry"))
        assertEquals(Layer.HARNESS, Layer.of(":harness"))
        assertEquals(Layer.APP, Layer.of(":app"))
        assertEquals(Layer.TOOLS, Layer.of(":tools:cli"))
    }

    @Test
    fun `the engine module wins over the more general data prefix`() {
        // Declaration order matters here: ":data:engine" also starts with
        // ":data:", so a plain first-match would classify it as DATA and the
        // composition root could not do its job.
        assertEquals(Layer.ENGINE, Layer.of(":data:engine"))
    }

    @Test
    fun `a named module is matched exactly, not by bare prefix`() {
        // Otherwise a future ":data:engineering-tools" would silently inherit
        // the composition root's permission to reach across the data layer.
        assertEquals(Layer.DATA, Layer.of(":data:engineering-tools"))
        assertEquals(Layer.DATA, Layer.of(":data:engine-extras"))
        assertNull(Layer.of(":appwidget"))
        assertNull(Layer.of(":harness-tools"))
        // A named module still covers its own subprojects.
        assertEquals(Layer.APP, Layer.of(":app:ui"))
        assertEquals(Layer.ENGINE, Layer.of(":data:engine:wiring"))
    }

    @Test
    fun `unknown paths are not policed`() {
        assertNull(Layer.of(":something-else"))
        assertTrue(LayerRules.isAllowed(":unknown", ":app"))
    }

    // ---- the edge the whole design depends on ------------------------------

    @Test
    fun `harness may never depend on app`() {
        assertFalse(LayerRules.isAllowed(":harness", ":app"))
    }

    @Test
    fun `harness may depend on core, data and the engine`() {
        assertTrue(LayerRules.isAllowed(":harness", ":core:domain"))
        assertTrue(LayerRules.isAllowed(":harness", ":data:telemetry"))
        assertTrue(LayerRules.isAllowed(":harness", ":data:engine"))
    }

    // ---- inward-only ------------------------------------------------------

    @Test
    fun `core may depend on core alone`() {
        assertTrue(LayerRules.isAllowed(":core:analysis", ":core:domain"))
        assertFalse(LayerRules.isAllowed(":core:domain", ":data:telemetry"))
        assertFalse(LayerRules.isAllowed(":core:domain", ":app"))
        assertFalse(LayerRules.isAllowed(":core:domain", ":harness"))
    }

    @Test
    fun `a data adapter may reach core and the shared port module only`() {
        assertTrue(LayerRules.isAllowed(":data:backend-litert", ":core:domain"))
        assertTrue(LayerRules.isAllowed(":data:backend-litert", ":data:backend-api"))
        // Sideways across the data layer is otherwise forbidden: an adapter
        // reaching into persistence would couple a runtime to storage.
        assertFalse(LayerRules.isAllowed(":data:backend-litert", ":data:persistence"))
        assertFalse(LayerRules.isAllowed(":data:telemetry", ":data:backend-mediapipe"))
        assertFalse(LayerRules.isAllowed(":data:persistence", ":app"))
    }

    @Test
    fun `the engine composition root may reach across the data layer`() {
        assertTrue(LayerRules.isAllowed(":data:engine", ":core:analysis"))
        assertTrue(LayerRules.isAllowed(":data:engine", ":data:persistence"))
        assertTrue(LayerRules.isAllowed(":data:engine", ":data:backend-llamacpp"))
        // But it still may not depend on an entry point.
        assertFalse(LayerRules.isAllowed(":data:engine", ":app"))
        assertFalse(LayerRules.isAllowed(":data:engine", ":harness"))
    }

    @Test
    fun `app may use everything except the JVM tools`() {
        assertTrue(LayerRules.isAllowed(":app", ":core:schema"))
        assertTrue(LayerRules.isAllowed(":app", ":data:engine"))
        assertTrue(LayerRules.isAllowed(":app", ":harness"))
        assertFalse(LayerRules.isAllowed(":app", ":tools:cli"))
    }

    @Test
    fun `tools stay JVM only and never touch an Android module`() {
        assertTrue(LayerRules.isAllowed(":tools:analysis-cli", ":core:analysis"))
        assertTrue(LayerRules.isAllowed(":tools:analysis-cli", ":tools:cli"))
        assertFalse(LayerRules.isAllowed(":tools:cli", ":data:telemetry"))
        assertFalse(LayerRules.isAllowed(":tools:cli", ":app"))
        assertFalse(LayerRules.isAllowed(":tools:cli", ":harness"))
    }

    @Test
    fun `the checked configurations cover the ones that carry real dependencies`() {
        listOf("api", "implementation", "ksp", "compileOnly", "runtimeOnly").forEach {
            assertTrue(it in LayerRules.CHECKED_CONFIGURATIONS, "$it should be checked")
        }
        // Test-only configurations are deliberately not policed: a test may
        // reach for whatever it needs to set up a fixture.
        assertFalse("testImplementation" in LayerRules.CHECKED_CONFIGURATIONS)
    }
}
