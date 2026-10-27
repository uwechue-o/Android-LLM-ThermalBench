package com.thermalbench.persistence.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RunDao {

    @Upsert suspend fun upsert(run: RunEntity)

    @Query("SELECT * FROM runs ORDER BY startedAtEpochMillis DESC")
    fun observeAll(): Flow<List<RunEntity>>

    @Query("SELECT * FROM runs WHERE resultId = :resultId")
    suspend fun find(resultId: String): RunEntity?

    @Query("SELECT * FROM runs WHERE specId = :specId ORDER BY startedAtEpochMillis DESC")
    suspend fun findBySpec(specId: String): List<RunEntity>

    @Query("SELECT * FROM runs WHERE complete = 0")
    suspend fun findIncomplete(): List<RunEntity>

    @Query("DELETE FROM runs WHERE resultId = :resultId")
    suspend fun delete(resultId: String)
}

@Dao
interface SampleDao {

    /**
     * Batched flush of iteration samples. REPLACE rather than ABORT so a
     * restarted run overwrites its own partial rows instead of failing.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIterations(samples: List<IterationSampleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertThermalSamples(samples: List<ThermalSampleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertThermalTransitions(transitions: List<ThermalTransitionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPowerSamples(samples: List<PowerSampleEntity>)

    /** One transaction for a whole flush: either the batch lands or none of it does. */
    @Transaction
    suspend fun flush(
        iterations: List<IterationSampleEntity>,
        thermalSamples: List<ThermalSampleEntity>,
        transitions: List<ThermalTransitionEntity>,
        power: List<PowerSampleEntity>,
    ) {
        if (iterations.isNotEmpty()) insertIterations(iterations)
        if (thermalSamples.isNotEmpty()) insertThermalSamples(thermalSamples)
        if (transitions.isNotEmpty()) insertThermalTransitions(transitions)
        if (power.isNotEmpty()) insertPowerSamples(power)
    }

    @Query("SELECT * FROM iteration_samples WHERE resultId = :resultId ORDER BY iterationIndex ASC")
    suspend fun iterations(resultId: String): List<IterationSampleEntity>

    @Query("SELECT * FROM thermal_samples WHERE resultId = :resultId ORDER BY elapsedNanos ASC")
    suspend fun thermalSamples(resultId: String): List<ThermalSampleEntity>

    @Query("SELECT * FROM thermal_transitions WHERE resultId = :resultId ORDER BY elapsedNanos ASC")
    suspend fun thermalTransitions(resultId: String): List<ThermalTransitionEntity>

    @Query("SELECT * FROM power_samples WHERE resultId = :resultId ORDER BY elapsedNanos ASC")
    suspend fun powerSamples(resultId: String): List<PowerSampleEntity>

    @Query("SELECT COUNT(*) FROM iteration_samples WHERE resultId = :resultId")
    suspend fun iterationCount(resultId: String): Int

    @Transaction
    suspend fun deleteAllFor(resultId: String) {
        deleteIterations(resultId)
        deleteThermalSamples(resultId)
        deleteThermalTransitions(resultId)
        deletePowerSamples(resultId)
    }

    @Query("DELETE FROM iteration_samples WHERE resultId = :resultId")
    suspend fun deleteIterations(resultId: String)

    @Query("DELETE FROM thermal_samples WHERE resultId = :resultId")
    suspend fun deleteThermalSamples(resultId: String)

    @Query("DELETE FROM thermal_transitions WHERE resultId = :resultId")
    suspend fun deleteThermalTransitions(resultId: String)

    @Query("DELETE FROM power_samples WHERE resultId = :resultId")
    suspend fun deletePowerSamples(resultId: String)
}

@Dao
interface SpecDao {

    @Upsert suspend fun upsert(spec: SpecEntity)

    @Query("SELECT * FROM specs ORDER BY savedAtEpochMillis DESC")
    fun observeAll(): Flow<List<SpecEntity>>

    @Query("SELECT * FROM specs WHERE specId = :specId")
    suspend fun find(specId: String): SpecEntity?

    @Query("DELETE FROM specs WHERE specId = :specId")
    suspend fun delete(specId: String)
}
