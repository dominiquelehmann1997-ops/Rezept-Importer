package de.dml.rezeptimporter.work

import de.dml.rezeptimporter.dashboard.JobResult
import de.dml.rezeptimporter.domain.RecipeDraft
import org.junit.Assert.assertEquals
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
