# FireWebKiosk (Fire TV / Android TV)

Kleine Kiosk-App für Fire TV:
- Fragt beim ersten Start nach einer URL
- Zeigt die URL im Vollbild (WebView)
- Merkt sich die letzte URL
- Verhindert Standby, solange die App läuft (KEEP_SCREEN_ON)
- URL ändern: auf Fire TV **Play/Pause lange drücken**

## APK automatisch bauen (GitHub Actions)
1. Dieses Repo zu GitHub hochladen
2. Tab **Actions** öffnen → Workflow **Build APK** starten (oder push nach main)
3. Artifact `FireWebKiosk-APK` herunterladen → `app-debug.apk`

## Installation auf Fire TV
- APK auf deinen Server legen
- Auf dem Fire TV mit der Downloader-App die URL zur APK öffnen und installieren
