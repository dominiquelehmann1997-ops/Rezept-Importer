package de.dml.rezeptimporter.settings

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

enum class Provider { GEMINI, HAIKU }

class AppSettings(context: Context) {
    // Nach einer Deinstallation löscht Android den Master-Key im Keystore. Stellt
    // Auto-Backup danach die alten verschlüsselten Prefs wieder her, scheitert das
    // Entschlüsseln des Keysets (AEADBadTagException) und die App crasht beim Start.
    // Dann: kaputte Prefs verwerfen und frisch anlegen — Keys/Vault trägt der
    // Nutzer einmalig neu ein.
    private val prefs: SharedPreferences = try {
        createPrefs(context)
    } catch (e: Exception) {
        context.deleteSharedPreferences(PREFS_NAME)
        createPrefs(context)
    }

    private fun createPrefs(context: Context): SharedPreferences =
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
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

    private companion object {
        const val PREFS_NAME = "rezept_importer_secure"
    }
}
