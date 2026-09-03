package de.dml.rezeptimporter.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class AppSettings(context: Context) {
    // Nach einer Deinstallation löscht Android den Master-Key im Keystore. Stellt
    // Auto-Backup danach die alten verschlüsselten Prefs wieder her, scheitert das
    // Entschlüsseln des Keysets (AEADBadTagException) und die App crasht beim Start.
    // Dann: kaputte Prefs verwerfen und frisch anlegen — Adresse/Token trägt der
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

    /** Basis-URL des Dashboards, ohne Slash am Ende. Zuhause die LAN-Adresse,
     *  unterwegs der Cloudflare-Hostname. */
    var dashboardUrl: String
        get() = prefs.getString("dashboard_url", "")!!
        set(v) = prefs.edit().putString("dashboard_url", v.trim().trimEnd('/')).apply()

    /** Wert von RECIPE_IMPORT_TOKEN aus web/.env. */
    var importToken: String
        get() = prefs.getString("import_token", "")!!
        set(v) = prefs.edit().putString("import_token", v.trim()).apply()

    /** Cloudflare-Access-Service-Token; leer lassen, wenn direkt ins LAN gefunkt wird. */
    var cfClientId: String
        get() = prefs.getString("cf_client_id", "")!!
        set(v) = prefs.edit().putString("cf_client_id", v.trim()).apply()

    var cfClientSecret: String
        get() = prefs.getString("cf_client_secret", "")!!
        set(v) = prefs.edit().putString("cf_client_secret", v.trim()).apply()

    /** null = System folgen; sonst expliziter Dark-Mode-Schalter aus den Einstellungen. */
    var darkMode: Boolean?
        get() = if (prefs.contains("dark_mode")) prefs.getBoolean("dark_mode", false) else null
        set(v) = prefs.edit().apply {
            if (v == null) remove("dark_mode") else putBoolean("dark_mode", v)
        }.apply()

    private companion object {
        const val PREFS_NAME = "rezept_importer_secure"
    }
}
