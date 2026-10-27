package com.thermalbench.backend.llamacpp

import com.thermalbench.domain.port.InferenceBackend
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class LlamaCppModule {

    @Binds @IntoSet abstract fun backend(impl: LlamaCppBackend): InferenceBackend
}
