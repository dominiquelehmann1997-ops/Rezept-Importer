package de.dml.rezeptimporter.llm

import de.dml.rezeptimporter.domain.ImportSource

/** Kurzform für Tests, die nur eine einzelne Textquelle brauchen. */
fun src(text: String): ImportSource =
    ImportSource.ofText(ImportSource.LABEL_SHARED_TEXT, text)
