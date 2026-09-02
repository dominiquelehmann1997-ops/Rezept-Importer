package de.dml.rezeptimporter.draft

import android.content.SharedPreferences
import de.dml.rezeptimporter.domain.RecipeDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Minimale In-Memory-SharedPreferences; nur die vier von DraftStore genutzten Wege. */
private class FakePrefs : SharedPreferences {
    val values = mutableMapOf<String, String>()

    override fun getString(key: String?, defValue: String?): String? = values[key] ?: defValue
    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun contains(key: String?): Boolean = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor(values)

    override fun getStringSet(k: String?, d: MutableSet<String>?) = d
    override fun getInt(k: String?, d: Int) = d
    override fun getLong(k: String?, d: Long) = d
    override fun getFloat(k: String?, d: Float) = d
    override fun getBoolean(k: String?, d: Boolean) = d
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}

private class FakeEditor(private val values: MutableMap<String, String>) : SharedPreferences.Editor {
    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
        if (key != null && value != null) values[key] = value
        return this
    }
    override fun remove(key: String?): SharedPreferences.Editor {
        values.remove(key); return this
    }
    override fun clear(): SharedPreferences.Editor { values.clear(); return this }
    override fun apply() {}
    override fun commit(): Boolean = true

    override fun putStringSet(k: String?, v: MutableSet<String>?) = this
    override fun putInt(k: String?, v: Int) = this
    override fun putLong(k: String?, v: Long) = this
    override fun putFloat(k: String?, v: Float) = this
    override fun putBoolean(k: String?, v: Boolean) = this
}

class DraftStoreTest {

    private val prefs = FakePrefs()
    private val store = DraftStore(prefs)
    private val draft = RecipeDraft(name = "Linsen-Dal")

    @Test
    fun `legt ab und liest zurueck`() {
        store.put("job-1", draft)
        assertEquals("Linsen-Dal", store.get("job-1")?.name)
    }

    @Test
    fun `zwei Jobs ueberschreiben sich nicht`() {
        store.put("job-1", draft)
        store.put("job-2", draft.copy(name = "Kekse"))

        assertEquals("Linsen-Dal", store.get("job-1")?.name)
        assertEquals("Kekse", store.get("job-2")?.name)
    }

    @Test
    fun `kennt unbekannte Jobs nicht`() {
        assertNull(store.get("gibtsnicht"))
    }

    @Test
    fun `remove trifft nur den einen Eintrag`() {
        store.put("job-1", draft)
        store.put("job-2", draft)
        store.remove("job-1")

        assertNull(store.get("job-1"))
        assertEquals("Linsen-Dal", store.get("job-2")?.name)
    }

    @Test
    fun `sweep entfernt Alte und laesst Junge stehen`() {
        store.put("alt", draft)
        store.put("jung", draft)
        // "alt" kuenstlich altern lassen: Eintrag mit altem Zeitstempel neu schreiben.
        prefs.values["draft_alt"] = prefs.values["draft_alt"]!!.replace(
            Regex("\"savedAt\":\\d+"), "\"savedAt\":1",
        )

        store.sweep(now = MAX_AGE_MS + 2)

        assertNull(store.get("alt"))
        assertEquals("Linsen-Dal", store.get("jung")?.name)
    }

    @Test
    fun `kaputter Eintrag liefert null statt zu werfen`() {
        prefs.values["draft_kaputt"] = "{kein json"
        assertNull(store.get("kaputt"))
    }
}
