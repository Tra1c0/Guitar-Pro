# Verifikation und Abnahme

Die Testergebnisse stehen in `docs/VERIFICATION.json`; sie werden von `tools/collect_validation.py` aus den tatsächlichen JUnit-XML-Berichten eines vollständigen CI-Laufs erzeugt. Die CI archiviert die Gradle-, Lint- und JUnit-Berichte als Artefakt `verification-reports` und die Debug-APK als `riffworkbench-debug-apk`. Ein erfolgreicher Build ist keine Geräte-, Hör- oder Desktop-Abnahme.

Geprüfter Stand: **227 unterschiedliche Tests bestanden, 242 Ausführungen einschließlich Debug-/Release-Varianten; Build, Architekturprüfung und Android Lint ohne Fehler** (CI-Lauf 10 für Commit `24c72b5`, 4. November 2026). Der Quell-Fingerabdruck in `docs/VERIFICATION.json` entspricht dem Kotlin-Stand dieses Commits; seither hat die QA-Kampagne (Zweig `ccr-0b650dff-jnhups-qa`, siehe [QA_JOURNAL](QA_JOURNAL.md)) Code und Tests deutlich erweitert; maßgeblich ist dort der jeweils letzte grüne CI-Lauf. `VERIFICATION.json` ist bis zur nächsten Neuerzeugung veraltet.

## Automatisierte Prüfung

```sh
./gradlew check :app:assembleDebug :app:lintDebug
./gradlew :data:local:generateFixtures
python3 tools/collect_validation.py --output build/verification.json
```

Die rein portablen Module (`core:*`, `adapter:*`, `data:local`) lassen sich auch ohne Android-SDK mit einem reinen JVM-Build testen; die Android-Module prüft die CI.

| Bereich | Tests | Was sie belegen |
|---|---|---|
| Modell | TimeModelTest | rationale Zeit/Überlauf, verschachtelte Tuplets, Punktierung, Drop-A-Oktaven/Teil-Capo, unabhängige Bends, stabile Wire-Tags |
| Editor | EditorSessionTest, EditorExtensionsTest | vier Stimmen, Akkorde, Commit/Undo/Redo, Artikulationen, Haltebögen, Ripple, Takte einfügen/löschen/duplizieren/einfügen, Wiederholungen, Spuren duplizieren, Instrumentwechsel mit Tonhöhe/Griff, Transposition, Akkordbibliothek, vom Basisstand gelöste Vorschläge |
| Wiedergabe | RouteCompilerTest, SequenceCompilerTest | Wiederholungen mit Voltas, D.C./D.S./Coda/Fine, Tempo in Schreibreihenfolge, Swing, Fermaten; Bends unabhängig pro Saite, Legato, Let Ring, Strum-Reihenfolge, Flageoletts (auch mit Capo), Drum-Chokes und Element-Mix, Metronom nach Taktart |
| Synthesizer | SynthEngineTest, AudioClipTest | Stimmung der Saiten, PM/Dead klingen kürzer, Bend nur auf der gebendeten Saite, Flageolett-Oktave, deterministisches Rendern, High-Gain-Kette begrenzt, Drum-Chokes, Mute/Solo, sample-genauer Loop, Count-in, Vorschau, Offline-Mix und Stems gleicher Länge, Echtzeitfaktor; Clip-Offset, Loop-Wiederholung, Solo-Regel, Streaming-Resampler |
| Audio-Adapter | AudioAdapterTest, AudioDecodingTest | Transport gegen virtuelle Senke (Play/Pause/Seek/Loop/Count-in/Trainer, Position aus der Sample-Clock), Stimmgerät mit synthetischen Tönen, WAV-Decoder aller Formate, Längenlimit, Fehlerfälle, Wellenform-Peaks |
| Notensatz | LayoutTest | Fixtures in Eingabe-, Lese- und Standardnotations-Stil (PNG-Ausgabe zur Sichtprüfung), Trefferflächen für Spalten und Saiten, 200 Takte in kurzer Zeit, wohlgeformtes SVG |
| Speicher | ProjectStorageTest, NativeArchiveTest | Neustart, konkurrierende Revision, atomare Sichtbarkeit, Recovery, Snapshots, Assets, native Archive mit Pfad-, Größen- und Prüfsummenlimits |
| Formate | CatalogTest, XmlTest, Gp*Test, Gpif*Test, Gpx*Test, Midi*Test, MusicXmlTest, AsciiTabExporterTest | Inhaltserkennung; GP3/4/5 gegen PyGuitarPro-Orakel samt Verlustliste, Trunkierung und Mutationen; GPIF-Rundreise, ZIP-/XML-Angriffe, GPX-Container; MIDI-, MusicXML- und ASCII-Abbildung; Export nach GP7/MIDI/MusicXML/ASCII und Reimport über den Katalog; jedes Präfix eines XML-Dokuments scheitert sauber |
| ViewModels | AutosaveTest, ReferenceAudioTest, LibraryImportTest | Autosave, Retry und Snapshot-Rennen; Referenz-Audio importieren, ausrichten, entfernen, Undo; Fremdimport mit genau einer Herkunft und ohne verwaiste Assets |
| App | ApplicationFlowTest | tatsächliche MainActivity auf Robolectric API 35 (Debug und Release): Projekt anlegen, Bund 12, Undo/Redo/Persistenz, alle Bereiche, Funktionssuche, Drumgrid, Einstellungen, Tablet-Navigation |
| Architektur | verify_architecture.py | Modulrichtung, Zyklen, portabler Kern, vollständige Registerdimensionen, bekannte Routen/Verträge, aktuelle generierte Parität |

