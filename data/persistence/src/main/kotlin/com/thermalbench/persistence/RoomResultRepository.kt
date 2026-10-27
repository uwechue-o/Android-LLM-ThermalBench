package com.thermalbench.persistence

import com.thermalbench.domain.model.BenchmarkResult
import com.thermalbench.domain.model.ResultId
import com.thermalbench.domain.model.ResultSummary
import com.thermalbench.domain.model.SpecId
import com.thermalbench.domain.port.ResultCodec
import com.thermalbench.domain.port.ResultRepository
import com.thermalbench.persistence.artifact.ArtifactStore
import com.thermalbench.persistence.db.EntityMappers.toDomain
import com.thermalbench.persistence.db.EntityMappers.toEntity
import com.thermalbench.persistence.db.EntityMappers.toRunEntity
import com.thermalbench.persistence.db.EntityMappers.toSummary
import com.thermalbench.persistence.db.RunDao
import com.thermalbench.persistence.db.SampleDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ResultRepository] over Room plus the artifact store.
 *
 * A finished run is stored twice on purpose: as a canonical JSON document,
 * which is the shareable artifact, and as an index row plus sample rows, which
 * make the history list cheap and let an interrupted run be recovered. Reads
 * prefer the document and fall back to the rows, so a run that never reached
 * its final write is still recoverable rather than lost.
 */
@Singleton
class RoomResultRepository @Inject constructor(
    private val runDao: RunDao,
    private val sampleDao: SampleDao,
    private val artifacts: ArtifactStore,
    private val codec: ResultCodec,
) : ResultRepository {

    override suspend fun save(result: BenchmarkResult): ResultId {
        val fileName = artifacts.fileNameFor(result)
        artifacts.write(fileName, codec.encodeResult(result))
        artifacts.writeSidecar(artifacts.csvNameFor(result.resultId), codec.resultCsv(result))

        runDao.upsert(result.toRunEntity(documentFileName = fileName, complete = true))
        // Rewrite the sample rows so the index matches the document exactly.
        sampleDao.deleteAllFor(result.resultId.value)
        sampleDao.flush(
            iterations = result.iterations.map { it.toEntity(result.resultId.value) },
            thermalSamples = result.thermalTimeline.samples.map { it.toEntity(result.resultId.value) },
            transitions = result.thermalTimeline.transitions.map { it.toEntity(result.resultId.value) },
            power = result.powerSamples.map { it.toEntity(result.resultId.value) },
        )
        return result.resultId
    }

    override fun observeAll(): Flow<List<ResultSummary>> =
        runDao.observeAll().map { rows -> rows.map { it.toSummary() } }

    override suspend fun load(id: ResultId): BenchmarkResult? {
        val row = runDao.find(id.value) ?: return null
        val document = row.documentFileName?.let { artifacts.read(it) }
        return document?.let { runCatching { codec.decodeResult(it) }.getOrNull() }
    }

    override suspend fun loadBySpec(specId: SpecId): List<BenchmarkResult> =
        runDao.findBySpec(specId.value).mapNotNull { load(ResultId(it.resultId)) }

    override suspend fun import(document: String): ResultId {
        val result = codec.decodeResult(document)
        return save(result)
    }

    override suspend fun delete(id: ResultId) {
        val row = runDao.find(id.value)
        row?.documentFileName?.let { artifacts.delete(it) }
        artifacts.delete(artifacts.csvNameFor(id))
        sampleDao.deleteAllFor(id.value)
        runDao.delete(id.value)
    }

    /**
     * Samples recovered from an interrupted run. The engine writes rows as it
     * goes, so a run killed at iteration 47 still yields the 46 that completed.
     */
    suspend fun recoverIterations(id: ResultId) =
        sampleDao.iterations(id.value).map { it.toDomain() }

    suspend fun incompleteRuns(): List<ResultSummary> =
        runDao.findIncomplete().map { it.toSummary() }
}
