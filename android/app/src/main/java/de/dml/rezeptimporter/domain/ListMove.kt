package de.dml.rezeptimporter.domain

/**
 * Element von [from] nach [to] verschieben; Rest rutscht nach. Indizes außerhalb der
 * Liste (oder from == to) lassen die Liste unverändert — der Aufrufer muss die Ränder
 * nicht selbst prüfen.
 */
fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from == to || from !in indices || to !in indices) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}
