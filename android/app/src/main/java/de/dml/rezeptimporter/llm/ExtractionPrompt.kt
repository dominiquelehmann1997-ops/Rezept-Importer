package de.dml.rezeptimporter.llm

object ExtractionPrompt {
    const val MAX_INPUT_CHARS = 6000
    const val MAX_OUTPUT_TOKENS = 4096

    val INSTRUCTION = """
        Du extrahierst Kochrezepte aus rohem Text (OCR-Ergebnisse, Social-Media-Captions).
        Antworte ausschließlich im vorgegebenen JSON-Format. Sprache: Deutsch.
        Regeln:
        - ÜBERSETZEN: Ist das Rezept nicht auf Deutsch (z.B. Englisch), übersetze ALLES
          ins Deutsche — "name", Zutaten-Namen inkl. "section", "steps", "tags",
          "nutrition.basis".
          Nichts in der Ausgabe darf in einer anderen Sprache bleiben.
        - METRISCH UMRECHNEN: Wandle US-/imperiale Mengen in europäische metrische Einheiten um.
          Runde auf küchentaugliche Werte. Umrechnungen:
            • lb/lbs/pound → g (1 lb ≈ 454 g), oz/ounce (Gewicht) → g (1 oz ≈ 28 g)
            • fl oz → ml (1 fl oz ≈ 30 ml), cup → ml bei Flüssigem (1 cup ≈ 240 ml)
            • stick Butter → g (1 stick ≈ 113 g)
            • tsp/teaspoon → TL, tbsp/Tbsp/tablespoon → EL
            • °F → °C (auch in "steps"!): °C = (°F − 32) × 5/9, auf 5er gerundet (z.B. 400°F → 200°C)
            • inch → cm (1 inch ≈ 2,5 cm)
          "to taste" → weglassen (kein amount), "a little"/"a pinch" → unit "Prise" ohne amount.
        - "amount" immer als String: ganze Zahlen "400", Dezimal "1.5", Brüche "1/2", Bereiche "2-3".
        - "unit" separat: g, kg, ml, l, EL, TL, Stk, Prise, Bund.
        - ZUTATEN-GRUPPEN: Jede Zeile in der Zutatenliste, die KEINE Zutat mit Menge ist, sondern
          einen Teil des Gerichts benennt, ist eine Gruppenüberschrift — mit ODER ohne
          Doppelpunkt, ein einzelnes Wort genügt. Beispiele: "Für die Nuggets:", "Für die Soße:",
          "Dip", "Sauce", "Teig", "Topping", "Marinade", "Füllung", "Zum Servieren".
          Setze bei JEDER Zutat unterhalb einer solchen Zeile "section" auf diese Überschrift
          (ohne Doppelpunkt, Schreibweise der Quelle). Die Überschrift selbst NIEMALS als Zutat
          ausgeben. Zutaten oberhalb der ersten Überschrift bleiben ohne "section".
          Beispiel:
            "300g Hähnchenbrust"  ⇒ {"name":"Hähnchenbrust","amount":"300","unit":"g"}
            "Dip"                 ⇒ Überschrift, KEINE Zutat
            "150g Skyr"           ⇒ {"name":"Skyr","amount":"150","unit":"g","section":"Dip"}
          Keine Gruppen sind Zeilen wie "Zutaten", "Zutaten für 2 Personen", "Rezept",
          "Zubereitung". Reihenfolge der Zutaten nicht verändern. Fehlen solche Zeilen,
          "section" überall weglassen — keine Gruppen erfinden.
        - "freshness" nur wenn eindeutig: "frisch" (Gemüse, Obst, Fleisch, Fisch, Milchprodukte, Kräuter),
          "haltbar" (Trockenvorrat, Konserven, Gewürze, Öl). Im Zweifel weglassen.
        - PORTIONEN: Enthält der Text Zutatenmengen für mehrere Personenzahlen (z.B. Spalten "2P",
          "3P", "4P" oder "2 Personen / 4 Personen"), verwende ausschließlich die Mengen der
          kleinsten angegebenen Portion (z.B. 2P) und setze "servings" auf diese Zahl.
          Nimm niemals Mengen aus verschiedenen Portionsspalten.
        - "steps": Bei mehrspaltig gescannten Rezeptkarten (z.B. HelloFresh 3×2-Raster) liefert
          der OCR-Text die Schritte spaltenweise gruppiert: erst alle Schritte der linken Spalte
          (z.B. Schritt 1 und 4), dann der mittleren (2 und 5), dann der rechten (3 und 6).
          Erkenne jeden Schritt am Titel (kurze Imperativphrase, z.B. „Kartoffeln vorbereiten").
          Der zugehörige Beschreibungstext folgt direkt darunter bis zum nächsten Titel.
          Bringe die Schritte in die logisch richtige Kochreihenfolge; sind Schrittnummern im Text
          vorhanden, nutze sie zur Sortierung. Schrittnummern nicht in den Ausgabetext übernehmen.
          Jeden Schritt als vollständige Sätze ausgeben.
        - "nutrition" nur befüllen, wenn Nährwerte im Text explizit genannt sind: kcal (Energie),
          protein/carbs/fat in Gramm (nur Zahl, ohne Einheit). "basis" = Bezug wie im Text,
          z.B. "pro Portion" oder "pro 100g". Nährwerte niemals schätzen oder berechnen.
        - Unbekannte Felder weglassen. Nichts erfinden.
    """.trimIndent()

    /** JSON-Schema (Draft-Stil) für Anthropic-Tool-Use. */
    const val SCHEMA_JSON = """
    {
      "type": "object",
      "properties": {
        "name": {"type": "string"},
        "tags": {"type": "array", "items": {"type": "string"}},
        "servings": {"type": "integer"},
        "prepMinutes": {"type": "integer"},
        "cookMinutes": {"type": "integer"},
        "ingredients": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "name": {"type": "string"},
              "amount": {"type": "string"},
              "unit": {"type": "string"},
              "freshness": {"type": "string", "enum": ["frisch", "haltbar"]},
              "section": {"type": "string"}
            },
            "required": ["name"],
            "additionalProperties": false
          }
        },
        "steps": {"type": "array", "items": {"type": "string"}},
        "nutrition": {
          "type": "object",
          "properties": {
            "basis": {"type": "string"},
            "kcal": {"type": "integer"},
            "protein": {"type": "number"},
            "carbs": {"type": "number"},
            "fat": {"type": "number"}
          },
          "additionalProperties": false
        }
      },
      "required": ["name", "ingredients", "steps"],
      "additionalProperties": false
    }
    """

    fun userMessage(rawText: String, repairHint: String?): String {
        val capped = rawText.take(MAX_INPUT_CHARS)
        val repair = repairHint?.let {
            "\n\nDein letzter Versuch war ungültig. Fehler: $it\nKorrigiere genau diese Punkte."
        } ?: ""
        return "Extrahiere das Rezept aus folgendem Text:\n\n$capped$repair"
    }
}
