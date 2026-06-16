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

    /** Standard-Zielordner (relativ zum Vault), in den neue Rezepte geschrieben werden.
     *  Das Dashboard liest /Dashboard, daher ist das der Default. */
    var saveFolder: String
        get() = prefs.getString("save_folder", DEFAULT_FOLDER)!!.ifBlank { DEFAULT_FOLDER }
        set(v) = prefs.edit().putString("save_folder", v.trim().ifBlank { DEFAULT_FOLDER }).apply()

    /** Auswählbare Zielordner. Reihenfolge bleibt erhalten (newline-getrennt gespeichert).
     *  "Dashboard" ist immer dabei und wird als erster geführt. */
    var saveFolders: List<String>
        get() = prefs.getString("save_folders", null)
            ?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.let { withDashboardFirst(it) }
            ?: DEFAULT_FOLDERS
        set(v) = prefs.edit()
            .putString("save_folders", withDashboardFirst(v).joinToString("\n"))
            .apply()

    private fun withDashboardFirst(folders: List<String>): List<String> {
        val cleaned = folders.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return (listOf(DEFAULT_FOLDER) + cleaned.filter { it != DEFAULT_FOLDER })
    }

    /** null = System folgen; sonst expliziter Dark-Mode-Schalter aus den Einstellungen. */
    var darkMode: Boolean?
        get() = if (prefs.contains("dark_mode")) prefs.getBoolean("dark_mode", false) else null
        set(v) = prefs.edit().apply {
            if (v == null) remove("dark_mode") else putBoolean("dark_mode", v)
        }.apply()

    private companion object {
        const val PREFS_NAME = "rezept_importer_secure"
        const val DEFAULT_FOLDER = "Dashboard"
        val DEFAULT_FOLDERS = listOf("Dashboard", "Süßspeisen", "Snacks")
    }
}
