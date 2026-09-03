package de.dml.rezeptimporter.draft

import android.content.SharedPreferences

/**
 * Minimale In-Memory-SharedPreferences; nur die von `DraftStore` genutzten Wege.
 * Liegt hier statt in einer Testklasse, weil auch die Worker-Tests einen echten
 * `DraftStore` ohne Android brauchen.
 */
internal class FakePrefs : SharedPreferences {
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

internal class FakeEditor(private val values: MutableMap<String, String>) : SharedPreferences.Editor {
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
