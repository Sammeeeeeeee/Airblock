package com.sam.airblock.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.first

data class Settings(
    val radiusNm: Int = 50,
    val intervalSec: Int = 15,
)

object SettingsStore {
    private val RADIUS = intPreferencesKey("radius_nm")
    private val INTERVAL = intPreferencesKey("interval_sec")

    suspend fun read(context: Context): Settings {
        val p = context.airblockStore.data.first()
        return Settings(
            radiusNm = p[RADIUS] ?: 50,
            intervalSec = p[INTERVAL] ?: 15,
        )
    }

    suspend fun write(context: Context, s: Settings) {
        context.airblockStore.edit { p ->
            p[RADIUS] = s.radiusNm
            p[INTERVAL] = s.intervalSec
        }
    }
}
