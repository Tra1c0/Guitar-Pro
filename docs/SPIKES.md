# Machbarkeitstests, offene Abnahmen und Roadmap

Die beiden Spikes aus Phase 1 sind im Code entschieden: Spike A durch den eigenen Notensatz und eigene GP-Formate statt alphaTab, Spike B durch die eigene Engine auf der AudioTrack-Sample-Clock statt Oboe/SF2. Was sich nur mit Hardware, Guitar Pro Desktop oder Cubase prüfen lässt, ist unten als offene Abnahme mit Pass/Fail-Kriterien beschrieben. Kein Punkt davon gilt als bestanden, solange er nicht durchgeführt wurde.

Voraussetzungen: Xiaomi 15, ein langsameres Android-Gerät, kabelgebundener und Bluetooth-Ausgang, eigener Zugang zu GP8 Desktop und Cubase.

## A: Editierintegration und GP7-Rückexport

**Im Code umgesetzt:** Eigener Notensatz mit Trefferflächen auf eigene IDs, Bearbeitung über EditorCommands mit Undo, sofortiges Neulayout. GP3/4/5-, GPX- und GP7/8-Import, GP7-Export und Reimport (Formatagent-Ergebnisse in adapter:formats). Automatisiert getestet sind Import gegen eigene Fixtures, Robustheit gegen beschädigte Dateien und Rundreisen innerhalb der App.

**Offen – Desktop-Gegencheck:**

1. In GP8 selbst erstellen: `spike-a-input.gp` mit Drop A, mehreren Stimmen, 7/8, PM/Dead/Slide/Pinch/Bend.
2. In der App importieren, bearbeiten, als `spike-a-edited-gp7.gp` exportieren; nach Undo zusätzlich `spike-a-undo-gp7.gp`.
3. Beide in GP8 öffnen, als `spike-a-desktop-resaved.gp` sichern und erneut importieren.
4. Track/Staff/Stimme/Takt/Beat/Note, Stimmung/Capo, Rhythmus, Links und Artikulationsparameter vergleichen. GP8-Audio, Layout, Presets, Automation und verschachtelte Tuplets gesondert im Verlustbericht prüfen.

**Pass:** GP8 öffnet die Exporte ohne relevante ungeklärte Unterschiede; der Reimport stimmt semantisch mit der erwarteten Änderung überein. **Fail-Folge:** Abweichungen als Verlusteinträge ergänzen oder den Exporter korrigieren; bis dahin bleibt `export.gp7`/`export.gp8` auf DESKTOP_PENDING.

**Offen – Messung auf dem Gerät:** Touch→Paint getrennt von Layoutdauer, Scroll-/Zoom-FPS und Allokationen an `eight-tracks-200-bars.riffproj`. Ziel unter 50 ms p95 visuelles Feedback und 60 fps beim normalen Scrollen. Auf der JVM braucht das Layout von 200 Takten etwa 35 ms; das ist kein Gerätewert.

## B: Gemeinsame Audio-Uhr

**Im Code umgesetzt:** Eigener Synthesizer, Metronom und Referenz-Clips auf derselben Sample-Clock; Tempoänderung, Seek mit Chase, Loop, Count-in und Stop/Resume. Automatisiert gegen eine virtuelle Senke getestet (SynthEngineTest, AudioAdapterTest, AudioClipTest): Start-/Endframes, Loop ohne Drift, Clip-Offset, zweite gehaltene Note bleibt beim Bend auf der Nachbarsaite unverändert.

**Offen – auf dem Gerät:**

- Latenz Tastendruck→Ton (Ziel unter 70 ms p95 für die Notenvorschau), Underruns im 30-Minuten-Loop, Route-Wechsel (Kopfhörer, Bluetooth, USB), Audio-Fokus bei Anrufen, Thermik.
- Hörprobe der Referenz-Clips mit MP3/AAC/Ogg/FLAC, Encoder-Delay am Dateianfang (ein MP3 kann einige Millisekunden früh oder spät liegen; der Offset gleicht das aus).
- Stimmgerät mit echter tiefer A-Saite, Störgeräuschen und Oktavfehlern.

**Pass:** keine wachsende Drift, Underrun-Zähler bleibt bei normaler Last bei 0, Position und Klang bleiben nach Route-Wechsel konsistent. **Fail-Folge:** Puffergröße und Renderblock anpassen; reicht das nicht, einen Oboe-Adapter als weitere `AudioSink` ergänzen, ohne Engine oder Modell umzubauen.

**Klang:** Blindvergleich des eigenen Synthesizers mit Aufnahmen echter Instrumente (PM, Dead Notes, Pinch, Bends, Drums). Ergebnis bestimmt, ob eine eigene Artikulations-Samplebank (C-SOUNDBANK) nötig ist.

## C: Cubase-Übergabe

**Im Code umgesetzt:** WAV-Mix und Stems (gleicher Startframe, gleiche Länge, 24 Bit) als ZIP samt MIDI mit Tempo-Map.

**Offen:** Stems und MIDI in Cubase importieren, Startpunkt und Tempo-Map mit dem Mix vergleichen. **Pass:** Stems liegen sample-genau übereinander, MIDI-Noten liegen auf den Stems.

## Roadmap

| Etappe | Ergebnis |
|---|---|
| Nächster Schritt | Geräte-, Desktop- und Cubase-Abnahmen oben; Time-Stretch für Referenz-Audio (C-STRETCH); Anker-Sync für frei gespielte Aufnahmen (C-SYNC) |
| Danach | Eigene Artikulations-Samplebank oder SF2-Import (C-SOUNDBANK), Bus-Routing und Automation, USB-MIDI-Eingabe, Line-in, Befehlspalette, Slash- und Jianpu-Notation, Lyrics- und Akkordsymbol-Satz |
| Lizenzabhängig | Fretlight und mySongBook jeweils separat technisch und vertraglich prüfen; VST/AUv3 sind plattformspezifische Vorhaben |

Die Zuordnung jedes Merkmals und jeder Fähigkeit steht in PARITY.md. GP8-Fidelity ist kein einzelner Schalter und wird nicht aus erfolgreichem Öffnen einer Datei abgeleitet.
