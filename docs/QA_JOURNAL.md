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

**Bestätigt durch Code-Analyse (Android, nur CI-kompiliert) und repariert:**

- `AudioTrackSink`: ein gemeinsames `AudioTimestamp` wurde von Render-, Poller- und Stats-Thread gleichzeitig beschrieben → jetzt ThreadLocal (lock- und allokationsfrei im Render-Pfad).
- `AudioTrackSink.open`/`AndroidPcmOutput.play`: AudioTrack-Leck, wenn `play()`/`write()` wirft; leeres PCM warf ungefangen.
- `WorkspaceViewModel`: Taps auf Drum-Raster und Griffbrett wirkten im Bereichsmodus an der alten Cursorposition; `insertChord` scheiterte still und machte „Letzten Befehl wiederholen“ unbrauchbar. Neue Tests in `AutosaveTest` (laufen auf CI).

**Bewusst nicht geändert (beabsichtigtes Design):** Tonhöhenänderung an einem Ende einer Haltebindung (Bund neu setzen, Transponieren eines Teilbereichs) wird abgelehnt. Der bestehende Test `editingATiedPitchCannotSilentlyBreakItsLink` legt das ausdrücklich fest.

**Unbestätigte Verdachtsmomente (offen, nicht repariert):** getrennt gepostete Host-Kommandos in `installLocked` (Loop-Grenzen bei Tempowechsel im Loop), Poller-Neustart-Fenster in `startPolling`, nicht-volatile Felder `loopFrames`/`rackSignature`, Allokationen bei `Reprogram` im Audio-Thread, Count-in geht bei Reprogram verloren, Legato-Überblendung nutzt `ratioAt(0)`, DrumVoice-Hüllkurvenreste, `persist` bei Abbruch, `atomicWrite` ohne Verzeichnis-fsync, O(alle Projekte) pro Autosave, Settings-Datei neuerer Version wird überschrieben.
