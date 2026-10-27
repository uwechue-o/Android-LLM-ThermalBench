package com.thermalbench.persistence

import com.thermalbench.domain.model.BenchmarkSpec
import com.thermalbench.domain.model.SpecId
import com.thermalbench.domain.port.ResultCodec
import com.thermalbench.domain.port.SpecRepository
import com.thermalbench.persistence.db.SpecDao
import com.thermalbench.persistence.db.SpecEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Specs are stored as canonical JSON so a saved spec keeps its identity exactly. */
@Singleton
class RoomSpecRepository @Inject constructor(
    private val specDao: SpecDao,
    private val codec: ResultCodec,
) : SpecRepository {

    override suspend fun save(spec: BenchmarkSpec) {
        specDao.upsert(
            SpecEntity(
                specId = spec.specId.value,
                label = spec.label,
                document = codec.encodeSpec(spec),
                savedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun load(id: SpecId): BenchmarkSpec? =
        specDao.find(id.value)?.let { runCatching { codec.decodeSpec(it.document) }.getOrNull() }

    override fun observeAll(): Flow<List<BenchmarkSpec>> = specDao.observeAll().map { rows ->
        rows.mapNotNull { runCatching { codec.decodeSpec(it.document) }.getOrNull() }
    }

    suspend fun delete(id: SpecId) = specDao.delete(id.value)
}
