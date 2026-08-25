# astrapi-sync-android

**Status: geplant, noch nicht begonnen.**

Native Android-App für [astrapi-sync](https://github.com/astrapi/astrapi-sync)
(Ordner-Synchronisation über mehrere eigene Geräte). Eigenständiges
Gradle-Projekt, Kotlin — kein geteilter Code mit den Python-Komponenten,
implementiert dasselbe Sync-Protokoll nativ.

Geplanter Stack: OkHttp (HTTP + WebSocket), WorkManager/Foreground-Service
(Hintergrund-Sync), Room (lokaler Datei-Index).

Details zur Gesamtarchitektur: `architecture/abhaengigkeiten.md` und
`projects/sync/` im astrapi-hub-Vault.

Kein Play-Store-Release geplant — reines GitHub-Backup, wie bei den
anderen Client-Repos.
