# Phase 3: Rezept aus Video — Transkription + Frame-OCR (Recime-Stil)

> **Status: STUFE 1 UMGESETZT.** Video wird ausgewertet, aber ohne eigenes Backend und ohne
> Video-Download. Der unten skizzierte Weg (Whisper + Frame-OCR) ist damit **hinfällig**:
> Gemini 2.5 Flash nimmt Video direkt entgegen (Bild *und* Ton) und liefert dasselbe
> Rezept-JSON wie bisher. Was bleibt, ist die Frage der Video-*Beschaffung* — dafür siehe
> „Was noch fehlt" unten.

## Was umgesetzt ist (Stufe 1)

- **Quellen-Bündel statt Einzeltext.** `ImportSource` trägt beschriftete Textquellen (Caption,
  Videobeschreibung, OCR) *und* ein optionales Video. Caption und Video gehen in **denselben**
  LLM-Call — ein Rezept ergibt sich regelmäßig erst aus beidem.
- **Merge-Regeln im Prompt:** Vorrang bei Widersprüchen (geschriebene Menge schlägt gesprochene
  Näherung), Deduplizieren von Schritten, Hashtags/„Rezept unten!" ignorieren.
- **YouTube inkl. Shorts:** Beschreibung wird gescrapt; trägt sie das Rezept nicht (< 400 Zeichen),
  geht die Video-URL direkt an Gemini — kein Download nötig.
- **Geteilte Videodatei:** Intent-Filter für `video/*`, Upload über die Gemini Files API. Die URI
  wird über alle Calls eines Imports wiederverwendet (Extraktion, Repair-Retry, Übersetzung),
  sonst würde dasselbe Video dreimal hochgeladen.
- **Zwei-Schritt-Share für Reels:** Beim Teilen eines Instagram-/TikTok-Links wird die Caption
  30 Minuten geparkt. Teilt man danach die gespeicherte Videodatei, werden beide Quellen
  zusammengeführt.
- **Quelllink in der Notiz:** `source` im Frontmatter plus Abschnitt `## Quelle` im Body.

## Was noch fehlt (Stufe 2)

Der bequeme Fall — *aus Instagram heraus einmal teilen, fertig* — geht weiterhin nicht ohne
Backend: Android liefert beim Reel-Share nur den Link, und den Video-Download müsste ein Dienst
übernehmen. Dafür bleibt Option A unten gültig, jetzt aber deutlich kleiner: Der Dienst muss nur
noch **Video beschaffen**, nicht mehr transkribieren — Gemini erledigt STT und Frame-Lesen selbst.
Der `BackendVideoResolver` liefert dann ein `ImportSource` und fügt sich in den bestehenden Router.

---

## Ursprüngliche Skizze (überholt, für den Kontext)

## Problem
Tier 1/2 holen nur **Text**: JSON-LD (Web), Caption (TikTok/Instagram), Beschreibung (YouTube).
Reels/Shorts, bei denen das Rezept **nur gesprochen oder eingeblendet** ist (nichts im
Text), bleiben unzugänglich → aktuell klare Fehlermeldung, kein Import.

## Wie Recime & Co. das lösen
Serverseitige Pipeline (nicht on-device):
1. Geteilten Link ans Backend.
2. **Video laden** — yt-dlp-artige Extraktoren; bei IG/TikTok teils eingeloggte Sessions/Proxies
   gegen Blockade.
3. **Audio → Speech-to-Text** (z.B. Whisper) → gesprochene Schritte/Mengen als Text.
4. **Frame-OCR** — Schlüsselframes greifen, eingeblendete Zutaten/Mengen lesen (ML Kit/Tesseract).
5. Caption + Transkript + Frame-Text → **LLM** strukturiert das Rezept (gleiches Schema wie heute).
Finanziert über Abo (Download + Whisper + OCR + LLM = echte Compute-Kosten).

## Warum nicht on-device (Stand 2026-06)
- Video-Download von IG/TikTok/YouTube: ToS-grau, brüchig, teils Login/Proxy nötig.
- Whisper on-device (Handy): machbar (whisper.cpp/tiny-Modelle), aber langsam + Akku + APK-Größe.
- Frame-Extraktion + OCR pro Frame: CPU/Zeit-intensiv mobil.
→ Realistisch braucht es einen **Backend-Dienst**. Das verlässt das „lokal, kein Server"-Prinzip
der jetzigen App.

## Architektur-Optionen (zu entscheiden, wenn relevant)
- **A — Eigenes Backend** (klein, z.B. Cloud Run/Worker): nimmt Link, macht Download→STT→OCR→LLM,
  gibt RecipeDraft-JSON zurück. App ruft nur eine eigene Endpoint. Volle Kontrolle, Betriebskosten,
  Hosting/Secrets nötig.
- **B — Fertige API** (Transkriptions-/Reel-Extraktions-Dienst): schneller, laufende Kosten,
  Abhängigkeit, Datenschutz prüfen.
- **C — On-device Whisper nur für Audio**, Video-Download trotzdem extern: Hybrid, komplex,
  fraglicher Gewinn.

Empfehlung beim Aufgreifen: **Option A**, minimal — nur YouTube/öffentliche Quellen zuerst
(weniger Blockade als IG), Whisper-tiny + ML-Kit-OCR, hartes Zeit-/Längen-Limit (z.B. ≤3 min Video).

## Offene Fragen
- Rechtslage/ToS Video-Download pro Plattform (IG am heikelsten).
- Kostenrahmen pro Import (STT-Minuten + LLM-Tokens) + Tageslimit.
- Genauigkeit Frame-OCR bei schnellen Schnitten — reichen N gleichverteilte Frames?
- Reuse: Backend liefert denselben `RecipeDraft`, damit Validierung/Vault-Write unverändert bleiben.

## Wiederverwendung aus Phase 1/2
- `RecipeDraft` + Schema + Validator + VaultWriter bleiben 1:1.
- `LinkResolver`-Interface kann ein `BackendVideoResolver` implementieren → fügt sich in den
  bestehenden `RecipeLinkResolver`-Router ein (neue Host-/Fallback-Regel), kein Pipeline-Umbau.
