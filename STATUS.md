# STATUS (Release-Candidate-Ziel)

Fortsetzung nach Pausen: dieses Dokument + `docs/QA_JOURNAL.md` lesen, dann beim "Nächster Schritt" weitermachen.
Branch: `ccr-0b650dff-jnhups-qa` (Fast-Forward nach `ccr-0b650dff-jnhups` bis 6ac710e erfolgt). Android-Builds/Tests nur auf GitHub Actions; lokal JVM-Harness für die portablen Module.

## Stand
- Letzter grüner CI-Lauf: 6ac710e (Lauf 42127215984); APK-Artefakt `riffworkbench-debug-apk` im Lauf 42126990857 (57453bb).
- Zyklus 18 abgeschlossen (siehe QA_JOURNAL): Sample-Bank v2 (Feinstimmung/Pegel), Gain-Staging, Transport-Startfixes, Seek-Poller-Race, Startmarke/Zum-Anfang, ViewportScroll, Taktangabe-Sheet (Long-Press), Standard 1×4/4 + E-Standard, Umstimmen mit Konfliktplan.

## P0 (Crash / Datenverlust / Playback)
- Keine bekannten offenen. (Gerätetest des ersten Schlags steht aus, siehe Risiken.)

## P1 (Audio / Bedienung)
- [ ] Long-Press-Bereichsauswahl mit Haptik (Bericht 1 §5)
- [x] Drum-Grid: Überlappung/Bedienbarkeit auf dem Handy; Drum-Pegel; erster Hit (Zyklus 19, JVM grün; DrumScreen-UI, Gerät und Hörprüfung offen)
- [ ] Gitarren-Artikulationen klar unterscheidbar (Palm Mute, Dead, Harmonics natural/pinch, Slides/Bends), messbar per Render-Test
- [ ] Preset-Lautheit/-Charakter nachprüfbar verschieden (Clean/Crunch/High Gain)
- [ ] Live- vs. Offline-Render Konsistenz (Gitarre, Metronom, Drums)

## P2
- [ ] Partitur-Kopf/Taktlabels weiter kompaktieren
- [ ] MusicXML SHIFT-Slide über Stimmen (vorbestehend)

## Risiken (nie als bestanden ausgeben)
Keine Geräte-, Latenz- und Hörtests in dieser Umgebung: erster Schlag auf echtem Android, Unterläufe/Thermik, Klang von Clean/Crunch/High-Gain-Trims (0,3/0,28/0,6), Gesten/Haptik auf Hardware, TalkBack.

## Nächster Schritt
Zyklus 19: Long-Press-Auswahl, Drum-Grid/-Pegel, Artikulations-Messungen (parallel in Worktrees), dann Merge, volle JVM-Suite, CI.
