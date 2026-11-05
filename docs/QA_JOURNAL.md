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

**Audio: Messung und Stresstest (Teil von Zyklus 2, `AudioStressRegressionTest`, rot → grün):**

| # | Defekt |
|---|---|
| A1 | Riesige, aber endliche Tonhöhe (ungeprüfte `cents` in einer Datei, z. B. `Pitch(60, 100000)`) ließ den Render-Thread der Sample-Stimme endlos hängen. Ursache war der Loop-Wrap aus der Reparatur in Zyklus 1 (`while`-Schleife bei Positionen um 1e24). Jetzt: Schrittweite begrenzt, Wrap per Modulo, Compiler begrenzt die klingende Tonhöhe auf −24…151. |
| A2 | Ein nicht-endlicher Notenparameter (NaN-Vibrato, Kurve, Tonhöhe, Drum-Gain/Pan) machte den ganzen Mix sekundenlang NaN und den Kanal dauerhaft unbrauchbar. Compiler ersetzt NaN-Vibrato, Engine überspringt solche Noten. |
| A3 | Tempoänderung im Loop: Reprogram und Loop wurden getrennt gepostet. Ein Block dazwischen sprang zum alten Loop-Start und zählte einen falschen Trainer-Durchgang (statistisch 10 von 300). Reprogram trägt jetzt die Loop-Grenzen. |
| A4 | Tempoänderung oder Bearbeitung während des Einzählens brach das Einzählen ab. |
| A5 | (gering) Allokation je `play()` durch ein Default-`LongArray(0)` → gemeinsame Konstante. Restallokationen bei `Reprogram` (≈ 120 B, Positionsabbildung) und `Preview` (≈ 12 B, geboxtes `Long?`) bleiben bewusst; beide laufen nur auf Benutzeraktion. |

Messwerte (Desktop-JVM, nicht Telefon): 0 Byte Allokation pro 10 000 Render-Blöcke über alle Presets, Effektarten, Cabinet-Modelle, Clips, Preview, Seek, Loop, Rack-Tausch (auch mit `-XX:-DoEscapeAnalysis`). Extreme Effektparameter (0, ±1e9, NaN, ±∞) und Noten (MIDI 0/127, Bends ±4800, 1000-Noten-Akkorde, Tempo 1e-9…1e300 BPM) liefern endliche Ausgabe ≤ 0,73. 10 simulierte Minuten 8-Spur-Stresstest: Median 0,48–0,55 ms, P99 ≈ 0,8–1,06 ms bei 4 ms Budget, ≤ 38 Stimmen, Heap ohne Wachstum, nach Pause 0 Stimmen. 30-s-Nebenläufigkeitstest (6 Coroutinen, 13 424 Operationen, echter Render-Thread): 0 Ausnahmen, kein Deadlock, konsistenter Endzustand. Verdacht (b) „Poller geht beim Neustart verloren“ in 592 Versuchen nicht reproduziert (theoretisch µs-Fenster, nicht geändert).

Ergebnis Zyklus 2: **7 + 2 + 5 Defekte bestätigt und repariert** → nicht defektfrei.

### Zyklus 3 — Fehlerinjektion/Absturzkonsistenz (Speicher) und geometrische Eigenschaften (Notensatz)

Methoden (neu gegenüber Zyklus 1–2): (a) simulierte Abstürze an jedem Schritt von `save()` (18 Zustände + 150 zufällige Absturzketten mit 1180 Prüfungen), echtes ENOSPC auf einem 2-MiB-tmpfs und EROFS-Remount, 16 parallele Coroutinen mit 6400 Operationen, 45 feindliche Archive; (b) geometrische Invarianten über 24 530 Layouts aus 1000 generierten Projekten (alle Stile, Breiten, Raster, Stimmen) und 1000 SVG-Exporte, Kollisionsprüfung von Textglyphen mit Screenshots, Skalierungsmessungen (100–10 000 Takte); (c) eigene statische Suche nach riskanten Mustern in Android-Code (`!!`, `first()`, Ressourcenfreigabe).

**Bestätigt und repariert — Speicher (`StorageFaultRegressionTest`, rot → grün):**
1. Einstellungen: Datei einer neueren Version (unbekannter Schlüssel/Enum) oder abgeschnittene Datei → Laden scheiterte, der nächste Schalter überschrieb alle Einstellungen mit Standardwerten. Jetzt vorwärtskompatibel; unlesbare Datei wird als `settings.json.unreadable-*` aufbewahrt.
2. Beschädigtes Asset auf der Platte ließ sich nie reparieren und blockierte den Import gültiger Archive.
3. 252-KiB-Archiv („Deflate-Bombe“, 4 × 63 MiB) blähte vor jeder Prüfung 252 MiB auf; bei ≤ 320 MiB Heap `OutOfMemoryError` außerhalb von `Outcome` (Absturz). Jetzt Manifest zuerst, Assets nur bis zur deklarierten Größe.
4. Jeder Autosave las und validierte die gesamte Bibliothek unter dem Repository-Lock (21 Projekte: 1,3 s auf dem Desktop); Revisionen wurden doppelt dekodiert.
5. (gering) Snapshot nach Löschen erzeugte ein kaputtes Projektverzeichnis; eine zerrissene `.meta.json` versteckte alle Snapshots; abgeschnittenes Archiv wurde als IO statt CORRUPT gemeldet.

