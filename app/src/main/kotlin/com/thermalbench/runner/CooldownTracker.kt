package com.thermalbench.runner

import com.thermalbench.domain.model.PreconditionState
import com.thermalbench.domain.model.UnmetCondition
import com.thermalbench.domain.model.formatThermalMargin
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Turns the precondition gate's updates into cool-down progress an operator
 * can follow with the screen off.
 *
 * The screen is itself a precondition, so turning it on to check restarts the
 * stabilization window. Progress therefore goes out two ways that need no
 * screen: a spoken announcement at each milestone, and an ongoing notification
 * that says how far along the cooling is for whenever the operator does look.
 *
 * Each cue fires at most once per wait, so a margin hovering around the
 * target does not announce itself over and over.
 */
class CooldownTracker(
    private val targetMargin: Float?,
    /** How often the margin is read out while cooling. */
    private val announceEvery: Duration = 20.seconds,
) {

    enum class Cue {
        /** Half of the gap between the starting margin and the target is closed. */
        HALFWAY,

        /** Thermal status and headroom both meet the policy now. */
        COOLED,
    }

    data class Update(
        /** 0..100 toward the target margin, or null when headroom is not reported. */
        val percent: Int?,
        val text: String,
        val cue: Cue?,
        /** What to say aloud for [cue], or null when there is nothing to announce. */
        val announcement: String?,
    )

    private var startMargin: Float? = null
    private var neededCooling: Boolean? = null
    private var halfwayCued = false
    private var cooledCued = false

    /** Monotonic time of the last announcement of any kind; the gate's own clock, never wall time. */
    private var lastAnnouncedNanos: Long? = null

    fun onWaiting(state: PreconditionState.Waiting): Update {
        val thermalUnmet = state.unmet.filter {
            it is UnmetCondition.ThermalStatusTooHigh || it is UnmetCondition.ThermalHeadroomTooLow
        }
        val margin = state.latest.thermalHeadroom?.let { 1f - it }
        if (neededCooling == null) neededCooling = thermalUnmet.isNotEmpty()
        if (startMargin == null) startMargin = margin

        val percent = percentToward(margin)
        val cue = nextCue(cooled = thermalUnmet.isEmpty(), percent = percent)
        val now = state.latest.elapsedNanos
        val announcement = cue?.let { announce(it, state, margin) }
            ?: periodic(now, thermalUnmet, margin)
        if (announcement != null) lastAnnouncedNanos = now
        return Update(percent, describe(state, thermalUnmet, margin), cue, announcement)
    }

    /**
     * The current margin, read out every [announceEvery] while the
     * device is still cooling. Silent once it is cool enough: what follows is
     * the run itself, and nothing should be talking then.
     */
    private fun periodic(now: Long, thermalUnmet: List<UnmetCondition>, margin: Float?): String? {
        if (thermalUnmet.isEmpty()) return null
        val last = lastAnnouncedNanos
        if (last != null && now - last < announceEvery.inWholeNanoseconds) return null
        val headroom = thermalUnmet.filterIsInstance<UnmetCondition.ThermalHeadroomTooLow>().firstOrNull()
        return if (headroom != null && margin != null) {
            spokenMargin(margin)
        } else {
            val status = thermalUnmet.filterIsInstance<UnmetCondition.ThermalStatusTooHigh>().first()
            "Thermal status ${status.current.name.lowercase()}, need ${status.required.name.lowercase()}."
        }
    }

    /** Written to be heard, not read: no symbols, and the number that matters last. */
    private fun announce(cue: Cue, state: PreconditionState.Waiting, margin: Float?): String = when (cue) {
        Cue.HALFWAY -> buildString {
            append("ThermalBench: halfway cooled.")
            if (margin != null) append(" ").append(spokenMargin(margin))
        }
        Cue.COOLED -> {
            val others = state.unmet.filterNot {
                it is UnmetCondition.ThermalStatusTooHigh || it is UnmetCondition.ThermalHeadroomTooLow
            }
            when {
                others.isNotEmpty() ->
                    "ThermalBench: cool enough. Still waiting for: " +
                        others.joinToString(". ") { it.description.substringBefore(';') } + "."
                state.estimatedReady != null ->
                    "ThermalBench: cool enough. Starting in about ${state.estimatedReady!!.inWholeSeconds} seconds."
                else -> "ThermalBench: cool enough. The run starts once conditions hold steady."
            }
        }
    }

    /** The milestone reached by this update, if any; each fires once per wait. */
    private fun nextCue(cooled: Boolean, percent: Int?): Cue? {
        if (neededCooling != true) return null
        if (cooled) {
            if (cooledCued) return null
            cooledCued = true
            halfwayCued = true
            return Cue.COOLED
        }
        // Halfway is along the margin's gap, so only a device that started
        // short of the target margin has one; a status-only wait does not.
        val halfway = marginHadGap() && percent != null && percent >= HALFWAY_PERCENT
        if (!halfway || halfwayCued) return null
        halfwayCued = true
        return Cue.HALFWAY
    }

    private fun marginHadGap(): Boolean {
        val start = startMargin ?: return false
        val target = targetMargin ?: return false
        return start < target
    }

    private fun percentToward(margin: Float?): Int? {
        val target = targetMargin ?: return null
        val start = startMargin ?: return null
        if (margin == null) return null
        if (start >= target) return FULL_PERCENT
        val fraction = ((margin - start) / (target - start)).coerceIn(0f, 1f)
        return (fraction * FULL_PERCENT).roundToInt()
    }

    private fun describe(
        state: PreconditionState.Waiting,
        thermalUnmet: List<UnmetCondition>,
        margin: Float?,
    ): String {
        val others = state.unmet.size - thermalUnmet.size
        val also = if (others > 0) " · $others other condition(s) unmet" else ""
        val headroomUnmet = thermalUnmet.filterIsInstance<UnmetCondition.ThermalHeadroomTooLow>().firstOrNull()
        val statusUnmet = thermalUnmet.filterIsInstance<UnmetCondition.ThermalStatusTooHigh>().firstOrNull()
        return when {
            headroomUnmet != null && margin != null ->
                "Cooling: margin ${formatThermalMargin(margin)} of ${twoDecimals(headroomUnmet.required)}$also"
            statusUnmet != null -> "Cooling: thermal status ${statusUnmet.current}, need ${statusUnmet.required}$also"
            others > 0 -> "Cool enough · waiting on $others other condition(s)"
            else -> state.estimatedReady
                ?.let { "Cool enough · starting in about ${it.inWholeSeconds} s" }
                ?: "Cool enough · holding steady"
        }
    }

    private fun twoDecimals(value: Float) = String.format(Locale.US, "%.2f", value)

    /**
     * The margin as it is spoken. Formatted exactly as the gate screen and the
     * notification show it, rounded down, so at the moment of a readout the
     * voice and the screen agree.
     */
    private fun spokenMargin(margin: Float) = "Current thermal margin is ${formatThermalMargin(margin)}."

    private companion object {
        const val HALFWAY_PERCENT = 50
        const val FULL_PERCENT = 100
    }
}
