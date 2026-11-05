# QA-Journal

Persistentes Protokoll der Qualitätsprüfungen. Jede Prüfung bekommt einen eigenen Abschnitt: Methode, Schwerpunkt, Befunde (bestätigt / unbestätigt), Reparaturen mit Test, offene Punkte. Bestätigt heißt: durch einen Test reproduziert oder, bei nur auf CI baubaren Android-Modulen, durch eine nachvollziehbare Code-Analyse belegt und so gekennzeichnet.

Arbeitszweig: `ccr-0b650dff-jnhups-qa` (abgezweigt von `ccr-0b650dff-jnhups` @ `23f7faa`). Keine Merges, keine Deployments.

## Rahmen und Grenzen

- Lokal baubar und testbar: reine Kotlin-Module (core/*, adapter/formats, adapter/audio, data/local-Kern) über das JVM-Hilfsprojekt im Scratchpad.
- Nur auf GitHub Actions baubar: Android-Module (app, feature/*, platform/android, ui/*), Lint, Robolectric-Tests. Grund: maven.google.com leitet auf das in dieser Umgebung gesperrte dl.google.com um.
- Nicht prüfbar in dieser Umgebung: Verhalten auf echten Geräten (Latenz, Underruns, Thermik, Touch, Rendering auf dem Bildschirm), Hörtests.

## Zyklen

### Zyklus 0 — Build-Verifikation des Ausgangsstands (CI)

- Methode: CI-Lauf 41960001531 auf `23f7faa` (Android-Build, `check`, Lint).
- Befund (bestätigt durch CI): `feature:workspace:compileDebugKotlin` brach ab — `DiagnosticsScreen.kt` rief die `@Composable`-Funktion `tr()` in einem `onClick`-Handler und in einem `joinToString`-Lambda auf. Lokal nicht auffindbar, weil Compose-Module nur auf CI kompilieren.
- Reparatur: Texte im Composable-Kontext vorab auflösen (`fb319a2`, auf dem Arbeitszweig, weil dort der Build brach). Verifikation: CI-Lauf auf `fb319a2`.
- Lehre für weitere Zyklen: bei Compose-Code gezielt nach Composable-Aufrufen in Nicht-Composable-Lambdas suchen.

### Zyklus 1 — Unabhängige Code-Reviews mit Reproduktionstests (3 parallele Prüfer)

Methode: drei voneinander unabhängige Reviews (Editor/Speicher/ViewModels, Audio/Nebenläufigkeit, Formate/Parser). Jeder Verdacht musste durch einen JUnit-Test in einer Kopie des Repos reproduziert werden; Android-only-Code wurde durch Lesen geprüft und ist entsprechend markiert. Jeder übernommene Test wurde im Repo gegen den Code ohne Fix ausgeführt (rot) und mit Fix (grün).

**Bestätigt und repariert (Test rot → grün):**

| # | Bereich | Defekt | Test |
|---|---|---|---|
| 1 | data/local | Nach Wiederherstellung wegen zerrissener `head.json` überschrieb das nächste Autosave die intakte neuere `revision-N.json` (Datenverlust). Jetzt wird sie als `orphan-*.json` beiseitegelegt. | `StorageRecoveryRegressionTest.corruptHeadRecoveryOverwritesIntactNewestRevision` |
| 2 | data/local | Hinweis „recovered“ wurde mitgespeichert und erschien bei jedem späteren Öffnen. | `…recoveredDiagnosticIsPersistedAndReappearsOnEveryOpen` |
| 3 | core/editor | Pause über einer gebundenen Zielnote scheiterte an hängender Verknüpfung (Delete räumte auf, PutRest nicht). Link-Bereinigung läuft jetzt nach jeder Änderung. | `EditorRegressionTest.restOverTiedNote…` |
| 4 | core/editor | Umstellen Schlagzeug ↔ Klavier ließ die alte Notationsart stehen. | `EditorRegressionTest.retune…` (2) |
| 5 | core/notation | Eingaberaster brach bei 512 Zellen ab (z. B. 64/4 bei 1/64). Grenze jetzt 4096 = längster gültiger Takt. | `EditGridRegressionTest` |
| 6 | core/synth | Cowbell-Stimme endete nie → Audio-Thread ging nie in den Leerlauf (Akku). | `SynthRegressionTest.cowbellVoiceEndsAfterItDecays` |
| 7 | core/synth | Akkordnoten ohne Saitenangabe (Importe, Gitarrenpreset auf Spur ohne Stimmung) kappten sich gegenseitig auf eine Stimme. | `…chordWithoutStringNumbersKeepsAllNotes` |
| 8 | core/synth | Nach Voice-Stealing zeigten Saiten-Maps auf fremde Stimmen; neue Anschläge schnitten die neueste Note ab. | `…voiceStealingDoesNotLeaveStaleStringMapEntries` |
| 9 | core/synth | NaN in Pan/Volume (nur aus Dateien erreichbar) machte die gesamte Ausgabe NaN. | `…nonFiniteTrackPanDoesNotPoisonTheMaster` |
| 10 | core/synth | Sample-Bank mit Loop < 4 Frames führte zu Array-Überlauf im Render-Thread; wird jetzt beim Laden abgelehnt, Loop-Wrap robust. | `…degenerateLoopIsRejectedAtLoadTime` |
| 11 | adapter/audio | Tempoänderung im Pause-Zustand: Wiedergabe setzte im falschen Takt fort. | `AudioRaceRegressionTest.speedChangeWhilePaused…` |
| 12 | adapter/audio | `stop()` wurde vom Poller durch einen veralteten Snapshot rückgängig gemacht (Status PAUSED, Start nicht bei 0). | `…stopIsNotUndoneByThePoller` |
| 13 | adapter/audio | Ein auslaufender Render-Thread schloss die Ausgabe der nächsten Sitzung (stumme Wiedergabe). | `…dyingRenderThreadDoesNotCloseTheNextSession` |
| 14 | adapter/audio | Stimmgerät ließ sich nach Eingabefehler (Gerät entfernt) nicht neu starten. | `…tunerRestartsAfterInputFailure` |

| 15 | adapter/formats | GPIF: eine 1,3-KB-Datei mit einem geteilten, sehr breiten `<Beat>` hing 26 s (O(Referenzen × Kinder) in `XmlElement.child`). | `ImportComplexityRegressionTest.gpifSharedWideBeatIsLinear` |
| 16 | adapter/formats | MIDI: Pitch-Bend-Flut quadratisch in `bendPiece` (128k Bends: 18 s). | `…midiBendFloodIsLinear` |
| 17 | adapter/formats | MusicXML: unaufgelöste Haltebögen durchsuchten die ganze Stimme (20k Noten: 23 s). | `…musicXmlUnresolvedTiesAreLinear` |
| 18 | adapter/formats | MusicXML: Stimmen-Slot-Platzierung quadratisch je Takt (16k Noten: 31 s). | `…musicXmlDenseMeasureIsNotQuadratic` |
| 19 | adapter/formats | MusicXML: zusammengesetzte Taktart lief im Int über und wurde still zu 5/16. | `…compositeTimeSignatureDoesNotOverflow` |

Zusätzlich: Mutations-Fuzzing über alle Importformate (≈39 000 Fälle, 2 s Timeout je Fall: GP3/4/5, GP7-Zip, GPIF-Text, GPX BCFZ/BCFS, MIDI, MusicXML Text/komprimiert) — keine ungefangene Ausnahme, kein Hänger. GP3/4/5-Layout gegen PyGuitarPro abgeglichen, ohne Befund.

**Bestätigt durch Code-Analyse (Android, nur CI-kompiliert) und repariert:**

- `AudioTrackSink`: ein gemeinsames `AudioTimestamp` wurde von Render-, Poller- und Stats-Thread gleichzeitig beschrieben → jetzt ThreadLocal (lock- und allokationsfrei im Render-Pfad).
- `AudioTrackSink.open`/`AndroidPcmOutput.play`: AudioTrack-Leck, wenn `play()`/`write()` wirft; leeres PCM warf ungefangen.
- `WorkspaceViewModel`: Taps auf Drum-Raster und Griffbrett wirkten im Bereichsmodus an der alten Cursorposition; `insertChord` scheiterte still und machte „Letzten Befehl wiederholen“ unbrauchbar. Neue Tests in `AutosaveTest` (laufen auf CI).

**Bewusst nicht geändert (beabsichtigtes Design):** Tonhöhenänderung an einem Ende einer Haltebindung (Bund neu setzen, Transponieren eines Teilbereichs) wird abgelehnt. Der bestehende Test `editingATiedPitchCannotSilentlyBreakItsLink` legt das ausdrücklich fest.

**Unbestätigte Verdachtsmomente (offen, nicht repariert):** GPIF-Fermaten O(Fermaten × Beats) je Takt; GPX/BCFZ reserviert die im Kopf angegebene Größe (≤ 64 MiB) vor dem Dekodieren; GPIF `Octave*12+Tone` ohne Überlaufschutz; GP3-Akkord-Layout (erweitert) nicht gegen Referenz geprüft; getrennt gepostete Host-Kommandos in `installLocked` (Loop-Grenzen bei Tempowechsel im Loop), Poller-Neustart-Fenster in `startPolling`, nicht-volatile Felder `loopFrames`/`rackSignature`, Allokationen bei `Reprogram` im Audio-Thread, Count-in geht bei Reprogram verloren, Legato-Überblendung nutzt `ratioAt(0)`, DrumVoice-Hüllkurvenreste, `persist` bei Abbruch, `atomicWrite` ohne Verzeichnis-fsync, O(alle Projekte) pro Autosave, Settings-Datei neuerer Version wird überschrieben.

CI: Läufe 41962076517 (`dd162d5`) und 41962151320 (`66fde4e`) grün — Android-Build, alle Tests inkl. neuer ViewModel-Tests, Lint. Ergebnis Zyklus 1: **19 + 5 Defekte bestätigt und repariert** → Zyklus zählt nicht als defektfrei.

### Zyklus 2 — Eigenschaftsbasierte Tests, Round-Trips, Plattform-Lebenszyklus

Methoden (anders als Zyklus 1): (a) seed-basierte, modellbasierte Zufallstests: 5000 Befehlsfolgen mit 50–150 Befehlen (≈ 550 000 Editor-Befehle, inkl. ungültiger IDs/Werte/NaN) mit Invarianten (Validator ohne ERROR nach Erfolg, Failure ändert nichts, Undo/Redo exakt, Revision steigt, keine fremde Exception, Auswahl gültig); (b) Round-Trips über 900 generierte Projekte × GP7/MusicXML/MIDI/ASCII und 3626 × natives JSON/Archiv; (c) Playback-Eigenschaften über 12 508 Kompilierungen (Sortierung, Grenzen, Spans, `positionAt∘frameOf`, Vergleich gegen unabhängiges Entfalten); (d) eigene Analyse des Android-Lebenszyklus (Activity-Neuerzeugung, SAF-Ergebnisse, Manifest).

**Invarianten, die gehalten haben:** alle Editor-Invarianten (0 Fehlschläge), nativer Codec (außer NaN, s. u.), Playback-Timing.

**Bestätigt und repariert (Tests rot → grün, `PropertyFindingsRegressionTest`):**

| # | Bereich | Defekt |
|---|---|---|
| 1 | ASCII | Export scheiterte bei jedem neuen Band-Projekt (leere Schlagzeugspur → `List is empty`). |
| 2 | GP7 | Fermate mit Faktor 1,0 kam als 1,5 zurück (Decoder verwarf `Length=0`), ohne Verlustmeldung. |
| 3 | Editor | Takt vor Takt 1 einfügen ließ den neuen Takt 1 ohne Tempo; danach war das Starttempo löschbar und MusicXML-Round-Trip änderte die Dauer. |
| 4 | Editor | `MoveToString` (und Transponieren) konnte eine Haltebindung über zwei Saiten spannen → Wiedergabe ignorierte die Bindung. |
| 5 | Editor | `ToggleTie` band über Pausen hinweg; angezeigt, aber nicht gespielt. |
| 6 | Editor | Sprungmarke auf nicht existierenden Takt wurde angenommen (jetzt abgelehnt); Dal Segno ohne Segno wird als WARNUNG angezeigt (bewusst keine Ablehnung, damit importierte Dateien offen bleiben; die Wiedergabe meldet den Fehler ausdrücklich). |
| 7 | Editor | NaN/∞ in Fermate, Vibrato, Feinstimmung, Drum-Werten und Clip-Gain wurden angenommen → Projekt nicht mehr speicherbar (JSON), Tonhöhen NaN. |

**Bestätigt durch Code-Analyse (Android) und repariert (`7461e59`, CI grün):** Lange Exporte (WAV/Stems/PDF/Bytes) liefen im Composition-Scope und wurden bei Bildschirmdrehung abgebrochen (abgeschnittene Datei); offene Exportaufträge lagen in `remember`-State, sodass eine Drehung bei geöffnetem Dateidialog den Auftrag verwarf (leere Datei). Jetzt App-Scope und Container-Zustand.

**Widerlegt:** GPIF-Fermaten O(Fermaten × Beats) — 100 000 Fermaten × 4096 Beats bleiben auch im alten Code unter dem Zeitlimit; Änderung zurückgenommen. Manifest: nur Launcher-Activity exportiert, Backup aus, keine Intent-Filter für Dateien — ohne Befund. `stressTest` ersetzt das Projekt im Transport nur vorübergehend; `togglePlay` bereitet das eigene Projekt neu vor — ohne Befund.

**Bekannt, nicht geändert (Design oder geringe Relevanz, dokumentiert):** Einfügen von Takten auf eine Spur mit weniger Saiten verwirft Noten auf fehlenden Saiten (beabsichtigte Saitenverschiebung, Test `pasteBetweenSixAndSevenStringTracksShiftsStrings`) — es fehlt nur ein Hinweis. Nicht gemeldete Kleinverluste: MusicXML fasst Pedal-Hi-Hat 37/46 zu 44 zusammen, Spurname „Bass“ überschreibt Gitarrenprogramm, Triolen-Export erzeugt unnötige `duration_mismatch`-Hinweise, DEAD/PULL_OFF auf Drum-Noten fallen weg; GP7 macht aus reinen Velocity-Änderungen `Dynamic`-Artikulationen; MIDI führt zwei Drum-Spuren zusammen. `SetRepeat` mit Alternativenden, die alle Takte ausschließen, wird von der Wiedergabe ausdrücklich abgelehnt (beabsichtigt, `RouteCompilerTest`).

Ergebnis Zyklus 2: **7 + 2 Defekte bestätigt und repariert** → nicht defektfrei.
