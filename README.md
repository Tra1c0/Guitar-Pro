# RiffWorkbench

Android-first Offline-Editor für TAB und Partitur. Phase 1 setzt das vollständige Anwendungsgerüst des [Briefings](docs/BRIEFING.md) um: **17 Module, 178 registrierte Merkmale, 32 Dienstverträge und 13 Projektansichten**. Ein kleiner echter Editor ist nutzbar; Desktop-Parität ist das weitere Entwicklungsziel.

## Bereits nutzbar

- Projekte für siebensaitige Drop-A-Gitarre, Bass und Drums erstellen; weitere Gitarren-, Bass-, Drum- und Pianospuren ergänzen, umsortieren und entfernen.
- Saite, Takt und eine von vier Stimmen wählen. Bünde einschließlich 10–24 mit Vorschau/Commit, Notenwerte, Punktierung, Pausen, Palm-Mute und Dead Notes eingeben. Akkordmodus hält die Position.
- Drumgrid auf demselben Beat-/Notenmodell; Kick/HH-Akkorde bleiben beim Entfernen eines einzelnen Elements erhalten.
- Undo/Redo, Tempo, 4/4-/7/8-Takte, gespeicherte Mixerwerte und benannte Schnappschüsse.
- Autosave mit Konflikterkennung, vorherigem Commit und sichtbaren Speicherfehlern; native `.riffproj`-Dateien per Android-Dateiauswahl öffnen/exportieren.
- Deutsche und vorbereitete englische Oberfläche, dunkles/helles Design, Querformat-/Tabletstruktur und externe Tastatur.

**GP-Import/-Export, echter Notensatz und Audio sind noch nicht implementiert.** Die App gibt dafür einen erklärten Status aus. Es gibt keine simulierte Wiedergabe, leeren Erfolgsdateien oder mitgelieferten RSE-/SoundFont-Inhalte. Jede offene Fähigkeit verweist auf ihren Implementierungsvertrag.

## Bauen und prüfen

Voraussetzung: JDK 17, Android SDK Platform 36, Build Tools 36.0.0 und Python 3. `ANDROID_HOME` oder eine lokale, nicht versionierte `local.properties` mit `sdk.dir=…` setzen. Der Wrapper prüft die Gradle-Distribution gegen ihren SHA-256-Hash.

```sh
./gradlew check :app:assembleDebug :app:lintDebug
./gradlew :data:local:generateFixtures
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Die Debug-APK ist ein installierbares Entwicklungsartefakt. Release-Signatur und Veröffentlichung sind nicht eingerichtet. Die CI führt dieselben Prüfungen aus und archiviert APK sowie Berichte. UI-Tests laufen auf Robolectric API 35; das ersetzt keinen Xiaomi-15-Gerätetest.

## Architektur und weitere Arbeit

- [Architektur, Datenmodell und Zustandsflüsse](docs/ARCHITECTURE.md)
- [Vollständige Paritätsmatrix](docs/PARITY.md) und [maschinenlesbares Register](spec/features.json)
- [Dienstverträge und offene Implementierungen](docs/CONTRACTS.md)
- [Machbarkeitstests A/B und Roadmap](docs/SPIKES.md)
- [Testanleitung und Verifikationsstand](docs/VALIDATION.md)
- [Native Datei und Wiederherstellung](docs/PROJECT_FORMAT.md)
- [Quellen, Abhängigkeiten und Rechte](docs/DEPENDENCIES.md)
- [Eigene Fixtures](fixtures/README.md)

Nach einer Änderung an `spec/features.json`: `python3 tools/generate_registry.py`. `verifyArchitecture` verhindert veraltete Ableitungen, unbekannte Routen/Verträge, Kernimporte von Android/Java und zyklische Modulabhängigkeiten. Architekturabdeckung bedeutet, dass jede erfasste Anforderung einen Bereich, Vertrag, Zustand und eine Ausbauetappe hat. Sie bedeutet keine fertig implementierte Desktop-Parität.

Für die eigenen Anwendungsquellen ist noch keine Veröffentlichungslizenz festgelegt. Drittanbieter behalten ihre jeweiligen Lizenzbedingungen; das synthetische Testsignal ist separat als CC0 ausgewiesen.
