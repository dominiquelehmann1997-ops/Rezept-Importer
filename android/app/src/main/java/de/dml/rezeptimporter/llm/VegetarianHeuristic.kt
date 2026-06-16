package de.dml.rezeptimporter.llm

import de.dml.rezeptimporter.domain.RecipeDraft

/**
 * Grobe Heuristik: Ist das Rezept vegetarisch? Prüft die Zutaten-Namen auf Fleisch-
 * und Fischbegriffe. Kein Treffer ⇒ vegetarisch (true). Bewusst nur lange, eindeutige
 * Stämme als Substring — kurze, mehrdeutige (z.B. "hack" in "gehackt", "ham" in
 * "Champignon", "rind" in "Tamarinde") sind absichtlich NICHT gelistet, um vegetarische
 * Rezepte nicht fälschlich als nicht-vegetarisch zu markieren.
 */
object VegetarianHeuristic {
    private val MEAT_FISH = setOf(
        // Geflügel
        "hähnchen", "hühnchen", "hühner", "huhn", "geflügel", "pute", "puten",
        "truthahn", "ente",
        // Rind / Schwein / sonstiges Fleisch
        "rinder", "rindfleisch", "hackfleisch", "tatar", "tartar", "schwein",
        "speck", "bacon", "schinken", "wurst", "würstchen", "cabanossi", "salami",
        "chorizo", "mettwurst", "leberkäse", "leberwurst", "kalb", "lammfleisch",
        "gulasch", "gyros", "döner", "fleisch", "knochen",
        // Fisch & Meeresfrüchte
        "fisch", "thunfisch", "lachs", "garnele", "scampi", "shrimp", "prawn",
        "krabbe", "hummer", "hering", "sardelle", "sardine", "makrele", "forelle",
        "tintenfisch", "anchovi",
        // Tierische Geliermittel
        "gelatine", "gelantine",
        // Englische Fallbacks
        "chicken", "beef", "pork", "sausage", "salmon", "tuna",
    )

    fun isVegetarian(draft: RecipeDraft): Boolean {
        val names = draft.ingredients.joinToString(" ") { it.name }.lowercase()
        return MEAT_FISH.none { names.contains(it) }
    }
}
