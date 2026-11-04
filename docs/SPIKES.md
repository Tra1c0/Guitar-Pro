# Machbarkeitstests und Roadmap

Diese Datei ist ein Implementierungsauftrag für die nächste Phase. Beide Spikes sind noch offen. Voraussetzung für Hardware-Abnahmen: Xiaomi 15, ein langsameres Android-Gerät, kabelgebundener/USB-Ausgang und eigener Zugang zu GP8 Desktop beziehungsweise Cubase. Nichts davon wurde für Phase 1 als vorhanden angenommen.

## Spike A: Editierintegration und GP7-Rückexport

**Umfang:** alphaTab AndroidView in Compose, GP8-Import, eigene ID-Abbildung, Auswahl über Bounds, Note/Artikulation ändern, Undo, Rerender, GP7-Export und Reimport. Kleiner Web-/alphaTab-Vergleich anhand derselben Aktionen. Keine Übernahme fremder Testtabs.

**Module:** adapter:alphatab, adapter:formats, ui:score, core:editor. Die veröffentlichten APIs sind AlphaTabView.api, renderScore/render, boundsLookup mit includeNoteBounds sowie ScoreLoader/Gp7Exporter. Kotlin-Signaturen und generierte Typen am gewählten veröffentlichten Artefakt kompilierend prüfen. Strukturänderungen benötigen Relink/Finish; Renderer- und MIDI-Caches sind getrennt.

**Toolchain:** alphaTab 1.9.0 ist auf Maven Central vorhanden, fordert laut Release-Quellcode SDK 37 und veröffentlicht Kotlin-stdlib 2.4.21. Vor Aktivierung eine passende AGP/Kotlin/SDK-Kombination pinnen. Die aktuelle Basis bleibt bis zu diesem Abgleich ohne alphaTab-Runtime.

**Fixtures und Desktop-Gegencheck:**

1. Selbst in GP8 erstellen: `spike-a-input.gp` mit Drop A, mehreren Stimmen, 7/8, PM/Dead/Slide/Pinch/Bend.
2. Export: `spike-a-edited-gp7.gp`; nach Undo zusätzlich `spike-a-undo-gp7.gp`.
3. In GP8 öffnen, als `spike-a-desktop-resaved.gp` sichern und erneut in die App importieren.
4. Track/Staff/Stimme/Takt/Beat/Note, Stimmung/Capo, Rhythmus, Links und Artikulationsparameter vergleichen. GP8-Audio, Layout, Presets, Automation und verschachtelte Tuplets gesondert im Verlustbericht prüfen.

**Pass:** Eingabe und Undo reproduzierbar; keine verwaisten Links/IDs; semantischer Reimport stimmt mit der erwarteten Änderung überein; GP8 öffnet den Export ohne relevante ungeklärte Unterschiede. Desktop-Check ohne Desktop bleibt offen.

**Messung:** Touch→Paint getrennt von Renderdauer, Scroll-/Zoom-FPS und Allokationen an eigenen 8-Spur-/200-Takt-Fixtures. Ziel <50 ms p95 visuelles Feedback; 60 fps beim normalen Scrollen. Messaufbau/Gerät dokumentieren.

**Fail-Folge:** Bei fehlerhafter Bounds-/Editierintegration bleibt die eigene TAB aktiv. Web-Vergleich entscheidet über alternativen Rendereradapter. Keine Abhängigkeit des Kernmodells von alphaTab einführen. Bei Fidelity-Verlust GP7 nur für nachgewiesene Merkmale freigeben; native Datei bleibt vollständig.

## Spike B: Gemeinsame Audio-Uhr

**Umfang:** Oboe-Ausgabe, ein frei nutzbarer SF2-Synth-Kandidat, Metronom und genau ein PCM-Stem auf derselben Sample-Clock. Tempoänderung, Seek, Loop und reproduzierbares Stop/Resume. Kein RSE-Klangversprechen.

**Module:** core:playback, adapter:audio und platform:android. Kotlin-Control-Thread kompiliert Events; native Engine übernimmt begrenzte Eventqueues/immutable Pläne. Seek rekonstruiert Instrument-/Controllerzustand. Audio-Callback allokiert nicht und betreibt weder I/O noch JNI pro Sample.

**Fixture:** `shared-clock.riffproj`, `clock-120bpm-48k.wav`; 48 kHz, 8 s, Impulse alle 24.000 Frames, exklusives Loop-Ende 384.000. Varianten mit Tempoänderung und nichtganzzahligen BPM ergänzen. Eine zweite gehaltene Note muss bei einem Bend auf der anderen Saite unverändert bleiben.

**Pass:** Instrumentierte Start-/End-Samples und Anker stimmen mit der goldenen Route überein; keine kumulative Drift beim Loopen; Seek-Ereignisse und Decoder-Preroll sind definiert. 30-min-Stresstest ohne wachsende Drift, mit Underrun-Zähler, Pause/Resume, Route-Wechsel und Thermikprotokoll. Geräteabhängige Latenz separat berichten. Ziel Touch→Preview <70 ms p95 über Kabel/USB.

**Fail-Folge:** Engine bleibt experimentell. Buffer, Scheduling, Resampling und Decoder getrennt untersuchen; keine UI-Clock als Ersatz. Oboe gegen einen anderen nativen Ausgabeadapter evaluieren, ohne Editor-/Projektmodell umzubauen.

**Klangfolge:** GM-Basis gegen vorverzerrte Artikulationsbank und DI+Amp/Cab blind vergleichen. Eigene gedämpfte PM- und perkussive Dead-Note-Samples; Harmonic-Pitch/Obertöne; per-Voice-Bends, Releases und Saiten-Chokes. Rechte, CPU und Thermik sind eigene Abnahmepunkte.

## Roadmap nach den Spikes

| Etappe | Ergebnis |
|---|---|
| B | Vollständigerer Gitarren-/Drum-Editor, Basisrhythmik, zentrale Metal-Techniken, funktionierendes Playback/Mixer/Loop/Klick, GP7/MIDI |
| C | Komplexe Wiederholungen, vier Stimmen vertiefen, verschachtelte Tuplets, weitere Notation, Audio-Sync, MusicXML/PDF, Automation und GP8-Teiltreue |
| D | Mehrere Stems, Stretch/Pitch, eigene Soundbanks/Gitarrenbank, synchroner WAV-/MIDI-Export für Cubase, Line-in/MIDI, weitere Ausgaben, Tablet-Verfeinerung und iOS-Entscheidung |
| Lizenzabhängig | Fretlight und mySongBook jeweils separat technisch und vertraglich prüfen; VST/AUv3 sind plattformspezifische Vorhaben |

Die Zuordnung jedes Merkmals und jeder Fähigkeit steht in PARITY.md. GP8-Fidelity ist kein einzelner Schalter und wird nicht aus erfolgreichem Öffnen einer Datei abgeleitet.
