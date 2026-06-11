package de.dml.rezeptimporter.settings

import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

enum class Provider { GEMINI, HAIKU }

class AppSettings(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "rezept_importer_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var vaultUri: Uri?
        get() = prefs.getString("vault_uri", null)?.let(Uri::parse)
        set(v) = prefs.edit().putString("vault_uri", v?.toString()).apply()

    var provider: Provider
        get() = Provider.valueOf(prefs.getString("provider", Provider.GEMINI.name)!!)
        set(v) = prefs.edit().putString("provider", v.name).apply()

    var geminiKey: String
        get() = prefs.getString("gemini_key", "")!!
        set(v) = prefs.edit().putString("gemini_key", v).apply()

    var anthropicKey: String
        get() = prefs.getString("anthropic_key", "")!!
        set(v) = prefs.edit().putString("anthropic_key", v).apply()
}
