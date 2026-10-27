package com.thermalbench.persistence

import android.content.Context
import androidx.room.Room
import com.thermalbench.domain.port.ResultCodec
import com.thermalbench.domain.port.ResultRepository
import com.thermalbench.domain.port.SpecRepository
import com.thermalbench.persistence.db.RunDao
import com.thermalbench.persistence.db.SampleDao
import com.thermalbench.persistence.db.SpecDao
import com.thermalbench.persistence.db.ThermalBenchDatabase
import com.thermalbench.schema.JsonResultCodec
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PersistenceProvidesModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): ThermalBenchDatabase =
        Room.databaseBuilder(context, ThermalBenchDatabase::class.java, ThermalBenchDatabase.NAME)
            // No destructive migration: a benchmark history is data someone
            // spent device-hours producing, and silently dropping it on a
            // schema change would be the wrong default for this tool.
            .build()

    @Provides fun runDao(db: ThermalBenchDatabase): RunDao = db.runDao()

    @Provides fun sampleDao(db: ThermalBenchDatabase): SampleDao = db.sampleDao()

    @Provides fun specDao(db: ThermalBenchDatabase): SpecDao = db.specDao()

    @Provides @Singleton fun codec(): JsonResultCodec = JsonResultCodec()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PersistenceModule {

    @Binds abstract fun resultCodec(impl: JsonResultCodec): ResultCodec

    @Binds abstract fun resultRepository(impl: RoomResultRepository): ResultRepository

    @Binds abstract fun specRepository(impl: RoomSpecRepository): SpecRepository
}
