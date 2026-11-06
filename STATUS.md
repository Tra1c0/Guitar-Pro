# STATUS (Release-Candidate-Ziel)

Fortsetzung nach Pausen: dieses Dokument + `docs/QA_JOURNAL.md` lesen, dann beim "Nächster Schritt" weitermachen.
Branch: `ccr-0b650dff-jnhups-qa`, per Fast-Forward gespiegelt nach `ccr-0b650dff-jnhups`. Android-Builds/Tests nur auf GitHub Actions; lokal JVM-Harness für die portablen Module.

## Stand
- Letzter grüner CI-Lauf: 8e5ab38 (Lauf 42205433832, Build + `check` + Lint + Robolectric, APK-Artefakt `riffworkbench-debug-apk`). Vorher grün: babba9a, eb82763, 2acce85, 1eec164, d9c3c09, 7ed6d80, 2b8a1d7, 64b495b.
- Lokale JVM-Suite (portable Module): 603/603 grün auf 8e5ab38.
- Zyklus 18 abgeschlossen (siehe QA_JOURNAL): Sample-Bank v2 (Feinstimmung/Pegel), Gain-Staging, Transport-Startfixes, Seek-Poller-Race, Startmarke/Zum-Anfang, ViewportScroll, Taktangabe-Sheet (Long-Press), Standard 1×4/4 + E-Standard, Umstimmen mit Konfliktplan.
- Zyklus 19 gemergt (64b495b, JVM 549/549, CI grün): Long-Press-Bereichsauswahl mit Haptik, Drum-Pegel/erster Hit/Raster, Gitarren-Artikulationen (Natural/Pinch Harmonics, Palm-Mute-Klangfarbe), Live = Offline bitidentisch, zwei Start-Races im Transport (PLAYING→PAUSED-Flackern, Ansicht sprang).

- Zyklus 20 gemergt (d9c3c09, JVM 572/572, CI grün): Editor-Zufallstests (6 Befunde: Eingabe-Überschreiben im Bereichsmodus, Navigation/Zum-Anfang im Bereichsmodus, Undo in Pause, Cursor nach Taktlöschen, stille No-op), Autosave-Absturztest (11 652 Abbilder, kein Verlust), Audio-Thread 0 B Allokation, Latenzausgleich (Gitarre/Bass 1,3 ms zu spät → ≤ 0,35 ms), Clip-Declick, Vorzähler-Phase, Poller-ABA und Startphasen-CAS (PLAYING→PAUSED unter Last), Partitur-Kopf kompakter mit fester TAB-Lage (−6,5 %/−11,8 % Höhe, 9→0 Label-Kollisionen).

- Zyklus 21 gemergt (2acce85, CI grün): Export ohne 64-Frame-Vorlauf und vollständig, Stems = Mix (≤ 5·10⁻⁸, Rausch-Seed je Spur), Mix/Stems bei Master-Latenz ausgerichtet, MIDI-Import behält 1/32-/1/64-Raster (Korpus 55 besser, 0 schlechter), MIDI-Note-on vs. Audio ≤ 1 Frame; Notensatz-Export ohne Befund. UX-Audit (Robolectric, 5 Handy-Konfigurationen): Pinch-Zoom springt nicht mehr zum Cursor, Undo nach Auto-Takt, Spurwechsel behält Saite, Einstellungen/Diagnose im Mehr-Sheet erreichbar, Vorzähler-Chips nicht mehr gequetscht, Rückmeldung bei wirkungslosen Eingaben, fehlende Labels ergänzt.

- Zyklus 22 gemergt (eb82763, CI grün): HG gepickt vs. gebunden messbar getrennt (kleinste Differenz −0,9 → +3,5 dB; Legato setzt die klingende Saite fort, HG-Amp folgt kurz dem Anschlag), Pinch vs. Natural auf hoher E getrennt (Schwerpunkt ×1,01–1,08 → ×1,23–1,73; Pinch 12. Bund am Oberton), Stems clipfrei bis Track-Fader 1,0, Standardband-Balance und -Lautheit wie vorher (Limiter 0 %), Clean-Kompressor-Make-up 6 → 9 dB. Preset-Spreizung 7,2 → 6,4 LU. Entscheidung: Das eigene Zwischenziel ≤ 3 LU (keine Anforderung der Zielvorgabe, die nur „nachprüfbar verschieden“ verlangt) ist ersetzt durch „Spreizung ≤ 7 LU, Clean bleibt das dynamischste Preset, Stems clipfrei“. Begründung, gemessen: Die Clean-Spitzen sind Pick-Transienten der ersten 1–3 ms; ein Kompressor mit Attack ≥ 3 ms senkt sie nur um 0,7 dB, kürzere Attack-Zeiten drücken den Körper um 7–8 dB, mehr Make-up verletzt das Amp-Eingangsbudget, und eine Absenkung von Crunch/HG machte Band oder gespeicherte Projekte 8,5–8,6 dB leiser. Gleiche Lautheit über die Presets ist damit eine Klangentscheidung des Nutzers, kein Fehler. A/B-WAVs dem Nutzer geschickt.

## P0 (Crash / Datenverlust / Playback)
- Keine bekannten offenen. (Gerätetest des ersten Schlags steht aus, siehe Risiken.)

