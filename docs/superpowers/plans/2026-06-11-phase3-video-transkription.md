# Phase 3 (später): Rezept aus Video — Transkription + Frame-OCR (Recime-Stil)

> **Status: ZURÜCKGESTELLT.** Bewusst nicht in Phase 1/2. Hier nur die Architektur-Skizze,
> damit der Weg dokumentiert ist, wenn er später kommt.

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
