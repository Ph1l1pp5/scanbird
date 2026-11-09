# Roadmap

## Meilensteine

- M0 — Mail-Anbindung testen: Anhang und Standardtext im Ziel-Mailprogramm (Thunderbird für Android) auf dem Zielsystem verifiziert; Fallback über die Zwischenablage. Abgeschlossen am 08.11.2026 (Testberichte in Issue 1).
- M1 — Prototyp: Scan → PDF → Verlauf → Share als lauffähiges Android-Studio-Projekt (Kotlin, Jetpack Compose, Room, FileProvider). Abgeschlossen am 08.11.2026 (Issue 2).
- M2 — Scan-Pipeline: Kantenerkennung und Perspektivkorrektur lokal, ohne ML Kit und ohne proprietäre Blobs.
- M3 — Auto-Titel: Titelzeile des Blatts per lokaler OCR erkennen und als Namensvorschlag anbieten; Fallback Datum/Uhrzeit.
- M4 — Qualität: Testkorpus mit echten Praxisblättern; Vergleich gegen eine schwarze Referenz (nur Messung, kein Übernehmen fremden Codes).

## Später

- Cloud-Backup über Storage Access Framework (beliebiger Anbieter, ein wählbarer Zielordner).
- Mehrseiten-Scans, wenn die typische Berichtslänge es verlangt.
- Distribution über GitHub-Releases; Aufnahme in offene Stores.
