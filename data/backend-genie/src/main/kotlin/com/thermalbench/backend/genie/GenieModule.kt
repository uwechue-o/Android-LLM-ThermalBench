package com.thermalbench.backend.genie

import com.thermalbench.domain.port.InferenceBackend
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class GenieModule {

    @Binds @IntoSet abstract fun backend(impl: GenieBackend): InferenceBackend
}
