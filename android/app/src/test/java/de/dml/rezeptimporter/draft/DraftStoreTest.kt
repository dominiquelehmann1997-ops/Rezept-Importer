package de.dml.rezeptimporter.draft

import de.dml.rezeptimporter.domain.RecipeDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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

    @Test
    fun `pending listet alle Entwuerfe mit ihrer Job-Id`() {
        store.put("job-1", draft)
        store.put("job-2", draft.copy(name = "Kekse"))

        assertEquals(
            listOf("job-1" to "Linsen-Dal", "job-2" to "Kekse"),
            store.pending().map { (jobId, d) -> jobId to d.name },
        )
    }

    @Test
    fun `pending ueberspringt fremde Schluessel und kaputte Eintraege`() {
        store.put("job-1", draft)
        prefs.values["draft_kaputt"] = "{kein json"
        prefs.values["irgendwas_anderes"] = "egal"

        assertEquals(listOf("job-1"), store.pending().map { it.first })
    }
}
