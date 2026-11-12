# STATUS (Release-Candidate-Ziel)

Fortsetzung nach Pausen: dieses Dokument + `docs/QA_JOURNAL.md` lesen, dann beim "Nächster Schritt" weitermachen.
Branch: `ccr-0b650dff-jnhups-qa`, per Fast-Forward gespiegelt nach `ccr-0b650dff-jnhups`. Android-Builds/Tests nur auf GitHub Actions; lokal JVM-Harness für die portablen Module.

## Stand
- Letzter grüner CI-Lauf: 6d7fb34 (Lauf 43277721624, Build + `check` + Lint + Robolectric, APK-Artefakt `riffworkbench-debug-apk`). Vorher grün: e2c2cb4, 8e5ab38, babba9a, eb82763, 2acce85, 1eec164, d9c3c09, 7ed6d80, 2b8a1d7, 64b495b.
- Lokale JVM-Suite (portable Module): 620/620 grün auf 9579316 (+ ViewModel-Shim 59/59).
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

## Zyklus 27 (gemergt, CI grün 6d7fb34): (a) Auto-Updates: CI signiert den Debug-Build mit festem Schlüssel aus Secrets (ANDROID_KEYSTORE_BASE64/…_PASSWORD/ANDROID_KEY_ALIAS/ANDROID_KEY_PASSWORD), versionCode = Laufnummer+1000, Release-Job veröffentlicht v<code> von ccr-0b650dff-jnhups-qa; In-App-Updater (Einstellungen → Updates, Startprüfung ≤ alle 6 h, abschaltbar, einzige Netzverbindung). Noch kein Release: Secrets fehlen (Nutzer). (b) Rhythmus tippen: Ziel „Offen“ mit Stopp/Auto-Stopp, Takte am Stückende anhängen, nahtloses Plan-Verlängern (Reprogram seamless).

## Zyklus 26 (gemergt, CI grün 992f8c4): Latenz pro Ausgabegerät — AudioRouteMonitor (geroutetes Gerät, Typ+Name), AppSettings.latencyProfiles, Einstellungen → Latenz (Kalibrieren, Liste, Löschen), Hinweis bei unkalibriertem BT/BLE/Hörgerät, Playhead-Verzögerung = tap(Gerät) − tap(Lautsprecher/Kabel). Alte globale tapLatencyMs bleibt Fallback. Offen am Gerät: Routing-Meldung, Namensstabilität, Codec-Schwankung. 

## Zyklus 25 (gemergt, CI grün d8c56e0): „Rhythmus tippen“ (Werkzeuge → Rhythmus): Vorzähler + Metronom, Tippfläche mit Down/Up, Haltedauer = Notenwert, kurzer Tipp = Anschlag + Pausen, Raster 1/8–1/32/Auto (Triolen), Übernahme als Raster-Platzhalter oder Leersaite (ein Undo), Latenz-Kalibrierung (AppSettings.tapLatencyMs), Zeitabbildung Tipp → hörbarer Planframe. Notenwert ändern verschiebt keine Folgenoten mehr (kürzer → Pause, länger frisst Pausen, sonst Ablehnung). Gerätegefühl/Timing ungeprüft.

## Zyklus 24 (gemergt, CI grün b25c72a): App heißt ArtiFret (sichtbare Namen; Pakete/App-ID/.riffproj unverändert), neues adaptives Icon, gesampeltes Schlagzeug „ArtiFret Pearl Kit“ aus AVL Black Pearl 4 (CC BY-SA 3.0 mit AVL-Ausnahme; README in Assets und unter Einstellungen → Lizenzen & Herkunft; docs/DEPENDENCIES.md), synthetisches Kit als Fallback. Hörurteil offen: transponierte Toms, China = Crash 3, Kit-Balance.

