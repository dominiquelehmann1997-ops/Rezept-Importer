package de.dml.rezeptimporter.work

import de.dml.rezeptimporter.dashboard.JobResult
import de.dml.rezeptimporter.domain.RecipeDraft
import de.dml.rezeptimporter.draft.DraftStore
import de.dml.rezeptimporter.draft.FakePrefs
import de.dml.rezeptimporter.notify.ImportNotifier
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportOutcomeTest {

    private val draft = RecipeDraft(name = "Linsen-Dal")

    @Test
    fun `fertig meldet den Namen und legt den Entwurf ab`() {
        assertEquals(
            ImportOutcome.Done(draft),
            outcomeOf(JobResult.Done(draft), expired = false),
        )
    }

    @Test
    fun `fehler reicht die Servermeldung durch`() {
        assertEquals(
            ImportOutcome.Failed("kein Rezept"),
            outcomeOf(JobResult.Failed("kein Rezept"), expired = false),
        )
    }

    @Test
    fun `weggefallener Job ist unklar`() {
        assertEquals(ImportOutcome.Unclear, outcomeOf(JobResult.Gone, expired = false))
    }

    @Test
    fun `pending bleibt offen, solange Zeit ist`() {
        assertEquals(ImportOutcome.KeepWaiting, outcomeOf(JobResult.Pending, expired = false))
    }

    @Test
    fun `pending wird unklar, wenn die Zeit um ist`() {
        assertEquals(ImportOutcome.Unclear, outcomeOf(JobResult.Pending, expired = true))
    }
}

/**
 * Attrappe der Benachrichtigung. Sie hält fest, WAS gemeldet wurde und ob der
 * Entwurf zu diesem Zeitpunkt schon im Store lag — genau die Reihenfolge, die
 * das Design "den Kern" nennt: erst ablegen, dann melden.
 */
private class RecordingNotifier(private val drafts: DraftStore) : ImportNotifier {
    val events = mutableListOf<String>()

    override fun done(jobId: String, name: String) {
        events += "done:$name:abgelegt=${drafts.get(jobId) != null}"
    }

    override fun failed(jobId: String, reason: String) {
        events += "failed:$reason"
    }

    override fun unclear(jobId: String) {
        events += "unclear"
    }
}

/** Liefert die Antworten der Reihe nach; die letzte bleibt danach stehen. */
private fun pollOf(vararg results: JobResult): suspend () -> JobResult {
    var i = 0
    return { results[minOf(i++, results.size - 1)] }
}

/** Uhr, die die Werte der Reihe nach ausgibt; der letzte bleibt danach stehen. */
private fun clockOf(vararg ticks: Long): () -> Long {
    var i = 0
    return { ticks[minOf(i++, ticks.size - 1)] }
}

class TrackImportTest {

    private val draft = RecipeDraft(name = "Linsen-Dal")
    private val prefs = FakePrefs()
    private val drafts = DraftStore(prefs)
    private val notifier = RecordingNotifier(drafts)
    private var sleeps = 0

    private suspend fun track(
        poll: suspend () -> JobResult,
        now: () -> Long = clockOf(0L),
    ) = trackImport(
        jobId = "job-1",
        drafts = drafts,
        notifier = notifier,
        now = now,
        sleep = { sleeps++ },
        poll = poll,
    )

    @Test
    fun `fertig legt erst ab und meldet dann`() = runTest {
        track(pollOf(JobResult.Pending, JobResult.Done(draft)))

        assertEquals("Linsen-Dal", drafts.get("job-1")?.name)
        // "abgelegt=true": beim Melden lag der Entwurf schon. Werden die beiden
        // Zeilen im Worker vertauscht, steht hier false.
        assertEquals(listOf("done:Linsen-Dal:abgelegt=true"), notifier.events)
        assertEquals(1, sleeps)
    }

    @Test
    fun `fehler legt nichts ab und reicht die Servermeldung durch`() = runTest {
        track(pollOf(JobResult.Failed("Keine Rezeptdaten gefunden")))

        assertNull(drafts.get("job-1"))
        assertEquals(listOf("failed:Keine Rezeptdaten gefunden"), notifier.events)
    }

    @Test
    fun `weggefallener Job meldet unklar und legt nichts ab`() = runTest {
        track(pollOf(JobResult.Gone))

        assertNull(drafts.get("job-1"))
        assertEquals(listOf("unclear"), notifier.events)
    }

    @Test
    fun `pending nach Ablauf der Frist meldet unklar`() = runTest {
        // Erster Wert setzt die Frist, der zweite liegt dahinter.
        track(pollOf(JobResult.Pending), now = clockOf(0L, 999_999_999L))

        assertNull(drafts.get("job-1"))
        assertEquals(listOf("unclear"), notifier.events)
        assertEquals(0, sleeps)
    }
}
