package com.sam.airblock.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.first

data class Settings(
    val radiusNm: Int = 50,
    val intervalSec: Int = 15,
    val logEnabled: Boolean = true,
)

object SettingsStore {
    private val RADIUS = intPreferencesKey("radius_nm")
    private val INTERVAL = intPreferencesKey("interval_sec")
    private val LOG_ENABLED = booleanPreferencesKey("log_enabled")

    suspend fun read(context: Context): Settings {
        val p = context.airblockStore.data.first()
        return Settings(
            radiusNm = p[RADIUS] ?: 50,
            intervalSec = p[INTERVAL] ?: 15,
            logEnabled = p[LOG_ENABLED] ?: true,
        )
    }

    suspend fun write(context: Context, s: Settings) {
        context.airblockStore.edit { p ->
            p[RADIUS] = s.radiusNm
            p[INTERVAL] = s.intervalSec
            p[LOG_ENABLED] = s.logEnabled
        }
    }
}
