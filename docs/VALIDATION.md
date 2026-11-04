# Verifikation und Abnahme

Die vollständigen Testergebnisse stehen nach dem Prüflauf in `docs/VERIFICATION.json`; sie werden aus tatsächlichen JUnit-XML-Berichten erzeugt. Die CI archiviert die detaillierten Gradle-/Lint-/JUnit-Berichte. Ein erfolgreicher Build ist keine Geräte-, Hör- oder Desktop-Abnahme.

Geprüfter Stand vom 4. November 2026: **63 unterschiedliche Tests bestanden, 74 Ausführungen einschließlich Debug-/Release-Varianten; Build erfolgreich; Android Lint 0 Fehler und 18 Hinweise.** Die Hinweise betreffen verfügbare neuere Abhängigkeiten und die Ressourcen-Konfiguration. Bibliothek, Handy-/Tablet-Editor, Drumgrid, Funktionssuche und helles Design wurden anhand der erzeugten Screenshots visuell geprüft.

## Automatisierte Prüfung

```sh
./gradlew check :app:assembleDebug :app:lintDebug
./gradlew :data:local:generateFixtures
python3 tools/collect_validation.py --output build/verification.json
```

- TimeModelTest: rationale Zeit/Überlauf, verschachtelte Tuplets, Punktierung, Drop-A-Oktaven/Teil-Capo, unabhängige Bends, stabile Wire-Tags und Effektparameter.
- EditorSessionTest: vier Stimmen, Akkorde, Commit/Undo/Redo, Noten-/Taktgrenzen, freie Entwürfe, Drumakkorde, Spuren, Mixer, Metadaten-/Linkerhalt, leere Löschung, gestenweise Transaktionen und spätere Tempoänderungen.
- ProjectStorageTest: Neustart, konkurrierende Revision, atomare Sichtbarkeit, Recovery und Hinweis, kein Downgrade neuerer Schemata, beschädigte Projekte, Snapshots, Einstellungen und Asset-Integrität.
- NativeArchiveTest: Originalbytes/Parameter, eigene native Datei, fehlende Assets, Pfadüberschreitung, Prüfsummen, neuere Versionen, Tiefenlimits und komprimierte Bomben.
- AutosaveTest: zusammengefasste schnelle Eingaben, fehlgeschlagener Save/Retry/Flush, weitere Änderungen nach Fehler, Snapshot-Rennen und mehrstellige Vorschau.
- ApplicationFlowTest: tatsächliche MainActivity auf Robolectric API 35, Projekt anlegen, Bund 12, Undo/Redo/Persistenz, alle 13 Bereiche, Statussuche, Drumgrid, gespeicherte Einstellungen und Tablet-Navigation. Debug und Release verwenden denselben echten Activity-Einstieg.
- Adaptertests: keine vorgetäuschte Audio-/GP-Exportfähigkeit; ZIP-Signatur ist kein Beleg für GP8-Treue.
- verifyArchitecture: Modulrichtung, Zyklen, portabler Kern, vollständige Registerdimensionen, bekannte Routen/Verträge und aktuelle generierte Parität.

Screenshots entstehen am Ende eigener Bedienprüfungen mit Robolectric Native Graphics durch Zeichnen der tatsächlichen Activity in ein Bitmap. Das umgeht die dort nicht zuverlässige PixelCopy-/WindowCapture-Redraw-Synchronisierung. Dateien liegen in `app/build/reports/screenshots/`. Handy 411×891 dp, Tablet 1280×800 dp; keine Behauptung eines echten Gerätedisplays. Bibliothek, Editor, Drumgrid, Status, Tablet und helles Design werden geprüft. Die Simulation deckt keine Audio-/USB-/Bluetooth- oder Providerhardware ab.

Android Lint muss ohne Fehler bestehen. Hinweise auf neuere Bibliotheksversionen sind bei der bewusst gepinnten Toolchain erwartbar; die Aktualisierung für alphaTab ist ein eigener geprüfter Schritt.

## Manuelle Phase-1-Abnahme auf Android

1. Debug-APK installieren; Bibliothek ohne Netzwerk öffnen. Projekt mit Gitarre/Bass/Drums anlegen.
2. Im Hochformat tiefe A-Saite wählen, 150 BPM, 1/16 setzen, PM aktivieren und Bünde 0/12 mit ausdrücklichem Commit eingeben. Dead Note setzen. Auto-Advance und Akkordmodus prüfen.
3. Vier Stimmen separat bearbeiten; 7/8-Takt ergänzen; Rhythmusfehler zeigen lassen. Fehler mit Undo/Redo korrigieren.
4. Kick und HH auf denselben Beat setzen, nur Kick entfernen und Undo ausführen. Mixer-Slider verändern; genau ein Undo pro Slidergeste.
5. Benannten Snapshot erstellen, weiterarbeiten und Snapshot wiederherstellen; Restore rückgängig machen.
6. Auf „Lokal gespeichert“ warten, App beenden und Projekt erneut öffnen. Bei provoziertem Speicherfehler muss der ungespeicherte Stand sichtbar bleiben und Retry möglich sein.
7. Native Datei über SAF speichern und als neues Projekt öffnen; mit mindestens zwei Dokumentprovidern testen. Der native Import dupliziert die Projekt-ID, keine internen Notenreferenzen.
8. Querformat/Tablet, externe Tastatur und Screenreader prüfen. Kleine Partiturelemente haben Alternativen über Saitenwahl/Pfeile; Long Press ist keine Pflichtaktion.

## Weitere noch offene Abnahmen

GP8-Reopen/Reimport, Cubase-Stem-/MIDI-Übergabe, echte Klangartikulationen, Ein-/Ausgaberouting, 30-min-Stresstest, Touch→Paint/Preview-p50/p95, 60-fps-Scrollen, Bluetooth und Thermik. Voraussetzungen und konkrete Pass/Fail-Folgen stehen in SPIKES.md. Kein Wert wird als gemessene Leistung ausgegeben.
