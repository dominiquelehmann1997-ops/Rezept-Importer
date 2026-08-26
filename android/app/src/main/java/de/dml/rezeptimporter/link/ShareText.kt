package de.dml.rezeptimporter.link

/** true, wenn der geteilte Text nur aus einer einzelnen URL besteht (Reel/TikTok/Web-Link). */
fun isBareUrl(text: String): Boolean =
    Regex("^https?://\\S+$").matches(text.trim())

/**
 * URL aus geteiltem Text, wenn der Text im Kern nur ein Link ist — manche Apps
 * (z. B. Cookidoo) hängen Boilerplate wie "Schau dir dieses Rezept an: …" davor.
 * Lange Captions mit Link bleiben Text: die Caption selbst ist das Rezept.
 */
fun extractShareUrl(text: String): String? {
    val trimmed = text.trim()
    val url = Regex("https?://\\S+").find(trimmed)?.value ?: return null
    val rest = trimmed.replace(url, "").trim()
    return if (rest.length <= 80) url else null
}
