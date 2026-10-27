package com.thermalbench.leaderboard

import com.thermalbench.domain.port.LeaderboardClient
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LeaderboardProvidesModule {

    @Provides
    @Singleton
    fun config(): LeaderboardConfig = LeaderboardConfig(LeaderboardConfig.DEFAULT_BASE_URL)

    @Provides
    @Singleton
    fun httpClient(): HttpClient = HttpClient(OkHttp) {
        expectSuccess = true
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                    explicitNulls = false
                },
            )
        }
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
            connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
        }
    }

    private const val REQUEST_TIMEOUT_MILLIS = 60_000L
    private const val CONNECT_TIMEOUT_MILLIS = 15_000L
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LeaderboardModule {

    @Binds abstract fun client(impl: KtorLeaderboardClient): LeaderboardClient
}
