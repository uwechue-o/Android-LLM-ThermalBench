package com.thermalbench.persistence.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        RunEntity::class,
        IterationSampleEntity::class,
        ThermalSampleEntity::class,
        ThermalTransitionEntity::class,
        PowerSampleEntity::class,
        SpecEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ThermalBenchDatabase : RoomDatabase() {

    abstract fun runDao(): RunDao

    abstract fun sampleDao(): SampleDao

    abstract fun specDao(): SpecDao

    companion object {
        const val NAME = "thermalbench.db"
    }
}
