package com.thermalbench.backend.litert

import com.thermalbench.domain.port.InferenceBackend
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class LiteRtLmModule {

    @Binds @IntoSet abstract fun backend(impl: LiteRtLmBackend): InferenceBackend
}
