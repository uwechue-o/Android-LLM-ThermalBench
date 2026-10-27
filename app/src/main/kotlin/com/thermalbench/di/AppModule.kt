package com.thermalbench.di

import com.thermalbench.runner.RunController
import com.thermalbench.runner.ServiceRunController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds abstract fun runController(impl: ServiceRunController): RunController
}
