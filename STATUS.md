# STATUS (Release-Candidate-Ziel)

Fortsetzung nach Pausen: dieses Dokument + `docs/QA_JOURNAL.md` lesen, dann beim "Nächster Schritt" weitermachen.
Branch: `ccr-0b650dff-jnhups-qa` (Fast-Forward nach `ccr-0b650dff-jnhups` bis 6ac710e erfolgt). Android-Builds/Tests nur auf GitHub Actions; lokal JVM-Harness für die portablen Module.

## Stand
- Letzter grüner CI-Lauf: d9c3c09 (Lauf 42172779720, mit APK-Artefakt `riffworkbench-debug-apk`). Vorher grün: 64b495b, 2b8a1d7, 7ed6d80.
- Zyklus 18 abgeschlossen (siehe QA_JOURNAL): Sample-Bank v2 (Feinstimmung/Pegel), Gain-Staging, Transport-Startfixes, Seek-Poller-Race, Startmarke/Zum-Anfang, ViewportScroll, Taktangabe-Sheet (Long-Press), Standard 1×4/4 + E-Standard, Umstimmen mit Konfliktplan.
- Zyklus 19 gemergt (64b495b, JVM 549/549, CI grün): Long-Press-Bereichsauswahl mit Haptik, Drum-Pegel/erster Hit/Raster, Gitarren-Artikulationen (Natural/Pinch Harmonics, Palm-Mute-Klangfarbe), Live = Offline bitidentisch, zwei Start-Races im Transport (PLAYING→PAUSED-Flackern, Ansicht sprang).

- Zyklus 20 gemergt (d9c3c09, JVM 572/572, CI grün): Editor-Zufallstests (6 Befunde: Eingabe-Überschreiben im Bereichsmodus, Navigation/Zum-Anfang im Bereichsmodus, Undo in Pause, Cursor nach Taktlöschen, stille No-op), Autosave-Absturztest (11 652 Abbilder, kein Verlust), Audio-Thread 0 B Allokation, Latenzausgleich (Gitarre/Bass 1,3 ms zu spät → ≤ 0,35 ms), Clip-Declick, Vorzähler-Phase, Poller-ABA und Startphasen-CAS (PLAYING→PAUSED unter Last), Partitur-Kopf kompakter mit fester TAB-Lage (−6,5 %/−11,8 % Höhe, 9→0 Label-Kollisionen).

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
- [ ] Export/Import nach Latenzausgleich und Layoutänderungen (WAV/Stems-Ausrichtung, SVG/PDF, Format-Rundreisen) — Zyklus 21
- [ ] Touch-Ziele ≥ 40 dp, schnelle Tab-Eingabe, Spurwechsel, Feedback bei wirkungslosen Eingaben (Robolectric-Audit) — Zyklus 21

## P2
- [x] Partitur-Kopf/Taktlabels kompakter (Qa20ScoreHeaderLayoutTest, keine Systemverschiebung bei Noteneingabe)
- [ ] Bends/Effektzeilen verschieben ihr System und die folgenden (vorbestehend); fester Platz pro Spur wäre möglich, kostet Höhe
- [ ] Unterste Effektzeile kann einen Bund-Chip der hohen Saite um ≈ 3 Einheiten überlappen (vorbestehend, geschätzt)
- [ ] MusicXML SHIFT-Slide über Stimmen (vorbestehend)

## Risiken (nie als bestanden ausgeben)
Keine Geräte-, Latenz- und Hörtests in dieser Umgebung: erster Schlag auf echtem Android, Unterläufe/Thermik, Klang von Clean/Crunch/High-Gain-Trims (0,3/0,28/0,6), Drum-Trim 0,5, Pinch-Mischpegel, Gesten/Haptik auf Hardware, TalkBack.
Bank-Grenze: Flatwound-DI-Aufnahme, E2 trocken ohne Energie > 2 kHz — „glasklarer“ Clean nur mit anderer (lizenzierter) Aufnahme oder Hörentscheidung über Höhenanhebung. Natural Harmonics sind modelliert (keine Flageolett-Samples).

## Nächster Schritt
Zyklus 21 (läuft): Export/Import-Regression und Robolectric-UX-Audit, danach Merge, Suite, CI. Android-Ausgabe geprüft (LOW_LATENCY, native Rate/Burst, Float, URGENT_AUDIO); Oboe/AAudio ohne belegten Nutzen nicht eingeführt.