Android Lint muss ohne Fehler bestehen. Hinweise auf neuere Bibliotheksversionen sind bei der bewusst gepinnten Toolchain erwartbar.

## Manuelle Abnahme auf Android

1. Debug-APK installieren (eine ältere RiffWorkbench-Debug-APK vorher deinstallieren; Projekte vorher als `.riffproj` sichern). Bibliothek ohne Netzwerk öffnen, Projekt anlegen.
2. Riff auf der tiefen A-Saite eingeben: Bünde 0/12 mit Commit, 1/16, Palm-Mute, Dead Note, Bend, Slide. Jede Note muss beim Eintragen hörbar sein.
3. Wiedergabe ab Cursor, Loop über zwei Takte, Count-in, 70 %, Speed-Trainer. Playhead und Zählzeit-Punkte müssen dem Klang folgen, ohne Aussetzer.
4. Kopfhörer abziehen und Anruf simulieren: Wiedergabe muss pausieren. App in den Hintergrund: Wiedergabe pausiert, Stand ist gespeichert.
5. Eine eigene GP5- und eine GP7/8-Datei importieren; Verlustbericht auf der Austausch-Seite lesen. Als GP7, MIDI, MusicXML exportieren und in Guitar Pro, MuseScore beziehungsweise Cubase öffnen (Desktop-Gegencheck).
6. Eigene Aufnahme als Referenz-Audio importieren, per Wellenform „Takt 1“ setzen, mit der Partitur abspielen, Mute/Solo prüfen.
7. WAV-Mix und Stems exportieren und in Cubase übereinanderlegen.
8. Stimmgerät mit echter tiefer A-Saite. Querformat/Tablet, externe Tastatur und Screenreader prüfen.

## Noch offene Abnahmen

Gerätelatenz und Underruns, 30-min-Stresstest, Bluetooth/USB-Routing, Thermik, Hörvergleich des Synthesizers, MP3/AAC/Ogg/FLAC-Decoder auf dem Gerät, GP-Desktop-Rundreise, MuseScore, Cubase-Stem-/MIDI-Übergabe, Touch→Paint p50/p95 und 60-fps-Scrollen. Voraussetzungen und Pass/Fail-Folgen stehen in [SPIKES.md](SPIKES.md). Kein Wert wird als gemessene Leistung ausgegeben, solange er nicht auf einem Gerät gemessen wurde.