**Bestätigt und repariert — Notensatz/Rendering (`RenderingRegressionTest`, `LaneLabelRegressionTest`, rot → grün):**
1. Haltebögen machten das Layout quadratisch (4000 gebundene Takte 5,7 s, 10 000: 27–39 s; Relayout bei jeder Eingabe).
2. Gestauchte Systeme: Wiederholungs-Start schob Spalten hinter den Taktstrich (nicht antippbar); Takt- und Tonart überdeckten TAB-Schlüssel und Notenschlüssel. Feste Elemente werden jetzt nicht mehr gestaucht.
3. Mehrere Sprungmarken in einem Takt lagen exakt übereinander; Volta-Nummer lag auf Abschnitts- und Tempolabel; Labels der Kopfzeile liefen in schmalen Takten in den Nachbartakt.
4. SVG mit Steuerzeichen/einzelnen Surrogaten in Titel oder Spurnamen war kein gültiges XML (364 von 1000 Exporten); SVG war schmaler als das Layout (dichte Takte abgeschnitten).
5. PDF-Export quadratisch in der Systemzahl (2000 Takte 8,6 s); Spurüberschrift konnte allein am Seitenende stehen.
6. `ScoreView` zeichnete pro Frame alle Glyphen (nur x-Culling) und filterte für den Playhead alle Spalten → jetzt Index pro System/Takt (Android, durch Analyse belegt, Kern-Index getestet).
7. Playhead lief im letzten Schlag bis zu 2 Einheiten zurück; Tippen auf eine Bundzahl in gestauchten Systemen wählte den Nachbar-Slot.

**Bestätigt durch Code-Analyse (Android) und repariert:** `AudioRecordSource.open` gab das `AudioRecord` nicht frei, wenn `startRecording()` warf.

**Widerlegt / ohne Befund:** Absturzkonsistenz von `save()` in allen 18 Zuständen und 150 Ketten; ENOSPC/EROFS (immer IO-Failure, alter Stand intakt); 44 von 45 feindlichen Archiven typisiert abgelehnt (einzige Annahme: `"schemaVersion": "1"` als String — harmlos); `FileAssetStore` (640 parallele `put`, Pfad-Traversal unmöglich); Einstellungen bei 200 parallelen Updates konsistent; alle Layout-Koordinaten endlich, Systeme geordnet, jeder Beat hat eine Spalte, Hit-Tests runden korrekt (außer den reparierten Fällen); Escaping von `< & " '`, Emoji, RTL korrekt.

**Bekannt, nicht geändert (dokumentiert):** Zwei `FileProjectRepository`-Instanzen auf demselben Verzeichnis verlieren CONFLICTs (App erzeugt genau eine; Mehrprozess als unverifiziert dokumentiert). Sind beide Heads unlesbar, wird nicht nach intakten `revision-*.json` gesucht (dokumentiertes Verhalten in PROJECT_FORMAT.md). Keine automatische Bereinigung alter Revisionen/Waisen (dokumentiert für Phase 1). Sehr dichte Einzeltakte bleiben breiter als die Seite (Mindeststauchung 0,5; in der App horizontal scrollbar, im PDF abgeschnitten). Sehr hohe Noten (Transponieren +48) werden in Standardnotation über dem System gezeichnet; Bends auf der letzten Note ragen über das Ende hinaus. Einzeltakte mit > 2000 Noten skalieren superlinear (4096 Noten: 0,7 s). Pinch-Zoom-Zeitverhalten in `ScoreView` nicht testbar.

Ergebnis Zyklus 3: **≈ 20 Defekte bestätigt und repariert** → nicht defektfrei. Nächste Zyklen müssen andere Fehlerklassen untersuchen.

### Zyklus 4 — Orakelbasierte Semantik der Wiedergabe und numerische Referenzmessungen (DSP)

Methoden (neu): (a) unabhängiges Referenz-Orakel für Route, Timing und Artikulationen gegen den Compiler: 3000 zufällige Routen (Wiederholungen ×2–4, Volten, D.C./D.S., al Fine/al Coda), 700 Partituren mit 16 316 Notenbeginnen und 3119 Takten, 600 Linien mit Bindungen/Staccato/Let Ring, Handfälle; (b) Genauigkeitsmessungen mit synthetischen Signalen (Stimmgerät, Resampler, WAV-Encoder/-Decoder, Wellenform), 2322 WAV-Header-Varianten und 12 000 mutierte Dateien; (c) eigene Analyse des ViewModel-Lebenszyklus.

