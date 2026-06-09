package com.sam.airblock.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/** Per-network refresh behaviour. */
enum class NetMode { NORMAL, SLOW, OFF;
    companion object {
        fun from(value: String?) = entries.firstOrNull { it.name == value } ?: NORMAL
    }
}

data class Settings(
    val radiusNm: Int = 50,
    val intervalSec: Int = 15,
    val logEnabled: Boolean = false,
    val wifiMode: NetMode = NetMode.NORMAL,
    val dataMode: NetMode = NetMode.NORMAL,
)

object SettingsStore {
    private val RADIUS = intPreferencesKey("radius_nm")
    private val INTERVAL = intPreferencesKey("interval_sec")
    private val LOG_ENABLED = booleanPreferencesKey("log_enabled")
    private val WIFI_MODE = stringPreferencesKey("wifi_mode")
    private val DATA_MODE = stringPreferencesKey("data_mode")

    suspend fun read(context: Context): Settings {
        val p = context.airblockStore.data.first()
        return Settings(
            radiusNm = p[RADIUS] ?: 50,
            intervalSec = p[INTERVAL] ?: 15,
            logEnabled = p[LOG_ENABLED] ?: false,
            wifiMode = NetMode.from(p[WIFI_MODE]),
            dataMode = NetMode.from(p[DATA_MODE]),
        )
    }

    suspend fun write(context: Context, s: Settings) {
        context.airblockStore.edit { p ->
            p[RADIUS] = s.radiusNm
            p[INTERVAL] = s.intervalSec
            p[LOG_ENABLED] = s.logEnabled
            p[WIFI_MODE] = s.wifiMode.name
            p[DATA_MODE] = s.dataMode.name
        }
    }
}
