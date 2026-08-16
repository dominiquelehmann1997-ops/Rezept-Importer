package de.dml.rezeptimporter.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ListMoveTest {
    private val steps = listOf("a", "b", "c", "d")

    @Test
    fun movesItemUp() {
        assertEquals(listOf("a", "c", "b", "d"), steps.moved(2, 1))
    }

    @Test
    fun movesItemDown() {
        assertEquals(listOf("b", "a", "c", "d"), steps.moved(0, 1))
    }

    @Test
    fun movesAcrossSeveralPositions() {
        assertEquals(listOf("b", "c", "d", "a"), steps.moved(0, 3))
        assertEquals(listOf("d", "a", "b", "c"), steps.moved(3, 0))
    }

    @Test
    fun ignoresOutOfBoundsAndNoOpMoves() {
        assertEquals(steps, steps.moved(0, 0))
        assertEquals(steps, steps.moved(0, -1))     // "hoch" beim ersten Element
        assertEquals(steps, steps.moved(3, 4))      // "runter" beim letzten
        assertEquals(steps, steps.moved(-1, 2))
        assertEquals(emptyList<String>(), emptyList<String>().moved(0, 1))
    }
}
