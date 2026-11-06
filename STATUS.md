# STATUS (Release-Candidate-Ziel)

Fortsetzung nach Pausen: dieses Dokument + `docs/QA_JOURNAL.md` lesen, dann beim "Nächster Schritt" weitermachen.
Branch: `ccr-0b650dff-jnhups-qa` (Fast-Forward nach `ccr-0b650dff-jnhups` bis 6ac710e erfolgt). Android-Builds/Tests nur auf GitHub Actions; lokal JVM-Harness für die portablen Module.

## Stand
- Letzter grüner CI-Lauf: 6ac710e (Lauf 42127215984); APK-Artefakt `riffworkbench-debug-apk` im Lauf 42126990857 (57453bb).
- Zyklus 18 abgeschlossen (siehe QA_JOURNAL): Sample-Bank v2 (Feinstimmung/Pegel), Gain-Staging, Transport-Startfixes, Seek-Poller-Race, Startmarke/Zum-Anfang, ViewportScroll, Taktangabe-Sheet (Long-Press), Standard 1×4/4 + E-Standard, Umstimmen mit Konfliktplan.
- Zyklus 19 gemergt (64b495b, JVM 549/549 grün; CI ausstehend): Long-Press-Bereichsauswahl mit Haptik, Drum-Pegel/erster Hit/Raster, Gitarren-Artikulationen (Natural/Pinch Harmonics, Palm-Mute-Klangfarbe), Live = Offline bitidentisch, zwei Start-Races im Transport (PLAYING→PAUSED-Flackern, Ansicht sprang).

## P0 (Crash / Datenverlust / Playback)
- Keine bekannten offenen. (Gerätetest des ersten Schlags steht aus, siehe Risiken.)

## P1 (Audio / Bedienung)
- [x] Long-Press-Bereichsauswahl mit Haptik (Bericht 1 §5) — Robolectric nur CI, Haptik am Gerät offen; auf der ausgewählten Note bleibt Halten+Ziehen ein Noten-Drag
- [x] Drum-Grid: Überlappung/Bedienbarkeit auf dem Handy; Drum-Pegel; erster Hit (Zyklus 19, JVM grün; DrumScreen-UI, Gerät und Hörprüfung offen)
- [x] Gitarren-Artikulationen messbar unterscheidbar (Qa19ArticulationTest); offen: HG gepickt vs. gebunden gleich, Pinch vs. Natural auf hoher E-Saite in Crunch/HG kaum verschieden (Klangentscheidung/Hörtest)
- [x] Preset-Charakter nachprüfbar verschieden (Schwerpunkt 462/519/1445 Hz, THD 1,1/20,8/24,3 %, Note-off-Ausklang 192/125/67 ms); Lautheitsspreizung zwischen Presets weiter offen (Designentscheidung)
- [x] Live- vs. Offline-Render Gitarre/Drums bitidentisch (Qa19LiveVsOfflineTest, Qa19DrumLevelTest)
- [ ] Audio-Thread: Allokationen/Locks/CPU je Block mit allen neuen Pfaden; Metronom/Drums/Backing sample-genau synchron (Zyklus 20)
- [ ] Editor-Robustheit: Bereichsoperationen, Undo/Redo, Autosave, Navigation per modellbasiertem Zufallstest (Zyklus 20)

## P2
- [ ] Partitur-Kopf/Taktlabels weiter kompaktieren
- [ ] MusicXML SHIFT-Slide über Stimmen (vorbestehend)

## Risiken (nie als bestanden ausgeben)
Keine Geräte-, Latenz- und Hörtests in dieser Umgebung: erster Schlag auf echtem Android, Unterläufe/Thermik, Klang von Clean/Crunch/High-Gain-Trims (0,3/0,28/0,6), Drum-Trim 0,5, Pinch-Mischpegel, Gesten/Haptik auf Hardware, TalkBack.
Bank-Grenze: Flatwound-DI-Aufnahme, E2 trocken ohne Energie > 2 kHz — „glasklarer“ Clean nur mit anderer (lizenzierter) Aufnahme oder Hörentscheidung über Höhenanhebung. Natural Harmonics sind modelliert (keine Flageolett-Samples).

## Nächster Schritt
CI für 64b495b grün bekommen → APK-Link. Zyklus 20: Audio-Thread-Hygiene/CPU/Synchronität und modellbasierter Editor-Zufallstest (Bereich, Undo, Autosave, Navigation), parallel in Worktrees.
