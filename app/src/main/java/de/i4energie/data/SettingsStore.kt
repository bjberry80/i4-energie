package de.i4energie.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class HaSettings(val baseUrl: String, val token: String) {
    val isComplete: Boolean get() = baseUrl.isNotBlank() && token.isNotBlank()
}

/** HA-URL und Token, verschlüsselt gespeichert. Der Token wird nie geloggt. */
@Suppress("DEPRECATION")
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences = open(context.applicationContext)

    fun load(): HaSettings = HaSettings(
        baseUrl = prefs.getString(KEY_URL, "").orEmpty(),
        token = prefs.getString(KEY_TOKEN, "").orEmpty(),
    )

    fun save(settings: HaSettings) = prefs.edit {
        putString(KEY_URL, settings.baseUrl.trim())
        putString(KEY_TOKEN, settings.token.trim())
    }

    private companion object {
        const val FILE = "ha_settings"
        const val KEY_URL = "base_url"
        const val KEY_TOKEN = "token"

        fun open(context: Context): SharedPreferences = try {
            create(context)
        } catch (_: Exception) {
            // Keystore-Schlüssel kaputt (z. B. nach Backup-Restore): neu anlegen.
            context.deleteSharedPreferences(FILE)
            create(context)
        }

        fun create(context: Context): SharedPreferences {
            val key = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                FILE,
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
