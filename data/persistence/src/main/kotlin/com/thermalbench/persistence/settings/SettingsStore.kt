package com.thermalbench.persistence.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "thermalbench_settings")

/** Operator settings and the leaderboard consent flag. */
data class Settings(
    /**
     * Live charts are the single largest source of observer effect in the app
     * path (GUIDE.md §5.3), so they can be switched off entirely.
     */
    val liveChartsEnabled: Boolean = true,
    val keepScreenOnDuringRun: Boolean = false,
    /** Consent is off until the operator turns it on, per submission. */
    val leaderboardConsentGiven: Boolean = false,
    val leaderboardIncludeDeviceModel: Boolean = true,
    val leaderboardIncludeRawIterations: Boolean = false,
    val consentEpochMillis: Long = 0L,
)

@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            liveChartsEnabled = prefs[LIVE_CHARTS] ?: true,
            keepScreenOnDuringRun = prefs[KEEP_SCREEN_ON] ?: false,
            leaderboardConsentGiven = prefs[CONSENT] ?: false,
            leaderboardIncludeDeviceModel = prefs[CONSENT_DEVICE_MODEL] ?: true,
            leaderboardIncludeRawIterations = prefs[CONSENT_RAW_ITERATIONS] ?: false,
            consentEpochMillis = prefs[CONSENT_AT] ?: 0L,
        )
    }

    suspend fun setLiveCharts(enabled: Boolean) = context.dataStore.edit { it[LIVE_CHARTS] = enabled }

    suspend fun setKeepScreenOn(enabled: Boolean) = context.dataStore.edit { it[KEEP_SCREEN_ON] = enabled }

    suspend fun setConsent(given: Boolean, includeDeviceModel: Boolean, includeRawIterations: Boolean) {
        context.dataStore.edit {
            it[CONSENT] = given
            it[CONSENT_DEVICE_MODEL] = includeDeviceModel
            it[CONSENT_RAW_ITERATIONS] = includeRawIterations
            it[CONSENT_AT] = if (given) System.currentTimeMillis() else 0L
        }
    }

    private companion object {
        val LIVE_CHARTS = booleanPreferencesKey("live_charts_enabled")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val CONSENT = booleanPreferencesKey("leaderboard_consent")
        val CONSENT_DEVICE_MODEL = booleanPreferencesKey("leaderboard_include_device_model")
        val CONSENT_RAW_ITERATIONS = booleanPreferencesKey("leaderboard_include_raw_iterations")
        val CONSENT_AT = longPreferencesKey("leaderboard_consent_at")
    }
}
