package de.dml.rezeptimporter.llm

object ExtractionPrompt {
    const val MAX_INPUT_CHARS = 6000
    const val MAX_OUTPUT_TOKENS = 4096

    val INSTRUCTION = """
        Du extrahierst Kochrezepte aus rohem Text (OCR-Ergebnisse, Social-Media-Captions).
        Antworte ausschließlich im vorgegebenen JSON-Format. Sprache: Deutsch.
        Regeln:
        - "amount" immer als String: ganze Zahlen "400", Dezimal "1.5", Brüche "1/2", Bereiche "2-3".
        - "unit" separat: g, kg, ml, l, EL, TL, Stk, Prise, Bund.
        - "freshness" nur wenn eindeutig: "frisch" (Gemüse, Obst, Fleisch, Fisch, Milchprodukte, Kräuter),
          "haltbar" (Trockenvorrat, Konserven, Gewürze, Öl). Im Zweifel weglassen.
        - "steps": die Zubereitungsschritte als einzelne, vollständige Sätze.
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
              "freshness": {"type": "string", "enum": ["frisch", "haltbar"]}
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
