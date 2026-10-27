package com.thermalbench.backend.api

import com.thermalbench.domain.port.BackendRegistry
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class BackendApiModule {

    @Binds abstract fun registry(impl: DefaultBackendRegistry): BackendRegistry
}
