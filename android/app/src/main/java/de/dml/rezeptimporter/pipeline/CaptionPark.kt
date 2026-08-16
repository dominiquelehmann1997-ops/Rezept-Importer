package de.dml.rezeptimporter.pipeline

import android.content.Context
import android.content.SharedPreferences

/**
 * Zwischenlager für die Caption eines geteilten Reel-Links.
 *
 * Hintergrund: Android liefert beim Teilen entweder den *Link* (aus Instagram/TikTok heraus —
 * Caption erreichbar, Video nicht) oder die *Videodatei* (aus der Galerie — Video da, Caption
 * weg). Beides zusammen gibt es in einem Share nicht. Deshalb wird die Caption beim Link-Share
 * geparkt; teilt der Nutzer kurz danach das gespeicherte Video, gehen Caption und Video
 * gemeinsam in denselben LLM-Call.
 *
 * Kurze Gültigkeit, damit eine vergessene Caption nicht Wochen später an ein fremdes Video
 * geheftet wird.
 */
class CaptionPark(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Parked(val caption: String, val sourceUrl: String?)

    fun park(caption: String, sourceUrl: String?) {
        if (caption.isBlank()) return
        prefs.edit()
            .putString(KEY_CAPTION, caption)
            .putString(KEY_URL, sourceUrl)
            .putLong(KEY_TIME, System.currentTimeMillis())
            .apply()
    }

    /** Geparkte Caption, sofern noch frisch. Abgelaufene Einträge werden dabei aufgeräumt. */
    fun peek(now: Long = System.currentTimeMillis()): Parked? {
        val caption = prefs.getString(KEY_CAPTION, null) ?: return null
        if (!isFresh(prefs.getLong(KEY_TIME, 0L), now)) {
            clear()
            return null
        }
        return Parked(caption, prefs.getString(KEY_URL, null))
    }

    fun clear() = prefs.edit().clear().apply()

    companion object {
        const val PREFS_NAME = "caption_park"
        const val KEY_CAPTION = "caption"
        const val KEY_URL = "url"
        const val KEY_TIME = "parked_at"

        /** 30 Minuten — genug, um das Video zu speichern und zu teilen. */
        const val VALIDITY_MS = 30 * 60 * 1000L

        /** Reine Regel, damit die Gültigkeit ohne Android-Context prüfbar bleibt. */
        fun isFresh(parkedAt: Long, now: Long): Boolean = now - parkedAt <= VALIDITY_MS
    }
}
