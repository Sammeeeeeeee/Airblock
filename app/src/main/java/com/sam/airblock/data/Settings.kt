package com.sam.airblock.data

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.first

data class Settings(
    val radiusNm: Int = 50,
    val intervalSec: Int = 15,
    /** Fallback location used when the system has no fix (e.g. right after reboot). */
    val homeLat: Double? = null,
    val homeLon: Double? = null,
)

object SettingsStore {
    private val RADIUS = intPreferencesKey("radius_nm")
    private val INTERVAL = intPreferencesKey("interval_sec")
    private val HOME_LAT = doublePreferencesKey("home_lat")
    private val HOME_LON = doublePreferencesKey("home_lon")

    suspend fun read(context: Context): Settings {
        val p = context.airblockStore.data.first()
        return Settings(
            radiusNm = p[RADIUS] ?: 50,
            intervalSec = p[INTERVAL] ?: 15,
            homeLat = p[HOME_LAT],
            homeLon = p[HOME_LON],
        )
    }

    suspend fun write(context: Context, s: Settings) {
        context.airblockStore.edit { p ->
            p[RADIUS] = s.radiusNm
            p[INTERVAL] = s.intervalSec
            if (s.homeLat != null) p[HOME_LAT] = s.homeLat else p.remove(HOME_LAT)
            if (s.homeLon != null) p[HOME_LON] = s.homeLon else p.remove(HOME_LON)
        }
    }
}
