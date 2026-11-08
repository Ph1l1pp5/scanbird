# Setup

Scanbird in Android Studio zum Laufen bringen:

1. Repository klonen oder als ZIP laden und entpacken.
2. In Android Studio: File > Open, den Projektordner auswählen.
3. Beim ersten Öffnen ergänzt Android Studio fehlende Gradle-Wrapper-Dateien automatisch (bestätigen). Falls nicht: im Projekterminal `gradle wrapper --gradle-version 8.7` ausführen.
4. Gerät anschließen oder Emulator starten, dann Run drücken.

## Was das Gerüst schon kann

- Blatt über die Systemkamera fotografieren
- daraus ein A4-PDF erzeugen
- im Verlauf speichern und jederzeit wiederfinden
- per Teilen-Knopf als Mail mit Betreff und Standardtext an ein Mailprogramm übergeben — Thunderbird für Android inklusive
- Standardtext unter Einstellungen hinterlegen und ändern

## Was bewusst noch fehlt

- Automatische Kantenerkennung statt der Kamera-Rückgabe (M2)
- Titel-Erkennung (M3), Qualitätsmessung (M4), Volltextsuche (M5)
