package com.sam.airblock.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.sam.airblock.BuildConfig

/**
 * Android Keystore-backed storage for the one secret Airblock holds: the
 * FlightAware AeroAPI key.
 *
 * The value is encrypted at rest by a master key that lives in the device's
 * hardware-backed keystore and never leaves it. The key is **never** written to
 * DataStore, the widget state, the activity log, or anywhere it could be read
 * back in plaintext — only this encrypted file holds it.
 *
 * The key is entered by the user in the Tuning screen. For convenience on local
 * builds it may also be seeded once from [BuildConfig.AERO_API_KEY] (populated
 * from a gitignored `local.properties`); leave that empty to keep the key out
 * of the APK binary entirely, which is the more secure option.
 *
 * Note: opening an [EncryptedSharedPreferences] does keystore + file I/O, so
 * every accessor here must be called off the main thread.
 */
object SecureKeyStore {
    private const val FILE = "airblock_secrets"
    private const val K_AERO = "aero_api_key"

    @Volatile
    private var cached: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences =
        cached ?: synchronized(this) {
            cached ?: build(context).also { cached = it }
        }

    private fun build(context: Context): SharedPreferences {
        val master = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context.applicationContext,
            FILE,
            master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * The current AeroAPI key, or null when none is set. On first access, if the
     * store is empty but a build-time seed exists, the seed is migrated in (so a
     * reinstall with a populated local.properties keeps working) and returned.
     */
    fun aeroKey(context: Context): String? {
        val p = prefs(context)
        p.getString(K_AERO, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val seed = BuildConfig.AERO_API_KEY.takeIf { it.isNotBlank() } ?: return null
        p.edit().putString(K_AERO, seed).apply()
        return seed
    }

    fun hasAeroKey(context: Context): Boolean = aeroKey(context) != null

    fun setAeroKey(context: Context, key: String) {
        prefs(context).edit().putString(K_AERO, key.trim()).apply()
    }

    fun clearAeroKey(context: Context) {
        prefs(context).edit().remove(K_AERO).apply()
    }
}
