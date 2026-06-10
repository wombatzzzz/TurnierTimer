# TurnierTimer

Android-App zur automatischen Spielzeitverwaltung bei Turnieren.

## Funktionen

- **Automatischer Spielplan** – Startzeit und Spielanzahl konfigurierbar, der Timer läuft im Hintergrund weiter, auch wenn die App minimiert ist
- **Jingle-Unterstützung** – drei konfigurierbare Audiodateien: Start-Jingle, Letzte-Minute-Jingle und Schluss-Jingle (eigene Dateien wählbar)
- **Lautstärkeregler** – separate Regler für Sprachansage und Jingles (0–100 %)
- **Benachrichtigungen** – zeigen den aktuellen Spielstand und den nächsten Jingle-Zeitpunkt an
- **Persistente URI-Berechtigungen** – gewählte Audiodateien bleiben auch nach einem Neustart verfügbar

## Voraussetzungen

- Android 8.0 (API 26) oder höher
- Android Studio zum Bauen und Installieren

## Bauen & Installieren

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Lizenz

Privates Projekt.
