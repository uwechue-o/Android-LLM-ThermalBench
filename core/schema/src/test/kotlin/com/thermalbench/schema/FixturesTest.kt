package com.thermalbench.schema

import com.google.common.truth.Truth.assertThat
import com.thermalbench.domain.model.BackendId
import com.thermalbench.domain.model.Delegate
import com.thermalbench.domain.model.RunProtocol
import com.thermalbench.domain.model.StopCondition
import org.junit.jupiter.api.Test

class FixturesTest {

    private val codec = JsonResultCodec()

    @Test
    fun `reference workload stops on token count, never on end of sequence`() {
        val workload = Fixtures.referenceWorkload()
        val stop = workload.stopCondition
        assertThat(stop).isInstanceOf(StopCondition.TokenCount::class.java)
        // The validator requires these to agree, so every backend emits the
        // same number of tokens and the rates stay comparable.
        assertThat((stop as StopCondition.TokenCount).tokens).isEqualTo(workload.maxOutputTokens)
    }

    @Test
    fun `the prompt hash is part of the workload identity`() {
        val workload = Fixtures.referenceWorkload()
        assertThat(workload.promptSha256).isEqualTo(JsonResultCodec.sha256(Fixtures.REFERENCE_PROMPT))

        val edited = workload.copy(
            prompt = Fixtures.REFERENCE_PROMPT + " ",
            promptSha256 = JsonResultCodec.sha256(Fixtures.REFERENCE_PROMPT + " "),
        )
        assertThat(edited.promptSha256).isNotEqualTo(workload.promptSha256)
    }

    @Test
    fun `default protocol meets the sustained-load iteration floor`() {
        assertThat(RunProtocol.DEFAULT.measuredIterations)
            .isAtLeast(RunProtocol.SUSTAINED_LOAD_MIN_ITERATIONS)
        // Back-to-back by default: a delay between iterations models a duty
        // cycle, which is a different measurement.
        assertThat(RunProtocol.DEFAULT.interIterationDelay.inWholeMilliseconds).isEqualTo(0)
    }

    @Test
    fun `a template spec round-trips and verifies its own identity`() {
        val spec = Fixtures.spec(
            model = Fixtures.placeholderModel(),
            backend = BackendId.LITERT_LM,
            delegate = Delegate.GPU,
            label = "template",
        )
        assertThat(codec.verifyId(spec)).isTrue()
        val document = codec.encodeSpec(spec)
        assertThat(codec.decodeSpec(document)).isEqualTo(spec)
    }
}