**Bestätigt und repariert — Wiedergabe (`PlaybackOracleRegressionTest`, `CountInRegressionTest`, rot → grün):**
1. Volten 1.|2.|3. bzw. 1.3.|2. mit zweitaktigem Körper spielten Takte doppelt (Route `0 1 2 0 1 3 0 1 2 1 3 1 4 5` statt `0 1 2 0 1 3 0 1 4 5`; 1178 von 3000 Zufallsrouten falsch).
2. Swing veränderte die Taktlänge bei 7/8, 3/8, 5/16 (alle Folgetakte verspätet).
3. Bindung mit Hammer-on/Pull-off am Ziel: Folgenote wurde neu angeschlagen.
4. Gebundener Akkord mit Strum am Ziel wurde erneut angeschlagen (5 statt 3 Noten).
5. Tremolo/Triller verloren Bend-Kurve, Vibrato, Drum-Schlagart/Choke/Gain/Pan; mit Let Ring wurde bis zur nächsten Note weitergepickt (17 statt 5 Anschläge); Tremolo-Rate falsch an der Taktgrenze.
6. Flageoletts ignorierten die Feinstimmung des Instruments.
7. Metronom: Auftakte klickten ab Offset 0 mit Downbeat-Akzent; 6/16, 9/16, 12/16 wurden nicht als zusammengesetzte Takte gezählt.
8. Einzähler übernahm die Zeitkarte des ersten Takts (Auftakt → 1 Klick, Fermate/Tempowechsel dehnten ihn). Jetzt ein regulärer Takt im Starttempo.

**Bestätigt und repariert — DSP (`DspRegressionTest`, rot → grün):**
1. Stimmgerät las hohe Töne zu hoch (E6 +10 Cent): Parabel auf der normierten YIN-Kurve. Nach dem Fix alle sauberen Töne B0–E6 bei 44,1/48/96 kHz innerhalb ±2 Cent.
2. Schwaches, ratenabhängiges Anti-Alias-Filter: 5/10/13 kHz erzeugten sichere Fehlanzeigen. Jetzt feste Eckfrequenz und In-Band-Energieschwelle.
3. WAV mit Datengröße 0 (nie gepatchter Header) wurde abgelehnt; `nBlockAlign` ignoriert (24 Bit in 4-Byte-Containern → falsche Werte und Framezahl).
4. Wellenform-Dauer zählte den letzten Teil-Bucket voll.

**Durch Analyse belegt und repariert (Android):** `WorkspaceViewModel.onCleared` startete das Stoppen des Stimmgeräts auf dem bereits abgebrochenen `viewModelScope` → wirkungslos. Test `clearingTheViewModelStopsTheTuner` (CI grün); der Rot-Nachweis beruht auf der Lifecycle-Reihenfolge (Scope wird vor `onCleared` geschlossen), nicht auf einem eigenen Lauf ohne Fix.

**Bewusst nicht geändert (Design/Vertrag):** Linearer Resampler ohne Anti-Alias-Filter (Code und Vertrag sagen ausdrücklich „linear“; gemessen: −1,5 dB bei 10 kHz für 44,1→48, 96→48 faltet 30 kHz unbedämpft zurück). Ein Sinc-Resampler wäre eine Designänderung → offener Punkt. Clips stumm bei Tempo ≠ 1 (C-STRETCH), nur die ersten zwei Kanäle, zweiter Datenblock ignoriert, RF64/RIFX abgelehnt. Nach D.C./D.S. keine Wiederholungen (dokumentiert). 7/8 klickt in Achteln (Test `metronomeFollowsMeterGrouping`). Fehlt ein Tempo in Takt 1, gilt die erste Tempoangabe (nur Importe). Flageolett auf Bund 0 = 12. Bund. Staccato am Bindungsursprung wird nicht verschmolzen (widersprüchliche Notation).

**Offen (bekannt, nicht repariert):** Swing in Auftakten (Raster müsste am Taktende verankert werden). Stimmgerät: synthetische Töne ohne/mit schwachem Grundton bei A5/C6 eine Oktave zu tief (echte Gitarrentöne haben starke Grundtöne); ein „heller“ E6 mit −10 dB Grundton wird durch die neue Energieschwelle nicht mehr angezeigt; reine Sinustöne 2,5–3 kHz (über dem Gitarrenbereich) werden als halbe Frequenz gelesen; Eingaberaten ≤ 22,05 kHz nicht nutzbar (App öffnet immer 48 kHz). Mixdown schneidet Referenzaudio am Partiturende hart ab (Klick, vermutlich beabsichtigt). `Wav.encode` wirft bei NaN (nicht erreichbar).

**Ohne Befund:** Route nach Fix 3000/3000; Timing 16 316 Onsets und 3119 Takte innerhalb 1 Frame; Bindungen/Staccato/Let Ring 600/600; Stimmgerät-Latenz 70–85 ms, keine Anzeigen bei Stille/Rauschen; WAV-Decoder 2322 Varianten bit-exakt, 12 000 Mutationen ohne Ausnahme; WAV-Header aller Bittiefen/Raten/Kanäle korrekt; Clip-Sync sample-genau; Resampler-Chunking identisch zum Einmal-Lauf.

Ergebnis Zyklus 4: **8 + 4 + 1 Defekte bestätigt und repariert** → nicht defektfrei.
