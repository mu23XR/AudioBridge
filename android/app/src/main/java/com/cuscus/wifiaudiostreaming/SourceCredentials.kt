package com.cuscus.wifiaudiostreaming

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Per-source keys have a separate Keystore alias and are never reset on open failure. */
internal object SourceCredentials {
    @Volatile private var cached: SharedPreferences? = null
    @Synchronized private fun prefs(context: Context): SharedPreferences {
        cached?.let { return it }
        try {
            val master = MasterKey.Builder(context.applicationContext, "wfas_receiver_master")
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(context.applicationContext, "wfas_receiver_credentials", master,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM).also { cached = it }
        } catch (failure: Exception) {
            throw IllegalStateException("Receiver credential storage unavailable; existing data preserved", failure)
        }
    }
    fun read(context: Context, id: String): String? = prefs(context).getString(id, null)
    fun save(context: Context, id: String, key: String) {
        require(key.isNotBlank())
        check(prefs(context).edit().putString(id, key).commit()) { "Receiver credential write failed" }
    }
    fun remove(context: Context, id: String) {
        check(prefs(context).edit().remove(id).commit()) { "Receiver credential removal failed" }
    }
}