## P1 (Audio / Bedienung)
- [x] Long-Press-Bereichsauswahl mit Haptik (Bericht 1 §5) — Robolectric nur CI, Haptik am Gerät offen; auf der ausgewählten Note bleibt Halten+Ziehen ein Noten-Drag
- [x] Drum-Grid: Überlappung/Bedienbarkeit auf dem Handy; Drum-Pegel; erster Hit (Zyklus 19, JVM grün; DrumScreen-UI, Gerät und Hörprüfung offen)
- [x] Gitarren-Artikulationen messbar unterscheidbar (Qa19ArticulationTest); offen: HG gepickt vs. gebunden gleich, Pinch vs. Natural auf hoher E-Saite in Crunch/HG kaum verschieden (Klangentscheidung/Hörtest)
- [x] Preset-Charakter nachprüfbar verschieden (Schwerpunkt 462/519/1445 Hz, THD 1,1/20,8/24,3 %, Note-off-Ausklang 192/125/67 ms); Lautheitsspreizung zwischen Presets weiter offen (Designentscheidung)
- [x] Live- vs. Offline-Render Gitarre/Drums bitidentisch (Qa19LiveVsOfflineTest, Qa19DrumLevelTest)
- [x] Audio-Thread: 0 B Allokation, keine Locks (Qa20AudioThreadAllocTest); CPU p99 ≤ 12 % Budget (JVM-Desktop, kein Android-Wert); Gitarre/Bass/Drums/Klick/Clip ≤ 0,35 ms (Qa20SyncTest)
- [x] Editor-Robustheit: Qa20ModelBasedEditorTest, Qa20WorkspaceRobustnessTest, Qa20AutosaveCrashTest
- [x] Export/Import nach Latenzausgleich und Layoutänderungen (Qa21ExportAlignmentTest, Qa21MidiAudioTimingTest, Qa21FormatShapesTest, Qa21PrintLayoutTest)
- [x] Touch-Ziele ≥ 40 dp, schnelle Tab-Eingabe, Spurwechsel, Feedback (Qa21TouchTargetTest, Qa21TabEntryFlowTest, Qa21TabEntryViewModelTest, Qa21ViewportCursorTest)
- [x] HG gepickt vs. gebunden, Pinch vs. Natural (hohe E) messbar getrennt (Qa22TransitionPinchLoudnessTest)
- [ ] Hörentscheidungen (nicht autonom lösbar): Natürlichkeit des HG-Anschlags, PM-Riff über einen Takt ~5 % heller, 6-ms-Legato-Überblendung (Intermodulation in HG möglich), hellerer Pinch in Clean, Clean ≈ 6 LU leiser als HG (Hörurteil, ob das stört), Demo-Band mit 2× HG: Limiter 3,7 % (max 1,2 dB)

## P2
- [x] Partitur-Kopf/Taktlabels kompakter (Qa20ScoreHeaderLayoutTest, keine Systemverschiebung bei Noteneingabe)
- [ ] Bends/Effektzeilen verschieben ihr System und die folgenden (vorbestehend); fester Platz pro Spur wäre möglich, kostet Höhe
- [ ] Unterste Effektzeile kann einen Bund-Chip der hohen Saite um ≈ 3 Einheiten überlappen (vorbestehend, geschätzt)
- [ ] MusicXML SHIFT-Slide über Stimmen (vorbestehend)
- [ ] Referenz-Clips nur im Mix, nicht als eigener Stem (mit Clips summieren sich Stems nicht zum Mix); `EngineTransport.render` (UI nutzt `renderTo`) übergibt keine Clips
- [ ] Dauerzeile auf 320-dp-Geräten effektiv 37,75 dp (außerhalb der Prüfmatrix); Bei Schrift 2,0 verkleinert die Hinweiszeile den Editor stark
- [ ] Langer Hall endet im Export hart nach 2 s Ausklang (≈ −82 dBFS)
- [x] Übersteuerte Exporte melden sich jetzt (Qa22ExportClipNoticeTest): die Exportmeldung nennt jede übersteuerte Datei mit Sample-Zahl. Stems sind unabhängig vom Master-Fader (Qa22TransitionPinchLoudnessTest.stemsDoNotDependOnTheMasterFader, rot davor: Clean-Stem bei Master 1,0 +3,1 dBFS, 72 geclippt); kein Fader-Paar kann einen Stem mehr übersteuern

## Risiken (nie als bestanden ausgeben)
Keine Geräte-, Latenz- und Hörtests in dieser Umgebung: erster Schlag auf echtem Android, Unterläufe/Thermik, ART-Allokationen/CPU am Telefon (nur JVM-Desktop gemessen), Klang von Clean/Crunch/High-Gain-Trims (0,3/0,28/0,6), Drum-Trim 0,5, Pinch-Mischpegel, Gesten/Haptik/Pinch auf Hardware, TalkBack (inkl. neuer Viewport-Semantik am Notenbild), PDF nur nachgestellt (nicht mit Android PdfDocument), kein Fremd-DAW-Import der Stems.
Bank-Grenze: Flatwound-DI-Aufnahme, E2 trocken ohne Energie > 2 kHz — „glasklarer“ Clean nur mit anderer (lizenzierter) Aufnahme oder Hörentscheidung über Höhenanhebung. Natural Harmonics sind modelliert (keine Flageolett-Samples).

## Nächster Schritt
Autonom lösbare P0/P1-Befunde sind abgearbeitet (Zyklen 18–22). Als Nächstes braucht es Gerätebefunde des Nutzers: APK aus Lauf 42205433832 installieren, A/B-WAVs aus Zyklus 22 anhören, Diagnose-Zähler (Unterläufe/Überläufe) nach dem ersten Play eines frischen Prozesses ablesen, Hörtest Clean/Crunch/HG mit Open/PM/Dead/Harmonics/Pinch/Legato, Bereichsauswahl/Haptik und Pinch-Zoom am Telefon. Danach P2 oder Klangfeinschliff nach Gehör. Branch `ccr-0b650dff-jnhups` per Fast-Forward auf denselben Stand wie `ccr-0b650dff-jnhups-qa` gebracht.
