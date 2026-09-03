package de.dml.rezeptimporter.draft

import android.content.Context
import android.content.SharedPreferences
import de.dml.rezeptimporter.domain.RecipeDraft
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Nach dieser Zeit räumt `sweep` einen nie abgenickten Entwurf weg. */
const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

private const val PREFIX = "draft_"

/** Entwurf plus Ablagezeitpunkt — der Zeitstempel treibt `sweep`. */
@Serializable
private data class StoredDraft(val savedAt: Long, val draft: RecipeDraft)

/**
 * Entwürfe je Import-Job. Bewusst ein Schlüssel pro Job: mit dem früheren
 * einzelnen `draft_json` hätten sich zwei gleichzeitige Importe gegenseitig
 * überschrieben, und das fällt erst auf, wenn ein Rezept schon weg ist.
 */
class DraftStore(private val prefs: SharedPreferences) {

    fun put(jobId: String, draft: RecipeDraft) {
        val stored = StoredDraft(System.currentTimeMillis(), draft)
        prefs.edit()
            .putString(PREFIX + jobId, Json.encodeToString(StoredDraft.serializer(), stored))
            .apply()
    }

    /** `null`, wenn es den Job nicht gibt oder der Eintrag unlesbar ist — nie eine Exception. */
    fun get(jobId: String): RecipeDraft? {
        val raw = prefs.getString(PREFIX + jobId, null) ?: return null
        return runCatching { Json.decodeFromString(StoredDraft.serializer(), raw).draft }.getOrNull()
    }

    fun remove(jobId: String) {
        prefs.edit().remove(PREFIX + jobId).apply()
    }

    /**
     * Wirft Entwürfe weg, die älter als [MAX_AGE_MS] sind. Ohne das wächst der
     * Speicher mit jedem Import, den niemand je abnickt. Unlesbare Einträge
     * fliegen gleich mit raus.
     */
    fun sweep(now: Long = System.currentTimeMillis()) {
        val editor = prefs.edit()
        for ((key, value) in prefs.all) {
            if (!key.startsWith(PREFIX)) continue
            val savedAt = runCatching {
                Json.decodeFromString(StoredDraft.serializer(), value as String).savedAt
            }.getOrNull()
            if (savedAt == null || now - savedAt > MAX_AGE_MS) editor.remove(key)
        }
        editor.apply()
    }

    /**
     * Alle lesbaren Entwürfe als Job-Id + Entwurf. Einstieg für die App, wenn die
     * Benachrichtigung nie ankam (verweigert oder abgeschaltet) — sonst wäre ein
     * fertiges Rezept nur noch über `sweep` erreichbar, also gar nicht.
     * Unlesbare Einträge werden übersprungen, gleiche Toleranz wie [get].
     */
    fun pending(): List<Pair<String, RecipeDraft>> =
        prefs.all.keys
            .filter { it.startsWith(PREFIX) }
            .sorted()
            .mapNotNull { key ->
                val jobId = key.removePrefix(PREFIX)
                get(jobId)?.let { jobId to it }
            }

    companion object {
        /**
         * Eigene, unverschlüsselte Ablage — bewusst nicht über `AppSettings`, dessen
         * Konstruktor bei einem Keystore-Fehler die Zugangsdaten verwirft. Das darf
         * ein Hintergrund-Worker nie auslösen können.
         *
         * Dateiname "import_drafts" (Plural), nicht "import_draft": in der alten Datei
         * liegt der Legacy-Schlüssel "draft_json", der mit dem von [sweep] durchkämmten
         * Präfix "draft_" beginnt — dort würde ein Alt-Entwurf stillschweigend mit
         * weggefegt.
         */
        fun of(context: Context): DraftStore =
            DraftStore(context.getSharedPreferences("import_drafts", Context.MODE_PRIVATE))
    }
}