## Schritt 2 (erledigt in Zyklus 24; Notizen bleiben als Herkunft): gesampeltes Schlagzeug
Heutige Drums sind rein synthetisch (core/synth OtherVoices.kt `DrumVoice`), klingen laut Nutzer nicht gut. Vom Nutzer genannte Kandidaten (seine Lizenzprüfung; beim Umsetzen Lizenztext und Herkunft selbst verifizieren und in docs/DEPENDENCIES.md eintragen):
- AVL Black Pearl — CC BY-SA 3.0, kommerziell erlaubt. Pflichten: Namensnennung; ShareAlike gilt für die Samples und daraus abgeleitete Bänke (umgewandelte Bank unter CC BY-SA weitergeben, Lizenzhinweis in App/Assets).
- MuldjordKit — CC BY 4.0, kommerziell erlaubt. Pflicht: Namensnennung.
- FreePats GM — GPL v3+ mit Ausnahme (Ausnahme-Wortlaut prüfen: ob eingebettete Samples die App nicht unter GPL stellen).
Technik: vorhandene Sample-Infrastruktur (RWSB, SampleVoice) nutzen; Velocity-Layer, Round Robins, Hi-Hat-Choke offen/geschlossen, GM-Mapping; APK-Größe beachten; danach Drum-Pegel und Erster-Schlag-Tests (Qa19DrumLevelTest, Qa19DrumFirstHitTest) neu einstellen. Download aus dieser Umgebung ggf. blockiert → Nutzer lädt Dateien hoch oder gibt den Host frei.

## Zyklus 23 (gemergt, CI grün 9579316): Nutzer-Gerätebefunde UI — alle Punkte behoben laut Tests, Gerätebestätigung offen
- P0 Effekte/Notenwerte werden zufällig auf andere Noten geschrieben (nach Bereichs-Operationen)
- P1 Bereichsauswahl: Aufheben markiert zufällig andere Bereiche, danach „buggt alles“
- P1 Raster feiner als 16tel praktisch nicht nutzbar
- P1 Flackern: diverse Menüs, Drei-Punkte-Menüs extrem, gezoomte Partitur bei Play
- P1 Meldungen erscheinen auch in Drei-Punkte-Menüs, müssen manuell geschlossen werden und schieben das Layout → sollen überlagern und von selbst verschwinden
- UX Partitur größer; Zoom standardmäßig aus, nur per Einstellung aktivierbar (Nutzer hatte „größer“ gemeint, nicht „kompakter“)

## Risiken (nie als bestanden ausgeben)
Keine Geräte-, Latenz- und Hörtests in dieser Umgebung: erster Schlag auf echtem Android, Unterläufe/Thermik, ART-Allokationen/CPU am Telefon (nur JVM-Desktop gemessen), Klang von Clean/Crunch/High-Gain-Trims (0,3/0,28/0,6), Drum-Trim 0,5, Pinch-Mischpegel, Gesten/Haptik/Pinch auf Hardware, TalkBack (inkl. neuer Viewport-Semantik am Notenbild), PDF nur nachgestellt (nicht mit Android PdfDocument), kein Fremd-DAW-Import der Stems.
Bank-Grenze: Flatwound-DI-Aufnahme, E2 trocken ohne Energie > 2 kHz — „glasklarer“ Clean nur mit anderer (lizenzierter) Aufnahme oder Hörentscheidung über Höhenanhebung. Natural Harmonics sind modelliert (keine Flageolett-Samples).

## Nächster Schritt
Autonom lösbare P0/P1-Befunde sind abgearbeitet (Zyklen 18–22). Als Nächstes braucht es Gerätebefunde des Nutzers: APK aus Lauf 42272758760 installieren, A/B-WAVs aus Zyklus 22 anhören, Diagnose-Zähler (Unterläufe/Überläufe) nach dem ersten Play eines frischen Prozesses ablesen, Hörtest Clean/Crunch/HG mit Open/PM/Dead/Harmonics/Pinch/Legato, Bereichsauswahl/Haptik und Pinch-Zoom am Telefon. Danach P2 oder Klangfeinschliff nach Gehör. Branch `ccr-0b650dff-jnhups` per Fast-Forward auf denselben Stand wie `ccr-0b650dff-jnhups-qa` gebracht.
