# Scanbird

Scan to Mail — Blatt scannen, Mail ist raus.

Scanbird ist eine schlichte Android-Scanner-App für den Arbeitsalltag: A4-Blatt unter die Kamera, scannen, und per Knopfdruck liegt das fertige PDF in einer neuen Mail im eigenen Mailprogramm — zum Beispiel Thunderbird für Android. Keine Registrierung, kein Konto, keine Cloud.

## Was Scanbird tut

- A4-Blätter scannen — Kantenerkennung und Perspektivkorrektur laufen vollständig auf dem Gerät.
- Aus jedem Scan wird ein PDF, mit Verlauf und Vorschau auf dem Handy.
- Der Blatttitel wird gelesen und als Namensvorschlag angeboten; Datum und Uhrzeit bleiben der zweite Vorschlag.
- Ein Knopf öffnet die fertige Mail mit Anhang im Mailprogramm des Nutzers.

## Haltung

- Eine sichtbare Hauptaktion pro Bildschirm. Keine Fachbegriffe.
- 100 Prozent lokal: kein Tracking, keine Telemetrie, keine proprietären Bausteine. Das Gerät verlässt nur, was der Nutzer selbst verschickt.
- Cloud-Backup optional und herstellerneutral: Die App sichert den Scan-Ordner in einen selbst gewählten Ordner einer beliebigen Cloud (Storage Access Framework).

## Status

M1-Gerüst steht: Scan → PDF → Verlauf → Teilen als importierbares Android-Studio-Projekt, siehe SETUP.md. Nächste Schritte: Kantenerkennung lokal (M2), Auto-Titel (M3), Qualitätsmessung (M4) und Volltextsuche (M5). Der Plan steht in der ROADMAP.md.

## Entwicklung und AI-Transparenz

Dieses Projekt wird mit AI-Unterstützung entwickelt. Alle Commits laufen unter dem Konto des Maintainers mit Assisted-by-Vermerk; für Reviews, Issues und Freigaben steht ein Mensch gerade: @Ph1l1pp5. AI-generierter Code ist erlaubt — die Verantwortung trägt der Mensch, nach dem Vorbild der Regeln des Linux-Kernels.

## Dank

An [MakeACopy](https://github.com/egdels/makeacopy) — Vorbild für offene, vollständig lokale Scan-Pipelines.

## Lizenz

MIT-0, siehe LICENSE. Hinweise zu gebündelten Komponenten: NOTICE.md.

## English summary

Scanbird is a plain Android scanner app for the small-office everyday: scan an A4 page to PDF, keep a local history, and open a ready-made mail in your own mail app with one tap. Fully offline, no tracking, no cloud requirement, no proprietary blobs. Work in progress.
