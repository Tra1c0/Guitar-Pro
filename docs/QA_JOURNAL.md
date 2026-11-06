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

### Zyklus 5 — Numerische Referenz der Effekte, Skalierung/Speicher großer Projekte

Methoden (neu): (a) Messung jedes Effekts mit Sinus-Sweeps, Impulsen und Sprüngen gegen seine Parameter und UI-Beschriftungen (Frequenzgang, statische Kennlinien, Zeitkonstanten, RT60, Aliasing per FFT/Goertzel), Mix-Gesetze; (b) Laufzeit-, Allokations- und Heap-Messungen mit großen Projekten (8 Spuren × 200/500/1000 Takte = 30k/75k/150k Noten, mit 256/512-MB-Heap), Durchsatz aller Ex-/Importe; (c) Review der restlichen Feature-Logik (Bibliothek, Einstellungen, Übungs-, Mixer- und Effekt-Bildschirme), Lokalisierung (de/en vollständig) und Barrierefreiheit (Icons, Touch-Ziele) — letztere ohne Befund.

**Bestätigt und repariert — Effekte (`EffectsRegressionTest`, rot → grün):**
1. Delay- und Hall-Fahnen wurden 6 s nach der letzten Stimme hart abgeschnitten, der eingefrorene Zustand kam bei der nächsten Note zurück. Jetzt Abschaltung erst nach 3 s stillem Ausgang, danach Effekt-Reset.
2. Jede Effekt-Parameteränderung beim Abspielen schnitt alle klingenden Noten aller Spuren ab (Rack-Tausch mit `releaseAll`). Jetzt bleiben Stimmen und Zustand unveränderter Effekte erhalten.
3. Master-Limiter sättigte jedes Sample und erreichte nur 0,73 statt der dokumentierten Grenze 0,94 (−24 dB 3. Harmonische bei Vollaussteuerung). Jetzt transparent bis 80 % der Grenze, weiches Knie darüber.
4. AMP-Gain sprang bei 0,30 um bis zu 4,4 dB (Zuschalten der 2. Stufe). Jetzt Überblendung 0,25–0,35.
5. OVERDRIVE ohne Oversampling: Rückfaltungen bis −10…−18 dB. Jetzt 4× überabgetastet wie der Amp.
6. Effekt-Slider zeigten für nicht gesetzte Parameter den Bereichsanfang statt des DSP-Standardwerts (EQ-Mitte −12 statt 0 dB); Kompressor-Slider reichte unter den DSP-Bereich. Gemeinsame Tabelle `EffectDefaults`.

**Bestätigt und repariert — Skalierung (`HistorySharingRegressionTest`, `CompactStorageRegressionTest`, `PracticeRegressionTest`, rot → grün):**
1. **Regression aus Zyklus 1:** die Link-Bereinigung nach jeder Änderung kopierte den ganzen Partiturbaum → 200 Undo-Schritte hielten 566 MB (200 Takte), bei 500 Takten `OutOfMemoryError`. Jetzt identitätserhaltend (Structural Sharing), keine Kopie ohne hängende Links; Saiten-Prüfung nur für geänderte Takte; Undo/Redo nutzen gespeicherte Diagnosen.
2. Projekt-JSON mit Pretty-Print: ~2 KB pro Note → ab ~32k Noten über dem 64-MiB-Limit; 500-Takt-Projekte nicht speicherbar, GP-Importe solcher Größe gingen verloren. Jetzt kompakt (alte Dateien laden weiter).
3. Übungsschleife ging nach Einfügen/Löschen von Takten still verloren, während die UI sie noch anzeigte; Speed-Trainer wurde von jeder Cursorbewegung zurückgesetzt und sein Tempo nicht angezeigt; jede Cursorbewegung beim Abspielen kompilierte die ganze Partitur neu.
4. (Android, Analyse) „Cursor folgt der Wiedergabe“ wirkte auf dem Übungsbildschirm nicht; Loop-Label zeigte Routenpositionen statt Taktnummern.

**Bewusst nicht geändert / Einschränkungen (dokumentiert):** Volume-Gesetz v²/0,64 (0,8 = 0 dB), konstante Leistung beim Pan; Drum-Element-Pan linear (inkonsistent, gering). Cabinet-Latenz 64 Samples, im Mix nicht kompensiert (Kammfilter bei gedoppelten Spuren mit/ohne Box). Kompressor: Spitzenwert-Detektor mit hartem Knie, Attack auf Sinus ~1,8× länger als der Parameter. Delay-/Chorus-Mixgesetz nie ganz „wet“; keine BPM-Synchronisation. Limiter nur Sample-Peak (kein True Peak). Amp-DC ≈ −35 dB, Rest-Aliasing 19–23 kHz −26…−38 dB. Gate-Hysterese fest. Rack-Tausch vergleicht Effekt-Einheiten auf dem Audio-Thread (kleine Allokation nur bei Benutzeränderung). **Offen:** Jede Bearbeitung validiert weiterhin das ganze Projekt synchron auf dem UI-Thread (500 Takte: PutNote ~170 ms, linear, nicht quadratisch); `insertChord` validiert pro Saite. Eine Verlagerung vom UI-Thread oder inkrementelle Validierung wäre eine Architekturänderung. Die Bibliothek legt das Original-Asset vor dem Speichern ab (bleibt bei Fehlschlag liegen, per Hash dedupliziert). Preset-Wechsel erzeugt zwei Undo-Schritte. `CursorMode` im Modell ist ungenutzt.

**Ohne Befund:** EQ (±0,00 dB an der Mittenfrequenz, Shelves korrekt), Filter, Kompressor unterhalb der Schwelle, Gate, Delay-Timing, Reverb-RT60/Dekorrelation, Chorus, Cabinet-Normierung, Solo/Mute, rampenförmige Gain-Änderungen, Metronompegel; `EngineProgram.prepare` 2 ms; GP7/MusicXML/MIDI/ASCII-Ex- und Importe linear; Autosave läuft abseits des UI-Threads und ist konfliert; Dateinamen-Bereinigung; Lokalisierung de/en vollständig.

Ergebnis Zyklus 5: **6 + 4 Defekte bestätigt und repariert** (davon eine Regression aus Zyklus 1) → nicht defektfrei.

### Zyklus 6 — Adversariale Prüfung der eigenen Reparaturen (23f7faa..HEAD) und UI-Verhaltenstests

Methoden (neu): (a) unabhängiges Review aller Änderungen des QA-Zweigs auf eingeführte Fehler, jede Vermutung per Test **auf HEAD (rot) und auf dem Ausgangsstand 23f7faa (grün)** belegt; (b) neue Compose/Robolectric-UI-Tests auf CI: Neuerzeugung der Activity mit offenem Projekt und Bearbeitungen, Erreichbarkeit der Diagnose-Seite (CI-Lauf 41978848129 grün).

**Bestätigte Regressionen aus früheren Zyklen, repariert (`FixReview*Test`, rot → grün):**
1. Tremolo (Zyklus 4): Anschläge liefen über eine spätere Note derselben Saite (Stimme 2) hinweg.
2. Metronom (Zyklus 4): jeder kurze Takt wurde als Auftakt behandelt → spätere kurze Takte (Importe) verloren den Downbeat-Akzent. Jetzt nur der erste Takt.
3. Bindungs-/Saitenprüfung (Zyklus 2): Transponieren der ganzen Spur bzw. Umstimmen mit „Klang behalten“ wurde bei Akkorden mit Bindungen abgelehnt. Prüfung jetzt nur für Einzelnoten-Befehle (MoveToString, PutNote). **Bekannt:** Spurweites Transponieren kann eine Bindung wie vor der QA auf zwei Saiten verteilen (die Platzierung arbeitet pro Beat).
4. Speed-Trainer (Zyklus 5): startete nach Stopp nicht wieder beim Starttempo.
5. AMP (Zyklus 5): Die Stufen-Überblendung machte das Preset „Bass · Pick“ (Gain 0,3) 5 dB leiser und verzerrter. Zwei weitere Varianten (Überblendung der fertigen Ausgänge, lineare Überblendung im Fenster 0,3–0,4) wurden gemessen und verworfen: Die Koppelstufe der zweiten Stufe dreht bei tiefen Frequenzen die Phase, beide Varianten senken den Pegel um bis zu 4 dB. **Änderung vollständig zurückgenommen**; der Sprung bei Gain 0,3 ist wieder ein offener Punkt (bräuchte ein Stufen-Redesign).
6. Effekt-`reset()` (Zyklus 5) allokierte auf dem Audio-Thread (Listenverkettungen) → Schleifen.

**Widerlegt (mit Belegen):** EffectDefaults deckungsgleich mit `Effects.create`; Limiter-Änderung beeinflusst die Sample-Kalibrierung nicht (relativer Vergleich durch denselben Limiter); Rack-Effekt-Wiederverwendung nur bei identischer Einheit (ID) und Stimmsignatur; Archiv „Manifest zuerst“ entspricht dem eigenen Encoder seit jeher; XML-Kindindex, MusicXML-Slot-TreeMap, Tie-Bisektion und MIDI-Bend-Slicing semantisch äquivalent; RouteCompiler-Änderung für weitere Volten-Formen korrekt; Swing-Teilzelle; Einzähler-Tempo; kein Deadlock beim `RealtimeHost.start`-Join; Undo-Diagnosen; ToggleTie-Angrenzung mit Tuplets/Auftakten; Bibliotheks-Summary; System-Bucketing in PDF/ScoreView; NaN-Grenzen umfassen die Modell-Standardwerte.

**Unbelegt, geringes Risiko:** WAV mit zu kleinem `blockAlign` (defekte Schreiber) wird jetzt als CORRUPT abgelehnt statt dekodiert; ein Export-Hinweis nach Drehung landet im alten Compose-State und ist nicht sichtbar (die Datei wird trotzdem vollständig geschrieben).

Ergebnis Zyklus 6: **6 Regressionen bestätigt und repariert** (eine davon durch Rücknahme) → nicht defektfrei. Lehre: Jede Reparatur an Klang und Timing braucht einen Vorher/Nachher-Vergleich der ausgelieferten Presets und Beispiele, nicht nur einen Test des gemeldeten Falls.

### Zyklus 7 — Differentieller Golden-Output-Vergleich Ausgangsstand (23f7faa) gegen HEAD

Methode (neu): identischer Dump-Test in beiden Versionen über alle Fixtures, Werksprojekte, Stress- und Demo-Projekt und je ein Projekt pro Preset: Offline-Renderings (mit/ohne Sample-Bank, mit/ohne Master-FX; RMS, Peak, Hüllkurve, Oktavbänder, Differenz), Playback-Pläne, Layouts und SVGs, alle Exporte mit Re-Import, alle Importer × alle Fixtures (156 Kombinationen), Stimmgerät, WAV-Decoder. Jede Abweichung musste einem Journal-Eintrag zugeordnet werden; zusätzlich wurde die Zuordnung im Audio per gezielter Rücknahme einzelner Änderungen bestätigt (ohne die Voice-Map-Korrektur sind alle Renderings ohne Master-FX bitidentisch).

**Ergebnis: keine unbeabsichtigte Abweichung.** Identisch: alle 156 Importergebnisse inkl. Verlustlisten, 48 Playback-Pläne, alle GP7-/MusicXML-Exporte, Keys-/Drum-Renderings ohne Master-FX, WAV-Dekodierung.

**Neu bestätigt und repariert (rot → grün):**
1. Limiter aus Zyklus 5: Das Gain-Ziel lag bei 1,2 × Grenze; 10 % eines dichten Mixes liefen durch das Knie und unter Dauerübersteuerung war der Klirrfaktor 1,6 dB *höher* als vorher (−22,7 statt −24,3 dB). Ziel jetzt = Grenze: Spitzen ≈ 0,89, 3. Harmonische < −30 dB (`EffectsRegressionTest.limiterIs…`).
2. Effekt-Linien (Let Ring, P.M.) auf dem letzten Schlag eines Takts endeten eine Spaltenbreite hinter dem Taktstrich — schon im Ausgangsstand (dort +53 Einheiten auf GP-Spur 1); durch die feste Breite der Vorspann-Elemente jetzt auch in GP5-Fixtures sichtbar (`LetRingLaneRegressionTest`).

**Beabsichtigte Unterschiede, größer als zuvor beschrieben:** Die Voice-Map-Korrektur (Zyklus 1 #8) betraf nicht nur Voice-Stealing, sondern jeden Akkord, der eine gerade wiederverwendete Stimme traf: Der Schlussakkord des Vergleichsriffs ist jetzt 1–8 dB lauter (er wurde vorher teilweise abgeschnitten). Master-FX-Renderings sind durch den Limiter 0,2–2,2 dB lauter (Spitzen −2,8 → −0,6 dBFS vor der Korrektur oben). Stimmgerät: oberhalb E6 (1,7–2,1 kHz, außerhalb des Gitarrenbereichs) teils ungenauer (bis −11 Cent), im Gitarrenbereich genauer. Tabelle aller beabsichtigten Unterschiede: Swing-7/8-Takt 88 000 → 84 000 Frames (GP4-Fixture), Tremolo-Kurven, MIDI-Export (eine 1-Tick-Note weniger, neuer Verlusteintrag), Layout-Breiten/Spaltenpositionen ohne Änderung der Glyph- oder Systemzahl, ASCII-Export des Werksprojekts, kompaktes Native-JSON (27,6 → 5,1 MB, Re-Import identisch).

Ergebnis Zyklus 7: **2 Defekte bestätigt und repariert** (einer aus Zyklus 5, einer vorbestehend) → nicht defektfrei.

### Zyklus 8 — Tests gegen reale Dateien Dritter (Korpus)

Methode (neu): öffentliche Testkorpora, nur lokal verwendet, nicht im Repo: alphaTab `test-data` (Commit 7d3efe78; GP3/4/5, GPX, GP7/8, MusicXML), W3C `musicxml` (ece89abc), jazz-soft `test-midi-files` (ee79d9e3, MIT). 674 Dateien, ≈ 6000 Importerläufe: Erkennung, Import, Validierung, Wiedergabe, Layout, SVG, Ex-/Re-Import; Inhaltsprüfungen gegen die Erwartungen der alphaTab-Tests (102 Prüfungen), unabhängiger MusicXML-DOM-Parser als Orakel (250 Dateien), Vergleich desselben Stücks in mehreren Formaten (121 Paare), MIDI gegen die Generator-Skripte.

**Bestätigt und repariert (`CorpusFindingsRegressionTest`, `UnfrettedLayoutScalingRegressionTest`; synthetische Repros ohne Fremddaten, rot → grün):**
1. **200 von 208 echten GP7/8-Dateien wurden als „Beschädigtes ZIP-Archiv“ abgelehnt** — Guitar Pro schreibt Datenbeschreiber ohne Signatur (crc, unkomprimiert, komprimiert), an denen `ZipInputStream` scheitert. Jetzt Lesen über das zentrale Verzeichnis (mit CRC-/Größenprüfung und denselben Budgets), Rückfall auf die lokale Header-Kette für Archive ohne Verzeichnis. Nachkontrolle am Korpus: **208/208**.
2. MusicXML: negativer `<offset sound="yes">` ließ die ganze Datei scheitern (4 Werke in .xml und .mxl).
3. MusicXML: `<fret>` ohne `<string>` (oder umgekehrt) erzeugte eine ungültige Note → ganze Datei abgelehnt.
4. GPIF-Akkorddiagramme ignorierten `baseFret` (D-Griff im 5. Bund kam als 1,3,3,3,1), ohne Verlustmeldung.
5. MusicXML-Bends: Release ohne vorherigen Bend ging unter die Grundtonhöhe; mehrere Bends ohne Positionsangabe fielen auf denselben Zeitpunkt.
6. Layout unbundierter Spuren (Klavier, die meisten MIDI-/MusicXML-Importe) quadratisch: Notenschlüssel aus allen Noten pro Takt neu berechnet (1734-Takt-MIDI: 32 s pro Layout).
7. GP 7.0 wurde wegen leerer `<AudioTracks></AudioTracks>` als GP8 erkannt (mit falscher Verlustmeldung); rohes GPIF-XML wurde als GP7 erkannt, der Import scheiterte dann mit „Keine ZIP-Datei“ (jetzt klare Meldung).

**Korpus-Ergebnis nach den Fixes:** GP3 18/18, GP4 23/23, GP5 56/56, GPX 37/37, GP7/8 208/208, MusicXML .xml 195/198 (Katalogdatei, zwei Teile ohne `<divisions>`), .musicxml 31/33 (W3C-Fragmente ohne `<divisions>`), .mxl 19/19, MIDI 58/76 (18 typisierte Ablehnungen: Typ 2, illegale Statusbytes, leere/abgeschnittene Dateien — laut Korpus selbst ungültig). Kein Absturz, kein Hänger, jedes erfolgreiche Importergebnis valide.

**Nicht geändert (dokumentiert):** TuxGuitar-Dead-Notes (`<play><mute>straight</mute>` bzw. x-Notenkopf auf der zusammengeführten Notationszeile) verlieren DEAD ohne Verlustmeldung — herstellerspezifisch und mehrdeutig („straight“ ist in MusicXML ein Blechbläser-Dämpfer). MIDI-Bank in MusicXML, Tonhöhen-Note in Schlagzeugpart (→ Drum 60) und untypisierter `<creator>` ohne Verlustmeldung (gering). Volten-Lesart bei „repeat-close-alternate-endings“ weicht von alphaTab ab (Takt ohne Klammer wird in jedem Durchgang gespielt — Standardlesart, nicht als Defekt gewertet). Übervolle GP-Takte werden mit Verlustmeldung gekürzt; GP6-Drum-Element/Variation mit Verlustmeldung verworfen. MusicXML-Teile ohne `<divisions>` werden typisiert abgelehnt (alphaTab erbt sie vom vorigen Teil).

Ergebnis Zyklus 8: **7 Defektklassen (9 Einzelbefunde) bestätigt und repariert** → nicht defektfrei.

### Zyklus 9 — Export-Konformität gegen unabhängige Prüfer

Methode (neu): 984 Projekte (Fixtures, Werks-/Stress-Projekte, 27 handgebaute Feature-Projekte, 300 generierte, 643 importierte Korpusdateien) in alle Formate exportiert und mit **fremden** Werkzeugen geprüft: MusicXML gegen das offizielle 4.0-XSD (lokal, ohne DTD-Abruf) plus semantische Regeln (Dauern pro Stimme/Takt, Tie-/Tuplet-Paare, TAB-Tonhöhe = Stimmung + Kapo + Bund); MIDI mit `mido` und eigenem SMF-Scanner (Onsets gegen den Playback-Plan ±1 ms, Note-on/off-Paare, RPN vor Bends, Kanal 10); WAV mit Pythons `wave`/`soundfile`; GP7 mit `zipfile` und ElementTree (CRC, referenzielle Integrität) sowie den Leseregeln von alphaTabs `GpifParser`; ASCII-Ausrichtung; SVG mit lxml.

**Bestätigt und repariert (`ExportConformanceRegressionTest`, rot → grün):**
1. MusicXML: Pausen in Lücken wurden in Teilwerte zerlegt, deren Raster `divisions` nicht abdeckte → `<duration>0</duration>` (XSD-Fehler, 21 Dateien).
2. XML-Writer ließ U+FFFE/U+FFFF und einzelne Surrogate durch, CDATA filterte gar nicht → MusicXML/GP7 nicht wohlgeformt (u. a. Steuerzeichen aus GP3–5-Titeln).
3. GP7: Vorschlagsnoten auf unbundierten Spuren nur mit `<Midi>`, ohne Octave/Tone → von alphaTab/GP als Tonhöhe 0 gelesen (17 echte Korpusdateien).
4. GP7: Haupt-Kapo war der häufigste statt der niedrigste Wert → Teilkapo unter dem Hauptkapo, eine Saite 2 Halbtöne zu hoch.
5. MIDI: drei gleiche Tonhöhen auf einem Kanal → doppeltes Note-on und verwaistes Note-off.
6. MusicXML verwarf weitere Vorschläge einer Note still → jetzt Verlustmeldung `grace_multiple`.

**Bewusst nicht geändert / Einschränkungen:** GP7 füllt die Lücke am Stimmende nicht mit Pausen (vom bestehenden `GpifRoundTripTest` so festgelegt; Guitar Pro markiert solche Takte evtl. als unvollständig). MIDI-Zeiten von Trillern/Tremolo/Vorschlägen in Swing-Takten weichen bis 83 ms vom Playback ab (Ornamente auf dem geraden Tick-Raster berechnet). 32-Bit-Float-WAV ohne `cbSize`/`fact`-Chunk (alle getesteten Leser akzeptieren es; der 44-Byte-Header ist im Code an mehreren Stellen vorausgesetzt). 24-Bit-WAV als `WAVE_FORMAT_PCM` statt `EXTENSIBLE` (verbreitet akzeptiert). alphaTab ignoriert `SecondaryTuplet` und `PartialCapoFret` (Leser-Einschränkung). Ein ungeklärter Fall (Seed 60): zwei gleiche Noten mit Rasgueado auf einem 7/320-Beat werden im MIDI zusammengefasst, im Playback nicht.

**Ohne Befund:** XSD 979/984 (die 5 Fälle oben), MIDI 959/959 parsebar, Onsets 955/959 innerhalb 1 ms (Rest: Swing-Ornamente), GP7-ZIP 984/984 sauber, referenzielle Integrität überall, ASCII 984/984, SVG 502/502, WAV 86/86 identisch mit `soundfile`.

Ergebnis Zyklus 9: **6 Defekte bestätigt und repariert** → nicht defektfrei.

### Zyklus 10 — Spezifikationstreue und UI unter Gerätekonfigurationen

Methoden (neu): (a) ≈ 150 überprüfbare Aussagen aus CONTRACTS, PROJECT_FORMAT, ARCHITECTURE, VALIDATION, README, features.json, KDoc und UI-Texten gegen das Verhalten getestet, Grenzen jeweils genau, ±1; (b) Robolectric-Matrix auf CI für den Hauptablauf (Projekt anlegen, Note setzen, Üben, „Mehr“/Seitenleiste, Diagnose) in Englisch, 320-dp-Telefon, Schriftgröße 2,0, Querformat (Seitenleiste ab 840 dp) und Dunkelmodus — alle grün (CI-Lauf 41994140082).

**Bestätigt und repariert (rot → grün bzw. CI):**
1. WAV-Export über 4 GiB (lange Routen, z. B. ×32-Wiederholungen) warf eine Exception statt `RESOURCE_LIMIT` — im App-Export auf dem Hintergrund-Scope vermutlich ein Absturz (`WavLimitRegressionTest`).
2. „Letztes Projekt öffnen“ / „Weiter mit“ öffneten das alphabetisch erste Projekt; Projektzusammenfassungen tragen jetzt die Speicherzeit (`RecentProjectRegressionTest`).
3. Mit Hardware-Tastatur fingen Editor-Kürzel Eingaben in Textfeldern ab (Leertaste startete die Wiedergabe statt ein Leerzeichen zu schreiben; Strg+Z/C/V wirkten auf die Partitur) — Kürzel ruhen jetzt, solange ein Textfeld fokussiert ist (`hardwareKeysInATextFieldTypeTextInsteadOfRunningShortcuts`, CI grün).
4. Übungstempo erreichte über „+5 %“ 200 %, dokumentiert sind 25–150 %.

**Dokumentation korrigiert (Code war richtig):** README/ARCHITECTURE nennen die mitgelieferte CC0-Gitarrenbank; 14 statt 13 Arbeitsbereiche; VALIDATION behauptet nicht mehr, spätere Commits hätten nur Doku geändert (VERIFICATION.json gilt als veraltet bis zur Neuerzeugung); PROJECT_FORMAT beschreibt Papierkorb, Waisen-Revisionen und beiseitegelegte Einstellungen; Count-in-Bereich (UI 0–2, Modell 0–4), MiB statt MB, Erkennungs-Ausnahmen (.tef, rohes GPIF); KDoc zu Faltungskosten und Stimmgerät-Allokation; veraltete Hinweistexte (Mixer, Techniken, Layout, Strikte Prüfung — letztere ist tatsächlich noch nicht schaltbar).

**Bekannt, nicht geändert:** Fehlermeldungen bei IO-Fehlern enthalten den app-privaten absoluten Pfad (nur Anzeige, nichts gespeichert). Ein Asset größer als deklariert wird als RESOURCE_LIMIT statt CORRUPT gemeldet (abgelehnt wird es korrekt). Ein offenes Stimmgerät wird nur beim Verlassen des Bildschirms, nicht bei ON_STOP gestoppt (Android blockiert das Mikrofon für Hintergrund-Apps ohnehin). Abgebrochener/fehlgeschlagener Export hinterlässt eine Teildatei beim Anbieter (in C-DOCUMENT dokumentiert). `ClearBars`, `SetStrum`, `SetVelocity`, `SetSound`, `SetMasterMix` nur indirekt getestet.

**Konform (Auswahl):** alle Größen-/Tiefen-/Anzahlgrenzen (Assets, Archive, Importer, ZIP-Einträge, Routen 20 000), Fehlercodes, Undo-Tiefe genau 200, Leerlauf-Stopp 20 s, 10-Minuten-Clips, Wellenform-Cache 16, keine INTERNET-Berechtigung, keine Pfade/URIs im Modell, Stimmen-Pools, Breakpoints.

Ergebnis Zyklus 10: **4 Code-Defekte + 9 Doku-Defekte bestätigt und behoben** → nicht defektfrei.

### Zyklus 11 — Adversariale Sicherheitsprüfung und Zustandsmaschinen/Lebenszyklus

Methoden (neu): (a) Angreifer-Sicht auf Manifest, exportierte Komponenten, SAF-Fluss, Archiv-/XML-/Binärleser und persistierte Daten, Speicherverbrauch in Kind-JVMs mit begrenztem Heap (`-Xmx320m`/`-Xmx512m`); (b) vollständige Übergangstabellen von Transport, Stimmgerät, Autosave, Navigation und Exporten, ungewöhnliche Übergänge gezielt angesteuert (blockierender Clip-Loader, Stop/Pause während laufendem `play()`, Doppel-Tap/-Zurück), Prozesstod-Analyse; WorkspaceViewModel lokal über einen Lifecycle-Shim auf der JVM ausgeführt (nur im Scratchpad).

**Bestätigt und repariert (rot → grün, CI 41998323291 und 41999267771 grün):**
1. Präpariertes `.riffproj` (252 KiB, erfundene Hashes, deklarierte 4 × 63 MiB) belegte 769 MiB und warf OutOfMemoryError → project.json muss vor den Assets stehen und wird sofort gegen das Manifest geprüft, deklarierte Größen gedeckelt, jeder Asset-Hash beim Lesen geprüft, `storageOutcome` meldet OOM als RESOURCE_LIMIT (`data/local/.../SecurityReviewRegressionTest`).
2. 16-KB-`.mxl` mit Millionen leerer Elemente bzw. Attributen erschöpfte 512 MiB Heap → Attribute zählen zum Knotenbudget, Budget 1 000 000 (größte Korpusdatei ≈ 87 000), `guarded` fängt OOM (`adapter/formats/.../security/SecurityReviewRegressionTest`).
3. Stop/Pause während ein `play()` noch kompilierte/Referenz-Audio dekodierte wurde überschrieben → Wiedergabe startete trotzdem (auch im Hintergrund nach ON_STOP). Jetzt Halt-Epoche unter Lock.
4. `pause()` im Zustand STOPPED setzte PAUSED; nach jedem Hintergrundwechsel startete Play an der alten Position statt am Cursor und jede Bearbeitung kompilierte neu.
5. Der Diagnose-Stresstest hob externe Pausen (Audiofokus, Kopfhörer gezogen, Hintergrund) binnen 1 s wieder auf → endet jetzt bei externer Pause.
6. Abgelehnter `setLoop` überschrieb die aktive Schleife; ein vor dem Plan geparkter Seek überlebte `stop()` (beide nur API-seitig erreichbar).
7. Doppel-Tap auf ein Projekt öffnete zwei Sitzungen (die untere konnte nie speichern oder verlassen werden); doppeltes Zurück poppte die Bibliothek (leerer Bildschirm) → Navigation nur aus der Bibliothek, `leave()` poppt nur den eigenen Eintrag (`LifecycleQaNavigationTest`, Robolectric, nur CI grün — die Rot-Probe dieses Tests wurde nicht separat auf CI ausgeführt).
8. In-Memory-Export, der nach einer Drehung fertig wird, rief einen abgemeldeten ActivityResultLauncher auf (Absturz) → aktueller Launcher über `PendingExports.launchBytes`, sonst Warteschlange für die nächste Komposition (nur Code-Nachweis, kein Test). Verlorene Stream-/PDF-Jobs nach Prozesstod melden jetzt einen Hinweis statt still eine leere Datei zu hinterlassen.
Tests: `adapter/audio/.../LifecycleQaTest` (7/7 rot ohne Fix), `feature/workspace/.../LifecycleQaViewModelTest`, `data/local/.../LifecycleQaStorageTest` (Autosave gegen Löschen: bestand bereits).

**Ausgeschlossen:** Pfad-Traversal (alle Senken über EntityId/AssetHash/UUID/Revisionsmuster), XXE/Entity-Expansion, exportierte Komponenten (nur Launcher; kein Provider/Service; Receiver NOT_EXPORTED), Intent-Extras, Logging sensibler Daten, Überläufe in GP3-5/MIDI/WAV/ZIP-Offsets, Abbruch-Verschlucken von CancellationException, Undo über Projektwechsel, Snapshot-Revisionsschutz.

**Offen/unbestätigt:** Navigation-Deep-Link-Extras könnten `project/{id}` mit ungültiger ID erreichen (Absturz, kein Datenzugriff; Bibliotheksquelle offline nicht prüfbar). Blockierende Arbeit auf Main: `InputTuner.start/stop`, `RealtimeHost.start` (bis 2 s Join). Prozesstod verliert Cursor, Eingabezustand, Übungs- und Ansichtseinstellungen, Undo und Zwischenablage (vertraglich nicht zugesagt; Musik selbst ist durch ON_STOP-Flush gesichert).

Ergebnis Zyklus 11: **8 Defektgruppen bestätigt und behoben** → nicht defektfrei.

### Pausiert nach Zyklus 11 (auf Nutzerwunsch)

Zyklus 12 (Regressionsprüfung der Fixes aus Zyklus 10/11; Barrierefreiheit/Lokalisierung) wurde begonnen und auf ausdrücklichen Wunsch des Nutzers abgebrochen, um sich auf die benutzbare App zu konzentrieren; Teilergebnisse wurden verworfen. **Abschlussbedingung nicht erfüllt:** kein Zyklus war bisher defektfrei (Zyklen 1–11 fanden jeweils Defekte). Letzter grüner CI-Lauf: 41999267771 (82f8a71).

Bei Wiederaufnahme zuerst offen: (1) Regressionsprüfung der Commits 69a6295, 3bfea04, 82f8a71 (Halt-Lock vs. Mutex, Knotenbudget inkl. Attribute für große GPIF, Reihenfolgepflicht project.json bei älteren Archiven, Navigationsschutz nach Import/„Weiter mit“, PendingExports-Warteschlange, `textEditing`-Fokus); (2) Barrierefreiheit (contentDescription, Touch-Ziele, Canvas-Semantik) und Lokalisierung (hartkodierte Texte, values/values-en-Abgleich, Platzhalter).

**Nie geprüft (keine Hardware in dieser Umgebung):** Klang und Latenz auf Geräten, Unterläufe/Thermik unter Last, echte Audiofokus-/Kopfhörer-Ereignisse, Mikrofon/Stimmgerät mit realem Signal, SAF-Anbieter (Drive, USB), Darstellung auf echter Hardware jenseits Robolectric, Navigation-Deep-Link-Extras, Main-Thread-Blockaden (nur aus Code abgeleitet).

### Zyklus 12 — Regressionsprüfung der Fixes 10/11, Barrierefreiheit/Lokalisierung, Gerätebefund (wieder aufgenommen)

Methoden (neu): (a) adversariale Prüfung jedes Hunks der Fix-Commits 69a6295, 3bfea04, 82f8a71; (b) Barrierefreiheits-/Lokalisierungsaudit (Semantik, Touch-Ziele, Live-Regionen, Ressourcenabgleich values/values-en, hartkodierte Texte); (c) erster **Befund auf einem echten Gerät** durch den Nutzer.

**Bestätigt und repariert:**
1. *Gerät:* Nach Antippen einer Note und Play sprang die Partituransicht hin und her. Ursache: bis der Render-Thread den Play-Befehl verarbeitet hatte, veröffentlichte der Poller die alte Position und kippte PLAYING→PAUSED (Folgen aus/an) — jetzt `starting`-Zustand (`PlayStartRegressionTest`, rot→grün). Zusätzlich holt der Cursor-Effekt die Ansicht beim Folgen nicht mehr zurück, eigenes Wischen pausiert das Folgen 2,5 s, Antippen während der Wiedergabe springt dorthin (Compose, nur CI/Gerät).
2. Regression 82f8a71: Pause direkt nach Play/Fortsetzen ging verloren (veralteter Status) → Pause fragt auch die Engine.
3. Unvollständiger Fix 82f8a71: Stop/Pause während `prepare()` (dort kompiliert togglePlay bei offener Ausgabe) wurde vom folgenden `play()` überstimmt → `prepareEpoch`.
4. Unvollständiger Fix: Stresstest hob eine Pause zwischen zwei Durchläufen auf → `halts`-Zähler.
5. Regression 3bfea04: eigener großer GP7-Export (57 600 Noten) war nicht mehr importierbar → Knotenbudget 3 M (`Qa12XmlBudgetTest`).
6. Regression 69a6295: Bibliotheksliste versteckte das alphabetisch erste Projekt, wenn ein anderes zuletzt gespeichert war (`Qa12LibraryListTest`, Robolectric).
7. Barrierefreiheit: Auswahlzustand nur farblich (Akkordmodus, Palm Mute, Notenwert, Navigation) → `selected`-Semantik; Schalter ohne Namen → ganze Zeile `toggleable`; stummer Speicherstatus/Hinweise → Live-Region; „1 projects/1 tracks“ → Singular (`AccessibilityQaTest`, `LocalizationConsistencyTest`; CI 42001636029 grün).
CI: 42001636029 (369ac0a) grün; 0e2ddda/b7041dc siehe nächster Eintrag.

**Nutzerwunsch umgesetzt (Feature, kein Defekt):** Drag-and-Drop der ausgewählten Note (`EditorCommand.MoveNote`: anderer Beat und/oder Saite, Tonhöhe bleibt, Techniken/Velocity/Fingersatz wandern mit, neuer Beat erhält den gewählten Notenwert, ein Undo-Schritt; belegte Ziele und unspielbare Saiten werden abgelehnt; `MoveNoteTest`). Geste: die bereits ausgewählte Note ziehen; Ziel wird hervorgehoben; Wischen anderswo bleibt Verschieben der Ansicht. Kein Auto-Scroll am Rand; nicht für Schlagzeugspuren.

**Bekannt, nicht geändert:** Einige Bedienelemente sind optisch 44–46 dp (dokumentiert 48 dp); M3 erweitert die Tippfläche vermutlich auf 48 dp, die Raster zu vergrößern würde das Design auf kleinen Displays ändern. Etwa 29 hartkodierte deutsche Meldungen in ViewModels erscheinen auch in der englischen Oberfläche (Englisch ist nicht als unterstützt dokumentiert; CONTRACTS verlangt deutsche Importfehler). `textEditing`-Flag könnte nach Verschwinden eines fokussierten Felds hängen bleiben (unbestätigt).

Ergebnis Zyklus 12: **7 Defektgruppen bestätigt und behoben** → nicht defektfrei.

### Zyklus 13 — Modellbasierte Zufallstests (Transport) und Prüfung des neuesten Codes

Methoden (neu): (a) modellbasierte Zufallstests von EngineTransport/RealtimeHost: geseedete Folgen aus prepare/play/pause/stop/seek/setLoop/Bearbeitung/Projektwechsel/Render/Wartezeit plus Wettläufen (Stop während blockiertem Dekodieren), Referenzmodell und Invarianten nach jedem Schritt, Schrumpfen auf Minimalfolgen (`Qa13ModelBasedTransportTest`; Standard 20 Folgen, Kampagne mit `-Dqa13.n=600`); (b) adversariale Prüfung der Commits 0e2ddda/b7041dc (Drag-and-Drop, Start-Zustand, XML-Budget, Bibliothek); (c) CI-Diagnose der Robolectric-Gestentests.

**Kampagnenverlauf (600 Folgen × 40 Schritte):** 289 fehlerhafte Folgen → 29 → 3 → **0** nach den Reparaturen.

**Bestätigt und repariert:**
1. MoveNote brach Bindebögen und Legato-Verbindungen still (neue Note-ID) → Note behält Identität/Links, gebundene Noten werden abgelehnt; Strum/Fermate wandern mit der einzigen Note (`Qa13MoveNoteReviewTest`).
2. Ein abgebrochenes Ziehen (Systemgeste) ließ die Note fallen; Ziehen pausiert jetzt das Folgen; Notenkopf in der Standardnotation ist ebenfalls greifbar.
3. Halt während eines überlappenden zweiten `prepare()` ging verloren → Zähler laufender Prepares (`Qa13PrepareEpochReviewTest`).
4. XML-Budget 3 M hielt 405 MiB → leere Attribut-Maps/Kindlisten geteilt, Namen interniert: 103 MiB (`Qa13XmlBudgetHeapTest`).
5. Das zuletzt gespeicherte Projekt war nicht löschbar → Liste zeigt alle Projekte (`Qa13LibraryDeleteTest`, Robolectric).
6. Pause direkt nach Stop machte aus STOPPED → PAUSED.
7. Bearbeitung während der Wiedergabe rechnete den Wiedergabekopf per Routenindex statt per Takt um (früheren Takt löschen → einen Takt weiter oder über das Ende) → `PlaybackPlan.frameFor` per Takt.
8. Projektwechsel behielt die Position des alten Projekts; nach Stop startete Play am veralteten Schleifenanfang; veröffentlichter Frame überschritt Schleifen-/Planende um bis zu einen Block.
9. Poller veröffentlichte Frames des alten Plans gegen den neuen → Snapshot zählt angewandte Programme/Play-Befehle; Start-Zustand endet, wenn die Engine Play angewandt hat (kurze Wiedergabe blieb sonst bis 1 s PLAYING).
10. Schleife, die während der Wiedergabe außerhalb des Kopfs gesetzt wurde, griff nie (oder erst spät) → springt an den Schleifenanfang; Suchen außerhalb einer Schleife landet am Schleifenanfang (wie `play()`).
11. Partituransicht scrollte vor der ersten Messung (Viewport 0×0) ungewollt nach unten — Tippen/Ziehen traf dann unterhalb des Sichtbaren (gefunden durch `Qa13DragGestureTest`).
Tests: `Qa13TransportFindingsTest` (7/9 rot vor Fix), `Qa13DragGestureTest` (Robolectric, CI). CI 42012082565 (719f7c1) grün.

**Bekannt, nicht geändert:** Eine Pinch-Geste, deren erster Finger auf der ausgewählten Note schon die Touch-Schwelle überschreitet, startet ein Ziehen statt Zoomen. Kein Auto-Scroll am Rand beim Ziehen. Hit-Test ignoriert die Stimme (Ziehen einer Stimme-0-Note bei gewählter Stimme 1 scheitert mit Hinweis). Tippen im Bereichsmodus während der Wiedergabe springt zur alten Cursorposition. Slide-Ziel einer verschobenen Note zeigt weiter auf den alten Nachbarn (Wiedergabe ignoriert nicht anliegende Slides).

**Nicht ausgeführt:** Fast-Forward von `ccr-0b650dff-jnhups-qa` nach `ccr-0b650dff-jnhups` (vom Nutzer gewünscht) wurde von der Berechtigungsprüfung der Umgebung als „Merge ohne Review“ blockiert; muss der Nutzer selbst ausführen oder freigeben.

Ergebnis Zyklus 13: **11 Defektgruppen bestätigt und behoben** → nicht defektfrei.

### Zyklus 14 — Modellbasierte Zufallstests für Editor-Kern und Absturzkonsistenz des Speichers

Methoden (neu für diese Bereiche): (a) `Qa14ModelBasedEditorTest`: geseedete Folgen aller EditorCommands + select/undo/redo mit Referenzmodell; Invarianten: kein Validator-ERROR nach Erfolg, Fehlschlag ändert nichts, undo/redo exakt, Revision monoton, Auswahl gültig, keine Ausnahme aus `dispatch`, Links/Bindebögen konsistent, JSON-Rundreise, Laufzeit < 200 ms bei 64 Takten — 7 500 Folgen (≈ 288 000 Schritte) vor, 1 500 nach den Reparaturen. (b) `Qa14ModelBasedStorageTest`: Folgen aus create/save (richtige/veraltete Revision)/open/delete/Snapshots/Assets/nativer Export-Import/Einstellungen/gleichzeitig speichern+löschen; **Absturz-Injektion an exakten Schreibpunkten** (SecurityManager-Hook kopiert das Verzeichnis beim k-ten Schreib-/Umbenenn-/Löschzugriff; nur JDK 17) und gezielte Korruption einzelner Dateien; 3 000 Folgen mit ≈ 26 600 Absturzabbildern und 10 450 Korruptionen.

**Bestätigt und repariert (rot → grün, CI 42017063363 grün):**
1. Bearbeitungen auf Beat-Ebene (Beat einfügen am Bindeziel, Notenwert/Triole des Bindeursprungs, Taktart verlängern) öffneten still eine Lücke in einem Haltebogen (Wiedergabe schlägt die Note neu an, GP-Export verwirft den Bogen) → abgelehnt mit „Haltebogen zuerst lösen“.
2. NaN/Unendlich in Master-Panorama, Effektparametern (Spur/Master) und Clip-Filtern wurden angenommen → Projekt nicht mehr speicherbar (JSON).
3. Unpaarige UTF-16-Surrogate in Titel/Metadaten/Texten/Abschnitten/Spurnamen wurden als „?“ gespeichert → abgelehnt.
4. `Replace` mit identischem Inhalt erhöhte die Revision und erzeugte einen leeren Undo-Schritt.
5. Riesige Auswahl-Offsets überliefen später; ein Fehlschlag danach hatte die Redo-Historie schon gelöscht; `redo()` warf. → Offsets begrenzt, Auswahl vor der Historie berechnet, undo/redo fangen ab.
6. Inkonsistente (handgebaute) Zwischenablage und `MoveTrack(Int.MAX_VALUE)` warfen bzw. liefen über.
7. *Speicher:* Absturz beim ersten Speichern eines neuen Projekts hinterließ dauerhaft „Projekt unlesbar“ in der Bibliothek (in 35 % der strikten Folgen) → Ordner ohne Head mit höchstens einer Revision und Temp-Dateien gilt als nie gespeichert (PROJECT_FORMAT ergänzt); Dateien bleiben liegen. Zwei bestehende Tests nutzten einen leeren Ordner als „kaputtes Projekt“ und verwenden jetzt einen beschädigten Head.
Tests: `Qa14EditorFindingsTest` (14 Tests), `Qa14StorageCrashFindingsTest`.

**Gehalten:** alle übrigen Editor-Invarianten (Validator, undo/redo exakt, Auswahl, keine hängenden Links, Laufzeit max. 177 ms bei 64 dichten Takten mit 4 parallelen Läufen); Speicher: dauerhafte Saves, CONFLICT bei veralteter Revision, kein Wiederauferstehen nach Löschen, jedes gelistete Projekt öffnet, Asset-Hashes, Export-Import-Rundreise, Speichern nach Absturz funktioniert.

**Bekannt, nicht geändert:** Takte einfügen/duplizieren zwischen gebundenen Takten behält den Bogen über die neuen Takte (bewusstes Design, `EditorExtensionsTest`); Transpose/SetInstrument können Bögen über Saiten teilen. Doppelfehler: Absturz zwischen `head.previous` und `head.json` lässt beide auf Revision N zeigen — ist N danach zusätzlich beschädigt, ist N−1 nicht mehr erreichbar. Ein Projekt mit nur einer Revision, dessen beide Heads verloren gehen, ist nun ebenfalls unsichtbar statt als unlesbar gemeldet (beides ohne Zugriff; Dateien bleiben erhalten).

Ergebnis Zyklus 14: **7 Defektgruppen bestätigt und behoben** → nicht defektfrei.

### Zyklus 15 — Generative Rundreise-Prüfung der Formate („Verlust-Ehrlichkeit“) und eigenschaftsbasierte Layout-Prüfung

Methoden (neu): (a) `Qa15FormatRoundTripTest`: zufällige gültige Projekte ausschließlich über EditorCommands erzeugt, Export→Import für GP7, MusicXML/.mxl, MIDI, nativ; merkmalsgenauer Vergleich, jede Abweichung muss durch einen Export-Verlust oder dokumentierte Formatgrenzen erklärt sein (L1), keine Ausnahme (L2), kein ERROR beim Import des eigenen Exports (L3), Idempotenz nach einer Runde (L4) — > 5 000 Projekte je Format. (b) `Qa15LayoutPropertyTest`: 2 000 zufällige Projekte, 8 000 Layouts (bis 300 Takte, 4–8 Saiten, Schlagzeug, Klavier, Tuplets, alle Artikulationen, TAB/Noten/beides, Umbruchbreiten, Raster); Invarianten P1–P9 (eine NoteBox je Note, Hit-Test trifft eigene Note, `xFor` monoton, `columnAt` invers, Glyphen im System, keine Überlappung, lineare Laufzeit, gültiges SVG, Determinismus). (c) Gegenprobe mit dem Realkorpus aus Zyklus 8 (673 Dateien): **keine Statusänderung**; einzige Zahlenänderung `test-all-gm-percussion.mid` 251 → 183 Noten = exakt die 183 Note-On-Ereignisse der Datei (vorher zerlegte Schlagzeugschläge).

**Korrektur Zyklus 13:** Die dortige Angabe „Notenkopf in der Standardnotation ist ebenfalls greifbar“ war falsch — ohne TAB gab es gar keine NoteBoxes (siehe 1).

**Bestätigt und repariert:**
1. Ohne TAB (nur Noten bei Gitarre, Schlagzeug, Klavier) gab es keine Trefferflächen: Noten waren nicht antippbar und nicht ziehbar → Notenköpfe erzeugen NoteBoxes (`Qa15LayoutFindingsTest`).
2. Whammy-Dip wurde unter das System bzw. aus der Seite gezeichnet (PDF schnitt ihn ab) → auf den Systemraum begrenzt.
3. Das „(n)“ einer Bindeziel-Note war breiter als ihre Spalte: Tippen auf die Mitte der Nachbarnote wählte das Bindeziel → Spaltenbreite berücksichtigt Bindeziele.
4. MIDI: ein Schlagzeugschlag erklang nach Re-Import doppelt (in gebundene Stücke zerlegt, Schlagzeug ignoriert Bindungen) → ein ungebundener Wert.
5. GP7: Dynamik einer einzelnen Akkordnote änderte die anderen ohne Verlustmeldung; Schlagtechnik (Side-Stick/Rimshot) übertrug sich auf normale Schläge desselben Instruments.
6. MusicXML: Whammy-Dip kam mit umgekehrter Richtung zurück; Bend+Whammy auf einer Note verschmolzen; Vibrato einer Akkordnote breitete sich auf den Akkord aus.
7. MusicXML ohne Verlustmeldung: expliziter D.S.-/Coda-Ziel, Textart (Fingersatz/Abschnitt), Hammer-on/Pull-off/Slide ohne Folgenote, Strum auf Einzelnote, Triller-Rate → jetzt gemeldet.
8. MIDI: benannte leere Spuren verschwanden; Bend-Kurven drifteten bei jeder Rundreise um einige Cent bzw. fielen in der zweiten Runde weg → stabil.
9. Metadaten: Leerzeichen am Rand gingen in XML-Formaten verloren → `SetMetadata` trimmt kanonisch wie `Rename` (die UI trimmte bereits).
Tests: `Qa15FormatFindingsTest` (13), `Qa15LayoutFindingsTest` (4), beide Kampagnen als kurze Läufe in der Suite.

**Gehalten:** nativ verlustfrei in allen Projekten; keine Ausnahmen/ERRORs; GP7 idempotent; Layout: TAB-Trefferflächen, `stringAt`, Monotonie, Inversität, keine Überlappung, kein NaN, 300 dichte Takte ≤ 128 ms bei linearer Glyphenzahl, alle SVGs gültig, deterministisch.

**Bekannt, nicht geändert (Standard-Ausnahmen der Layout-Kampagne):** Hit-Test ignoriert die Stimme; Notenköpfe ohne TAB können sich bei Sekund-Akkorden und extremer Kompression überdecken; Noten weit außerhalb des Systems (Klavier < m24, Gitarre > Bund 30) verlassen das Band; bei Rand 0 ragen Labels/letzte Spalte bis 2 Einheiten über den Schlussstrich; seltene Bend-Pfeile unter dem System. Formate: MusicXML erkennt tief gestimmte 4/5-Saiter ohne Namen „Bass“ als Bass; MIDI Let-Ring am Schluss/verschobene Texte erzeugen Zusatztakte; einzelne MIDI-Rundungsdifferenzen bei Bends und 7-Saiter-Stimmung in der zweiten Runde.

Ergebnis Zyklus 15: **9 Defektgruppen (16 Einzelbefunde) bestätigt und behoben** → nicht defektfrei.

### Zyklus 16 — Modellbasierte Zufallstests des WorkspaceViewModel und Compose-„Monkey“-Test

Methoden (neu): (a) WorkspaceViewModel auf der JVM über einen Lifecycle-Shim (nur im Scratchpad, nicht im Repo) mit echtem FileProjectRepository, Asset-Store, nativer Austausch, Formatkatalog und echtem EngineTransport; 59 Operationen/40 Befehlsvarianten, 25–60 % ohne Warten (überlappend); Invarianten V1–V8 (keine Ausnahme, Autosave konsistent, Auswahl gültig, Busy-Flags fallen zurück, Transportstatus, Wiederöffnen, Layout aktuell, Hinweise) — 2 500 vollständige Folgen. (b) `Qa16MonkeyTest` (Robolectric, CI): 5 Seeds × 40 zufällige UI-Aktionen (Klicks, Doppeltipps, Lang-Drücken, Text, Scrollen, Slider, Gesten auf der Partitur, Zurück, Drehen) mit Absturz-/Leerbildschirm-Prüfung; dazu statische Absturzanalyse aller `first{}`/`!!`/Indexzugriffe der Oberfläche (alle bis auf A unerreichbar).

**Bestätigt und repariert (rot → grün):**
1. Doppeltipp auf „Zurück“ in den Einstellungen poppte auch den Eintrag darunter: aus der Bibliothek ein leerer Bildschirm, aus einem Projekt das Projekt ohne Stopp der Wiedergabe und ohne Speichern (`doubleTapOnSettingsBackKeepsTheLibrary`, auf CI 42031458704 rot, 42032537065 grün). SVG-Export ist zusätzlich gegen Layout-Ausnahmen abgesichert.
2. Überkorrektur aus Zyklus 13 (#3): Play nach Stopp/Pause während einer fremden Neukompilierung (Bearbeitung, Tempo) wurde verschluckt; nach schnellen Play-Tipps gewann Stopp nicht. → Das gemeinsame Flag entfällt; `Transport.play(since)` erhält den Halt-Zähler vom Moment des Tipps (`togglePlay`). Tests aus Zyklus 12/13 rufen jetzt ebenfalls `play(since)` wie die App.
3. Eingaberaster folgte Notenwert/Triole nicht (ohne gewählten Beat gar nicht, mit Beat mit altem Raster).
4. Zoom brach umbrochene Systeme nicht neu um.
Tests: `Qa16ViewModelFindingsTest` (7, mit Shim vorher 7/7 rot, nachher 7/7 grün; Kopie in feature/workspace für CI), `Qa16MonkeyTest`. CI 42035002847 (9dae381) grün.

**Gehalten:** keine Ausnahmen/Coroutine-Fehler, Autosave stets konsistent mit dem Speicher (auch bei provoziertem CONFLICT nie fälschlich „gespeichert“), Auswahl gültig, Busy-Flags, Wiederöffnen liefert letzte gespeicherte Revision, Export-Callback genau einmal.

**Hänger beim Prüfen (dokumentiert):** (i) Mein CI-Watcher wartete auf den „neuesten“ Lauf, der von einem späteren Commit überholt wurde → `scratchpad/ci-wait.sh` wartet auf den Lauf genau eines Commits mit Zeitlimit. (ii) Der Gradle-Testprozess der 1 000er-ViewModel-Kampagne hing nach Testende beim Herunterfahren (2,3 GB, starke GC) → beendet; vermutlich Speicher, der sich im Testaufbau über viele ViewModels ansammelt (nicht als App-Defekt bestätigt — Gegenstand von Zyklus 17).

**Bekannt, nicht geändert:** Pinch-Zoom bei umbrochener Ansicht löst je Schritt ein (abbrechbares) Neu-Layout aus; laufende Layout-Berechnungen werden nicht unterbrochen, nur verworfen. `onCleared` stoppt den Transport nicht (das übernimmt `leave()`); nach Wiederöffnen hält der geteilte Transport bis zum nächsten Prepare alte Tempo-/Metronom-Einstellungen (ohne Wirkung, Play bereitet neu vor). Monkey-Test: Auswahl des obersten Fensters ist eine Heuristik; Datei-Auswahldialoge werden abgebrochen.

Ergebnis Zyklus 16: **4 Defektgruppen bestätigt und behoben** → nicht defektfrei.

### Zyklus 17 — DSP-Eigenschafts-Fuzzing und Speicher-/Ressourcen-Lebensdauer

Methoden (neu): (a) `Qa17DspFuzzCampaign`: 1 960 zufällige Effektketten (≈ 9 500 Einheiten, alle Arten, Grenz-/Null-/NaN-/±Inf-Parameter, 4 Abtastraten, 6 Blockgrößen, Stille/DC/Rechteck/Impuls/Rauschen) und 225 zufällige Engine-Renderings (1–8 Spuren, alle Presets, bis 400 Noten, Bends/Vibrato/Flageoletts, mit/ohne Sample-Bank); Invarianten D1–D8 (kein NaN, Limiter-Decke, Blockbudget, Stille→Stille, Blockgrößen-Unabhängigkeit, `reset()` = neu, keine Allokation, Stimmenraub ohne Klick). (b) Speicher-/Ressourcen-Messung: WorkspaceViewModel-Zyklen (öffnen, bearbeiten, spielen, exportieren, rendern, schließen) N = 20/50/100 mit Heap nach GC, Klassenhistogramm, WeakReferences, Thread- und Dateideskriptorzählung; Undo-Historie eines 300-Takt-Projekts.

**Bestätigt und repariert (rot → grün):**
1. *Speicher:* Der PCM-Cache für Referenz-Audio behielt dekodierte Clips geschlossener Projekte (bis 8 × ≈ 115 MB — weit über dem Android-Heap) → nur Clips des vorbereiteten Projekts bleiben (`Qa17ClipCacheFindingsTest`, deterministisch über den Cache-Inhalt; die erste Fassung mit `System.gc()`/WeakReference war auf CI nicht deterministisch).
2. Stimmenraub schnitt die geraubte Stimme hart ab: Klicks bis −9 dBFS (Drums), −14 dBFS (Sample-Gitarre) → Ausklang-Rest je Spur (4 ms). Der Test vergleicht gegen eine Referenz ohne die geraubte Stimme (der ursprüngliche Vergleich enthielt deren eigene Wellenform und war unerfüllbar).
3. NaN in Pan/Gain eines Referenz-Clips (nur aus Dateien möglich) machte den ganzen Master zu NaN.
4. `Chorus.reset()` setzte das LFO nicht zurück (Klang nach Leerlauf geschichtsabhängig).
5. Kompressor, Saiten- und Sample-Stimmen rechneten ihre Steuerrate je Aufruf statt fortlaufend → Ergebnis hing von der Blockgröße (Export vs. Live) und von Notenanfängen anderer Spuren/Metronom ab.
6. Erster Block nach Rack-Aufbau wurde von 0 eingeblendet (Export: erste ≈ 21 ms gedämpft).
7. Overdrive erreichte nie exakte Stille (Grenzzyklus nahe Flush-to-Zero) und kostete bei Stille das 3–4-fache an CPU → Biquad-Flush und softClip-Schutz; nach 10 s Stille exakt 0.
Tests: `Qa17DspFindingsTest` (7/7 rot vor dem Fix), `Qa17ClipCacheFindingsTest`. CI: 42041498640 (6d4c033) grün im 2. Versuch — der 1. scheiterte an HTTP 429 von Maven Central beim Laden der Robolectric-SDK-Datei (Infrastruktur, kein Testfehler).

**Gehalten:** kein NaN außer (3); Limiter nie über 0,94; Verzögerungs-Feedback 0,95 stabil; alle anderen Effekte bitgleich über Blockgrößen und nach `reset()`; keine Allokation im Renderpfad nach JIT-Aufwärmen (JFR); Stimmenpools nie überschritten; PcmBuilder in Stücken = am Stück; 170 Ketten bei 48 kHz/256 Frames ≤ 1 ms pro Block. Speicher: in der App-ähnlichen Konfiguration Heap über 100 Zyklen flach, keine abgeräumten ViewModels/Sessions/Layouts erreichbar, Threads (14) und Dateideskriptoren (65) konstant; Android-Registrierungen (Audiofokus, Kopfhörer-Receiver, Lifecycle-Observer, Export-Launcher) paarweise.

**Ursache des 2,3-GB-Hängers aus Zyklus 16:** reiner Testaufbau — je Folge ein neuer Transport mit nie endendem Poller (Thread-Starter ohne Thread); behoben durch `transport.shutdown()` in der Fixture.

**Bekannt, nicht geändert:** Undo-Historie speichert je Schritt die volle Diagnoseliste (≈ 37 B pro Diagnose; bei 2 400 Warnungen ≈ 90 KB pro Schritt, durch 200 Schritte begrenzt — Partiturbaum selbst strukturell geteilt, 2,3 KB pro Bearbeitung). Release-Erkennung der Saiten/Samples liegt jetzt auf dem Steuer-Takt (bis 15/31 Frames später). Hintereinander gestapelte Verstärkungen (Makeup +24, EQ +18, Pegel +12, Resonanz) können ohne Master-Limiter > 40 dBFS ergeben (jede Einheit hält ihre dokumentierte Verstärkung). `PendingExports` kann bei fehlendem Dokumentanbieter ein abgeräumtes ViewModel halten (höchstens eins).

Ergebnis Zyklus 17: **7 Defektgruppen bestätigt und behoben** → nicht defektfrei.

### Pausiert nach Zyklus 17 (auf Nutzerwunsch)

Der Nutzer hat gebeten, den laufenden Zyklus abzuschließen und das Ziel danach vorerst zu stoppen. **Abschlussbedingung nicht erfüllt:** Zyklen 1–17 fanden jeweils Defekte; es gab nie drei defektfreie Zyklen in Folge. Fortlaufend steigende Befundzahlen bei neuen Methoden (modellbasiert, generativ, Fuzzing) zeigen, dass weitere Fehlerklassen wahrscheinlich sind.

**Bei Wiederaufnahme naheliegend (noch nicht angewandte Methoden):** Mutationstests (Testqualität), Lint-/Detekt-Befunde der CI systematisch auswerten, modellbasierte Tests für Stimmgerät/Aufnahme, Performance-Profile großer Projekte auf dem Gerät, generative Rundreise für GP3–5/GPX-Importer.

**Weiterhin nie geprüft (keine Hardware in dieser Umgebung):** Klang/Latenz auf Geräten, Unterläufe/Thermik unter Last, echte Audiofokus-/Kopfhörer-Ereignisse, Mikrofon mit realem Signal, SAF-Anbieter (Drive, USB), Darstellung und Gesten auf echter Hardware (nur Robolectric), TalkBack/Accessibility-Scanner, Navigation-Deep-Link-Extras, Main-Thread-Blockaden (nur aus Code abgeleitet). Der vom Nutzer gewünschte Fast-Forward von `ccr-0b650dff-jnhups-qa` nach `ccr-0b650dff-jnhups` wurde von der Berechtigungsprüfung blockiert und ist offen.

### Zyklus 18 — Gerätebefunde des Nutzers: Gitarrenklang, Startverhalten, Bedienung (Bericht 1 + 2)

Anlass: erste Nutzertests am Gerät (übersteuerter Klang, „Fehler bei zwei gleichzeitigen Noten, nicht immer“, verschluckter erster Schlag, kaum zurücksetzbarer Zeitzeiger, Ansichtssprünge, „Ton 33 nicht spielbar“) plus Bedienwünsche (Taktart/Tempo per langem Drücken, Standardprojekt 1×4/4, Standardgitarre E-Standard). Methode: fünf unabhängige Diagnosen (D1 Akkorde/Umstimmen, D2 Pegelstruktur, D3 erster Schlag, D4 UI-Plan, D5 Startposition/Ansicht) mit Messungen und roten Tests, danach Reparatur in getrennten Worktrees, Zusammenführung, komplette Suite.

**Gemessene Ursachen und Reparaturen (jeweils rot → grün):**
1. *Zwei Noten* (D1): Die Voice-Engine ist exakt linear (525 Saitenpaare bitgenau = Summe der Einzelnoten). Ursache in der Sample-Bank: Regionen bis +14 Cent verstimmt (35/88 Quinten/Oktaven > 5 Cent, Powerchords schweben 1,5–2,4 Hz), Pegel streuen 8,2 dB (die leisere Note wird im sättigenden Amp um 7–16 dB „eingefangen“, Round-Robin-abhängig → „nicht immer“), Unisono = zweimal dasselbe Sample. → RWSB v2 mit Feinstimmung (Körper 0,25–0,9 s) und Pegel (RMS 0–0,4 s auf −23 dBFS, Peak-Deckel −3 dBFS) je Region, abgeleitet aus der v1-Bank (`tools/bank_analysis.py`, `tune_guitar_bank.py`, stdlib, deterministisch; v1 wird beim Laden normalisiert); Round-Robin je Saite geseedet, Startoffset je Saite. Nachher: alle Quinten/Oktaven ≤ 1 Cent, Pegelstreuung je Root 2,4/1,4 dB (`Qa18BankTuningTest`).
2. *Übersteuerung* (D2): jede Spur −4..+2 dBFS am Master, Limiter bei der Standardband 91 % der Zeit aktiv; Clean-Amp 19 % THD bei einer v96-Note; Crunch ohne Dynamik (2,7 dB); `softClip` überschoss auf 1,03 mit 3-%-Sprung bei |x| = 4,97. → Velocity-Gesetz v^1.3, sampleGain 0,6/0,28/0,3, Amp-/Kompressor-Trims (HG −5 dB, Crunch −2, Clean Comp −30/3/+6, Bass, Drums), softClip-Klemme bei 3, Hörkandidaten Clean-Chorus 0,10/Hall 0,12, Crunch-Hall 0,08. Nachher: Limiter 0 %, Clean-Amp-Eingang −15,7 dBFS (2,5 % THD), Crunch-Dynamik 8,3 dB, Stems ohne Clipping (`Qa18GainStructureTest`). Offen: Preset-Lautheit 9 dB Spreizung (Designentscheidung), 1-ulp-Empfindlichkeit des AmpSim (Sonde), HG 45 dB über dem Knie (Charakter).
3. *Erster Schlag* (D3): Kotlin-Pfad in 17 Tests onset-identisch; echter Fehler: Play im Block des 20-s-Leerlaufstopps ging verloren → Idle-Stopp nur bei leerer Befehlsschlange, `post()` meldet toten Host, `play()` startet neu und postet genau einmal. Nach natürlichem Ende startete Play oben statt am Cursor (veraltetes `ended`) → explizites `atEnd`, von seek gelöscht (D5-1). Android-Hypothesen umgesetzt (CI-geprüft, Gerät offen): AudioTrack mit Stille vorbefüllt und 8 statt 4 Blöcke Puffer; JIT-Aufwärmen der DSP-Pfade auf Default (bitgleicher Output, Test); Bank-Nachladen wird vom nächsten Play übernommen; `host.start()` nicht mehr auf Main (`Qa18FirstBeatTest`, `Qa18D5PlayAfterEndTest`, `Qa18StartFixesTest`).
4. *Startposition/Ansicht* (D5): kein Stopp/Zum-Anfang im Editor, Pause+Play ignorierte Cursorbewegungen, PAUSED/STOPPED gleiches Symbol; 8-%/85-%-Scrollregel sprang bei 15/15 Vorwärtsschritten um 43–64 % der Ansicht; Vertikalregel setzte jede Nutzer-Scrollposition zurück. → Cursor ist Startmarke (▶-Kappe im Stopp), Pause→Play setzt nur fort, wenn nicht navigiert wurde, Zum-Anfang-Pill + Pos1-Taste, PAUSED-Ring am Play-Pill, Taktkarte/Pfeile suchen auch während der Wiedergabe; `ViewportScroll` (core/notation, JVM-getestet) mit Minimalverschiebung, 24-Einheiten-Rand, Zeilenregel; Folgen-Pause als Zustand mit „Folgen“-Chip; Kopfbereich ≈ 66 dp kompakter, Wiedergabekopf 3,5 mit Dreieck, sichtbarere Cursor- und Saitenlinien (`ViewportScrollTest`, `Qa18StartPositionViewModelTest`).
5. *Taktangabe-Editor* (D4): `SignatureBox` im Layout, langes Drücken auf die Taktangabe (auch Höflichkeitsangaben am Systemanfang) öffnet ein Sheet mit Zähler, Nenner, Presets, BPM (Tempo in Kraft), Tempowechsel entfernen; Hinweise im Sheet; Kopfzeile tippbar als barrierefreie Alternative (`SignatureHitTest`, Robolectric `Qa18SignatureGestureTest`/`Qa18SignatureSheetTest`).
6. *Standardwerte/Umstimmen* (D4/D1-4): `ProjectFactory.create` → 1×4/4, 6-Saiter E-Standard „Gitarre“; `demo()` behält die alte Form für Fixtures/Tests. Umstimmen scheitert nicht mehr hart: Dry-Run `Retuning.plan`, Oktavierung ±12, Bindebögen als Gruppe, Löschen nur mit `allowDrop`, Meldung mit Takt/Saite; Stimmungs-Editor als Entwurf mit Konfliktpanel, Saitenzahl 6/7/8, persistierter Policy und Bank-Hinweis < Db2 (`RetuneResolutionTest`, `Qa18DefaultsRegressionTest`, `RetuneViewModelTest`).
7. Nebenbefund beim Merge: `leave()` konnte nach `flush()` auf dem Mutex-freigebenden Thread fortgesetzt werden und `popBackStack` abseits von Main rufen (Monkey-Seed 4) → `withContext(Dispatchers.Main.immediate)`.
8. Nebenbefund aus der CI (`Qa18StartPositionViewModelTest.r1` nur im Release-Lauf rot): Der Transport-Poller veröffentlichte den zuletzt *präsentierten* Block des Hosts auch dann, wenn dieser Block vor einem gerade geposteten Seek lag — die frische Seek-Position wurde vom alten Zeitzeiger überschrieben (am Gerät für einen Poll: „springt hin und her“, Bericht 1 §4; im Test mit angehaltenem Render-Thread dauerhaft). Außerdem konnte ein Poll, der den Host vor einer Pause gelesen hatte, sein PLAYING über das frischere PAUSED legen (Lost Update auf dem StateFlow). → `HostSnapshot.seeks` zählt angewandte Seeks je präsentiertem Block; der Poller hält die Seek-Position, bis der Host aufgeholt hat (wie bisher für Programme); jede Schreibung von Aufruferseite erhöht eine Schreib-Epoche, ein davor begonnener Poll wird atomar verworfen; `postedPlays` wird vor dem Posten gezählt (`Qa18SeekPublishingTest`, rot davor: Takte [1, 3] statt 3). Der Test rechnete zudem mit vier Takten, das Standardprojekt hat seit Punkt 6 einen — Fixture auf vier Takte zurückgestellt.

**Bekannt, nicht geändert:** MusicXML verliert einen SHIFT-Slide, dessen Ziel in einer anderen Stimme liegt (vorbestehend, Qa15-Seed 15022); Intra-Noten-Drift tiefer f-Layer-Samples (+22 → −2 Cent) ist mit einem Offset nicht korrigierbar; Clean-Kette hat eine +3,7-dB-Tonhöhenneigung (Modelle unverändert); Hörurteile zu Crunch-Trim 0,28 und HG-Trim 0,6 stehen aus. Gerätehypothesen D3-2/D3-3 sind nur per Code und JVM-Timing belegt: Nach dem ersten Play eines frischen Prozesses die Diagnose-Zähler (Unterläufe/Überläufe) ablesen.

Ergebnis Zyklus 18: **8 Befundgruppen mit roten Tests behoben, 4 Nutzerwünsche umgesetzt**; Ohrprüfung am Gerät steht aus.

### Zyklus 19 (Teil Drums) — Nutzerbericht: Drum-Raster überlappt, Drums übersteuern, erster Hit fehlt

Methode wie Zyklus 18: Messung gegen die D2-Pegelstruktur (48 kHz, Werks-Mix, Master vor dem Limiter) und Onset-Vergleich wie D3, jeweils roter Test vor der Reparatur (`Qa19DrumLevelTest`, `Qa19DrumFirstHitTest`, `Qa19DrumGridLayoutTest`).

1. *Pegel:* Die Drum-Stimmensumme lag bei +8,3 dBFS am Eingang des Drum-Kompressors; Groove (v96, Akzente v127) −0,4 dBFS am Master, alles v127 −0,0 dBFS, Kick v127 einzeln −2,3 dBFS; Standardband (HG, Bass, Drums) Master +1,4 dBFS vor dem Limiter, Limiter 4,7 % aktiv (max. 1,9 dB), Drums −0,4 dBFS gegen Gitarre −9,3 / Bass −7,8; Qa18-Demo-Band 5,5 %, Qa18-Drum-Stem −2,5 dBFS. → Kit-Trim in `DrumVoice` 0,8 → 0,5 (−4,1 dB), Kompressor unverändert (weniger Stauchung). Nachher: Groove −4,5 dBFS (RMS −21,9 → −23,3), alles v127 −4,1, jedes Element ≤ −6,2 dBFS bei v127, Standardband −2,6 dBFS / Limiter 0 %, Demo-Band 2,5 %, Drum-Stem −5,9 dBFS, kein Clipping. Live und Export nutzen denselben Pfad (Live vs. Export max. |Δ| 7,6e−5).
2. *Erster Hit:* Kaltstart, Stop→Play nach 0,5 s, Leerlaufstopp, Vorzähler: onset-gleich (0–50 ms ≤ 0,1 dB), nach dem Loop-Umlauf 0,4 dB. Echter Fehler: bis 200 ms nach einem Klang vor Play (Vorhören eines Rasterfelds, letzter Schlag vor Stop) verlor der erste Hit bis 1,8 dB über 50 ms und bis 4,2 dB in den ersten 5 ms (Drum-Kompressor noch in Gain-Reduktion, 15-ms-Choke des vorgehörten Fells überlappte). → `SynthEngine.play` beendet Drum-Stimmen mit dem 4-ms-Residual des Voice-Stealings und setzt die Kompressoren der Drum-Kanäle zurück (nur DRUMS). Nachher alle Abstände 10–400 ms ≤ 0,4 dB (0–50 ms).
3. *Raster/Layout:* Ohne TAB setzt der Editor die Drum-Spur nur mit Köpfen; ein 32tel-Takt auf 360 dp mit Raster 1/32 wurde auf Spalten von 11,4 Einheiten gestaucht (Stauchung 0,5 auf alle Spalten), Köpfe mit versetzten Sekunden überlappten um 7,7 Einheiten; im Lesemodus Ankerabstand 8,6 < Kopfbreite 8,7. → `Standard.headExtent` (Köpfe, Sekunden, Hilfslinien, Ghost-Klammern, Vorzeichen, Punkte) und `ScoreLayouter.sweep`: Anker rücken, bis Tinte + 0,4 Linienabstand passen; Zeilenumbruch zählt die Verschiebung, Stauchung per Bisektion begrenzt (Qa15 P6wide bleibt grün). Nachher kleinste Kopflücke 3,2 (EDIT) / 2,8 (READ), Ankerabstand ≥ 13,1 bzw. 11,5. TAB-only-Layouts unverändert. DrumScreen: Elementnamen in fester Spalte (scrollten bei 1/32 mit weg), Zählzeit-Lineal, Abstand je Schlag; Zellen bleiben 48 dp (nur CI-prüfbar).

**Offen:** Hörprüfung des Drum-Trims gegen die Gitarren (Drums RMS jetzt 3,4 dB unter der HG-Gitarre, Spitzen 4,8 dB darüber); ein 32tel-Takt ist auf 360 dp jetzt 573 Einheiten breit (Schwenken/Zoom 0,6) und die Spalten-Trefferfläche im Notenbild bleibt < 40 dp (Eingabe dichter Raster über den DrumScreen); das Werks-Kit hat keine Toms; Gerätetest des ersten Hits (AudioTrack) steht aus.


### Zyklus 19 / Gitarren-Artikulationen

Anlass: Nutzerziel für die drei gesampelten Presets (Clean glasklar mit realistischen Natural-/Pinch-Harmonics, Crunch definiert und dynamisch, High Gain tight mit präziser Tontrennung); Open, Palm Mute, Dead Note, Harmonics, Bends/Slides und Übergänge sollen klar unterscheidbar sein, nicht nur im Pegel. Methode: Offline-Renders durch die echte Kette (ScorePlaybackCompiler → SynthEngine mit Bank v2 → Preset-Kette → Master, 48 kHz, E-Standard, 120 BPM), dieselben Noten je Preset: E2 (Saite 6 leer), A3 (D-Saite Bund 7), E4 (hohe E leer), v64/v96/v127; Analyse-Kit `Qa19ArticulationAnalysis` (FFT, YIN-Tonhöhe, Hüllkurve, THD, nur stdlib). Tests: `Qa19ArticulationTest` (6 Prüfungen + Mess-Sonden, die die Tabellen drucken: Präfixe `Q19`, `Q19A`–`Q19E`, `Q19H`, `Q19P`, `Q19T`), `Qa19LiveVsOfflineTest` (adapter/audio). Alle Zahlen unten sind Messwerte nach den Reparaturen (Commit efd013b), „vorher“ = 2dd925f. Kein Hörtest: Klangurteile stehen aus.

Kennzahlen: Attack = RMS der ersten 20 ms gegen den Körper 20–200 ms; Decay = Zeit vom lautesten 10-ms-Fenster bis zum endgültigen Unterschreiten von −20/−30/−40 dB (Ganze Note = 2 s, d. h. ~2000 ms heißt „klingt bis Note-off“); Schwerpunkt und Anteil > 2 kHz im Fenster 30–200 ms; Periodizität = normierte Autokorrelation bei der Nennperiode (±6 %) im Fenster 5–45 ms; Teilton-Anteil = Leistungsanteil der ersten 12 Teiltöne der Note (±50 Cent) in den ersten 85 ms.

**Messtabelle (v96, Ausgang wie gehört; vollständige Tabelle mit v64/v127 druckt `probe_articulationTable`):**

| Preset | Ton | Artik. | Peak dBFS | Attack dB | −20/−30/−40 dB ms | Schwerpunkt Hz | > 2 kHz % | Periodizität | Teilton-Anteil |
|---|---|---|---|---|---|---|---|---|---|
| clean | E2 | open | -13.0 | 2.8 | 2025/2045/2145 | 363 | 0.00 | 0.92 | 0.72 |
| clean | E2 | PM | -14.7 | 12.1 | 115/200/410 | 281 | 0.01 | 0.90 | 0.84 |
| clean | E2 | dead | -19.5 | 25.3 | 20/115/265 | 433 | 0.09 | 0.35 | 0.39 |
| clean | A3 | open | -13.1 | 1.2 | 2005/2030/2225 | 561 | 1.34 | 0.98 | 0.96 |
| clean | A3 | PM | -15.9 | 14.0 | 70/235/350 | 570 | 2.08 | 0.98 | 0.94 |
| clean | A3 | dead | -17.1 | 29.7 | 20/25/280 | 1042 | 1.68 | 0.74 | 0.20 |
| clean | E4 | open | -13.6 | 2.1 | 2030/2055/2125 | 813 | 5.32 | 0.94 | 0.96 |
| clean | E4 | PM | -16.6 | 15.7 | 65/140/415 | 707 | 2.23 | 0.89 | 0.94 |
| clean | E4 | dead | -18.6 | 29.9 | 20/90/340 | 1700 | 21.76 | 0.58 | 0.34 |
| crunch | E2 | open | -9.1 | 1.1 | 2030/2050/2160 | 428 | 0.26 | 0.91 | 0.68 |
| crunch | E2 | PM | -10.3 | 9.4 | 130/205/300 | 286 | 0.02 | 0.93 | 0.84 |
| crunch | E2 | dead | -14.0 | 24.5 | 25/35/180 | 470 | 0.18 | -0.01 | 0.26 |
| crunch | A3 | open | -10.4 | -0.1 | 1960/1980/2155 | 611 | 1.86 | 0.98 | 0.95 |
| crunch | A3 | PM | -12.3 | 11.9 | 80/140/295 | 537 | 0.32 | 0.98 | 0.95 |
| crunch | A3 | dead | -13.2 | 34.4 | 15/20/260 | 914 | 2.10 | 0.90 | 0.30 |
| crunch | E4 | open | -9.8 | 0.8 | 2035/2055/2140 | 752 | 2.57 | 0.94 | 0.97 |
| crunch | E4 | PM | -10.6 | 13.1 | 85/115/260 | 664 | 0.48 | 0.91 | 0.95 |
| crunch | E4 | dead | -13.8 | 33.9 | 10/15/195 | 1530 | 11.34 | 0.59 | 0.30 |
| highgain | E2 | open | -9.1 | 0.1 | 2105/2145/2155 | 1171 | 16.91 | 0.81 | 0.31 |
| highgain | E2 | PM | -9.2 | 2.6 | 240/265/300 | 371 | 1.17 | 0.79 | 0.65 |
| highgain | E2 | dead | -10.2 | 9.0 | 40/55/65 | 125 | 0.00 | 0.27 | 0.33 |
| highgain | A3 | open | -9.7 | -0.7 | 1985/2000/2040 | 1719 | 43.25 | 0.82 | 0.83 |
| highgain | A3 | PM | -10.4 | 4.5 | 130/145/165 | 730 | 4.95 | 0.84 | 0.87 |
| highgain | A3 | dead | -10.4 | 23.8 | 20/25/35 | 714 | 1.50 | 0.85 | 0.28 |
| highgain | E4 | open | -9.8 | 0.0 | 1095/1110/1125 | 1447 | 16.72 | 0.91 | 0.97 |
| highgain | E4 | PM | -9.9 | 4.0 | 130/145/170 | 919 | 4.79 | 0.91 | 0.97 |
| highgain | E4 | dead | -10.7 | 42.1 | 15/20/25 | 714 | 1.91 | 0.80 | 0.04 |

Lesart: Clean A3 PM „> 2 kHz 2,08 % > open“ ist die Hallfahne des hellen Anschlags (Clean-Hall 0,12), nachdem der gedämpfte Ton nach 70 ms um 20 dB gefallen ist; solange die PM-Note klingt (20–105 ms), hat sie ×0,25 des Anteils > 1 kHz der offenen Note. Die hohe Periodizität der A3-Dead-Notes stammt von der Eigenresonanz der Dead-Note-Samples (~207/415/830 Hz, Vielfache fallen ins ±6-%-Fenster); die Tonhöhenverfolgung (10-ms-Fenster, 0–100 ms) trifft 220 Hz in 0 von 10 Fenstern.

**Harmonics, Übergänge, Preset-Charakter (v96):**
- Natural Harmonic 12/7/5 auf E2-, D3-, E4-Saite: Tonhöhe = 2/3/4 × Leersaite (alle 27 Fälle ±15 Cent, z. B. E2-NH7 247,3 Hz), Oberton trägt 50–99 % der Leistung (HG D3-NH7 49,8 %) und ist der stärkste Teilton, Leersaiten-Grundton 0,00 %. Vorher (Rauschimpuls-Anregung) 23 von 27 Fällen rot: der Oberton war nicht der stärkste Teilton bzw. < 20 % (Clean E2-NH7 0,7 % am Oberton, 38 % an dessen 3. Teilton; Crunch E2-NH7 1,3 %/64 %).
- Pinch (Leersaite, 3. Oberton) gegen NH7 derselben Saite (gleiche Tonhöhe): Schwerpunkt Clean 1289/283 Hz (E2) und 1016/487 Hz (D3), Crunch 728/298 und 852/535 Hz, High Gain 1564/345 und 1848/1218 Hz; Obertöne 2–8 × relativ zum Oberton +7,0/+9,9 (Clean), +6,7/+7,7 (Crunch), +9,4/+4,7 dB (HG). Vorher waren Pinch und NH dieselbe modellierte Stimme (alle Werte gleich bis 0,1 dB). Hohe E-Saite (Oberton 989 Hz): Clean +10,3 dB, Crunch +3,0 dB, HG +0,2 dB — nur gedruckt.
- Übergänge D-Saite A3 → B3: Pitch nach Hammer-on/Legato-Slide/Bend 247,3–247,7 Hz (B3 246,9 Hz) in allen Presets; Bend erreicht 246,8–246,9 Hz. HF-Sprung (Leistung > 2 kHz 21 ms nach/vor dem Wechsel) gepickt/Hammer/Legato-Slide: Clean 35,2/30,3/28,5 dB, Crunch 23,6/17,5/15,8 dB, **High Gain 2,8/3,4/2,1 dB**.
- Preset-Charakter: Schwerpunkt (A3+E2, v96) Clean 462 < Crunch 519 < HG 1445 Hz; THD eines 220-Hz-Sinus auf dem eigenen Eingangspegel (Kette) Clean 1,1 % < Crunch 20,8 % < HG 24,3 %; Dynamik v40→v127 Clean 12,8, Crunch 8,5, HG 1,4 dB; Ausklingen nach Note-off bis −50 dB unter Peak (PM-Achtel E2, Staccato-Achtel E2, PM-Achtel A3, Mittel) Clean 192, Crunch 125, HG 67 ms.

**Defizite und Reparaturen (rot → grün, rot auf 2dd925f):**
1. *Natural Harmonics klangen nicht am Oberton* (s. o.). → `StringVoice`: Natural Harmonics werden mit einer zupfförmigen Dreieckswelle (Scheitel bei 30 % der Periode, Teiltöne ~1/k², 4 % Rauschen) angeregt, Schleifenfilter wie ein gezupfter Ton (`naturalHarmonicsSoundAtTheirOvertone`, 23 Fehler → 0).
2. *Pinch = Natural Harmonic* (s. o.). → Pinch behält die helle Rauschanregung (keine Mindest-Weichheit 0,82, Pick-Geräusch ×3); zusätzlich spielt in den gesampelten Presets das aufgenommene Sample der gegriffenen Note durch einen Oberton-Kamm (`SampleVoice`, Summe von n Kopien im Abstand Periode/n: nur Vielfache des n-ten Teiltons bleiben, Anschlag, Saitengeräusch und Ausklingen sind die der Bank), in den ersten ms der volle Ton (Daumen erreicht den Knoten nach dem Plektrum, 6 ms), danach 10 % Grundton-Rest; ebenso für Artificial/Tapped-Harmonics (n aus dem Intervall). Kein neues Asset, keine Allokation im Render-Pfad (`pinchHarmonicIsBrighterThanTheNaturalHarmonic`, 12 Fehler → 0).
3. *Palm Mute nicht dunkel genug* (feste Tiefpass-Grenze 700 + 1500 v Hz): solange die Note klingt (20–105 ms), Anteil > 1 kHz Clean E2 v127 ×0,78, Clean A3 v127 ×0,68, Crunch E4 v96 ×0,75 der offenen Note; High Gain E2 v96/v127 nur 4,1/4,3 dB weniger Leistung > 2 kHz (20–190 ms). → Tiefpass öffnet für den Anschlag (2000 + 1500 v Hz) und gleitet in ~18 ms auf das 2–5-Fache des Grundtons (250–1200 Hz). Nachher Clean ×0,10–0,46, Crunch ×0,05–0,59 (E4 v96 knapp an der Grenze 0,6), HG > 2 kHz −9,0…−19,1 dB (Anteil > 1 kHz ×0,13–0,84, nur gedruckt); Decay −20 dB PM 40–255 ms gegen ≥ 1,1 s offen (`palmMuteDecaysFasterAndIsDarkerThanTheOpenNote`, 7 Fehler → 0).
4. Grün ohne Änderung: Dead Notes (−30 dB nach 15–115 ms, Tonhöhe der Note in ≤ 1 von 10 Fenstern, Teilton-Anteil ≤ 0,6 × offen; `deadNotesAreShortAndCarryNoPitchLine`), Preset-Charakter (`presetsDifferInTimbreDistortionDynamicsAndRingOut`), Übergänge Clean/Crunch (`pickedAndLegatoTransitionsDiffer`).
5. *Live vs. Offline*: dieselbe Artikulations-Phrase (14 Noten, 5 s) je Preset über `EngineTransport` → `RealtimeHost.renderBlock` (FakeSink, Blöcke 192/256/480, Start bei 0 und nach Seek in Takt 2) gegen `OfflineMixdown` (1024er-Blöcke, 32-bit float): Versatz 0 Frames, alle Onsets identisch, RMS-Differenz 0,000 dB, **bitidentisch** (max |Δ| = 0). Kein Fehler, keine Reparatur (`Qa19LiveVsOfflineTest`).
6. Gain-Struktur (Zyklus 18) unverändert grün: Standardband Limiter 0 %; 4-Spur-Demoband (2 × HG mit PM-Riffs und Pinch) Limiter > 0,5 dB 5,5 % → 3,1 %, max. GR 2,4 → 1,3 dB; Stems ohne Clipping. Volle Suiten core/synth (86 Tests) und adapter/audio (96 Tests) lokal grün (JVM-Harness).

**Offen / Restrisiken (nur mit Gehör oder neuen Samples zu entscheiden):**
- *Clean „glasklar“ ist mit dieser Bank begrenzt*: Quelle sind Flatwound-Saiten, DI, beide Tonabnehmer; schon das trockene Sample E2 hat 0,00 % > 2 kHz (30–200 ms), A3 0,7 %, E4 2,3 %. EQ kann fehlende Obertöne nicht erzeugen; brillanter Clean braucht eine Roundwound-/Steg-PU-Aufnahme (lizenziert) oder eine Hörentscheidung über Höhenanhebung/Exciter.
- *High Gain: gepickter und gebundener Übergang messbar gleich* (HF-Sprung 2,8 vs. 3,4 dB; trocken +15 dB gepickt): die drei gesättigten Vorstufen (≈ 58 dB) löschen den Plektrum-Transienten. Versuche: 1,5-ms-Abriss des alten Tons beim Neuanschlag — keine messbare Wirkung (verworfen); Tight-Hochpass vor der Vorstufe 400 statt 122 Hz — 5,0 vs. 2,6 dB, aber PM-Chug heller (E2 v96 ×0,19 → ×0,51) und weniger Tiefbass: Klangentscheidung per Hörtest, nicht übernommen. Test prüft HG nur gedruckt.
- High Gain Dynamik 1,4 dB und Attack-Verhältnis aller Artikulationen −1…+1 dB (Sättigung, Designcharakter); Unterscheidung dort über Decay, Spektrum und Tonhöhe. Pinch vs. NH auf der hohen E-Saite in Crunch/HG kaum verschieden (Schwerpunkt +4 %/+1 %).
- Natural Harmonics bleiben modelliert (die Bank hat keine Flageolett-Samples): Klangfarbe/Anschlag von NH und der Pinch-Mischung (Kamm-Pegel 2,2, Grundton-Rest 10 %) brauchen einen Hörtest.
- Clean-Hallfahne des Anschlags macht den PM-Ausklang hell (20–190 ms inkl. Hall: ×0,22–0,73 statt ×0,10–0,46); Clean-Dead-Note E2 erreicht −30 dB erst nach 115 ms (Hall), knapp unter der 120-ms-Grenze.
- Bank: High-Gain E4 offen v64/v96 fällt nach 1,1–1,2 s um 20 dB (v127 hält 2 s) — Layer-Unterschied der Samples, nicht geändert.
- Live = Offline gilt für den Kotlin-Pfad; AudioTrack/Gerät (Puffer, Unterläufe) ist nicht abgedeckt.

Ergebnis Zyklus 19: **3 Artikulationsdefizite mit roten Tests behoben** (Natural Harmonics, Pinch vs. Natural, Palm-Mute-Klangfarbe), Dead Notes/Preset-Charakter/Übergänge Clean+Crunch und Live = Offline messbar bestätigt; HG-Übergänge und Clean-Brillanz als Hör-/Sample-Restrisiko dokumentiert.


### Zyklus 20 / Audio-Thread, CPU, Synchronität

Anlass: Die Pfade SequenceCompiler → PlaybackPlan → EngineTransport → RealtimeHost → SynthEngine wurden in Zyklus 18/19 stark geändert (Bank v2, Round-Robin je Saite, Pinch-Kamm, PM-Filtergleiten, `restartDrums()`, neue Seek/Play-Zähler). Methode: messende Tests über den echten Live-Pfad (EngineTransport → `RealtimeHost.renderBlock`, Test-Senke ohne Allokation, Blöcke 192/256 bei 48 kHz, Bank v2) und `OfflineMixdown`; jede Abweichung erst als roter Test, dann behoben. Alle Werte: JVM-Harness, OpenJDK 17 HotSpot (C2), 4 Kerne, parallel lief ein anderer Agent. **Keine Android-Werte.** Tests: `Qa20AudioThreadAllocTest`, `Qa20RenderCostTest`, `Qa20SyncTest`, `Qa20DeclickTest` (adapter/audio, Bausatz `Qa20Kit`), `Qa20ConvolverTest`, `AudioClipTest` (core/synth), `Qa20PlanRemapTest` (core/playback).

**1. Audio-Thread-Hygiene.** Gemessen werden die auf dem Render-Thread allozierten Bytes (`ThreadMXBean.getThreadAllocatedBytes`, wie `FixReviewAllocReviewTest`) über ein Szenario mit allen Stimmenarten: gesampelte HG-Gitarre mit Open/PM/Dead/NH/Pinch/AH/Tapped/Semi, Bend, Whammy, Vibrato, Legato-Slide, Hammer/Pull, Triller, Tremolo, Vorschlag, Let Ring; Bass, Drums mit Chokes/Becken, Keys, Metronom, Vorzähler, Referenz-Clip, Vorhören, Seek während der Wiedergabe, Loop-Umläufe, Pause → Play (`restartDrums`), Rack- und Programmtausch (4 692 Schritte bei Block 192, mit und ohne Bank, nach 4 Aufwärmläufen) sowie die Befehlsverarbeitung in `renderBlock` (Install, Rack, Mix, Clips, Play mit Vorzähler, Seek, Loop, Preview, Reprogram auf 1,25 und zurück, Pause, Play). Rot vor der Reparatur:
- kompatibler Rack-Tausch 56 B (`channels.indices.all { }` erzeugte IntRange + Iterator) → Indexschleife;
- `Reprogram` 104 B je Befehl (`positionAt` + `frameFor` erzeugen PlaybackOccurrence/ScorePosition/Fraction, Ergebnis geboxt) → `PlaybackPlan.frameFromPlan` (primitiv, Ergebnis identisch zum Objektpfad: 15 337 Frames über 7 Planpaare mit Tempo-/Taktwechsel, Wiederholung, eingefügtem/gelöschtem Takt, `Qa20PlanRemapTest`);
- `Preview` 24 B (boxender `AudioSink.presentedFrame(): Long?` für die Note-on-Latenz) → `AudioSink.presentedFrameOrNegative()`, von `AudioTrackSink` ohne Boxing überschrieben (Android-Teil nur per Code geprüft).
Nachher 0 B in allen Fällen, auch mit `-XX:-DoEscapeAnalysis` (Bytecode selbst alloziert nicht; ART hat schwächere Escape-Analyse). Sperren: Während ein anderer Thread den Host-Monitor und den `idleLock` hält, laufen 500 Blöcke mit allen Befehlsarten durch, blockiert 0×, gewartet 0× (`ThreadMXBean`-Zähler); das einzige `synchronized` auf dem Audio-Thread bleibt der dokumentierte Leerlaufstopp. Damit sind auch die beiden Zyklus-5-Einträge „Rack-Tausch alloziert“ und „Cabinet-Latenz im Mix nicht kompensiert“ (s. u.) erledigt.

**2. CPU je Block** (`Qa20RenderCostTest`, Zeitring des Hosts = Diagnoseseite, 4 000 Blöcke nach 6 000 Aufwärmblöcken, Wiedergabe in Schleife; Median / p99 / Max in µs und Anteil am Blockbudget, zehn Läufe der vollen Suite):

| Fall | Block (Budget) | Median | p99 | Max | Messungen mit Max > Budget |
|---|---|---|---|---|---|
| Standardband (HG, Bass, Drums) | 192 (4 000 µs) | 140–177 µs (4 %) | 213–313 µs (5–8 %) | 674–6 253 µs (17–156 %) | 1 von 10 |
| Standardband | 256 (5 333 µs) | 189–231 µs (4 %) | 271–479 µs (5–9 %) | 622–7 889 µs (12–148 %) | 2 von 10 |
| dicht (2 × 6-stimmige 16tel-Akkorde, Drums 16tel, Metronom; bis 21 Stimmen) | 192 | 173–235 µs (4–6 %) | 263–442 µs (7–11 %) | 513–7 681 µs (13–192 %) | 2 von 10 |
| dicht | 256 | 236–301 µs (4–6 %) | 356–648 µs (7–12 %) | 660–33 777 µs (12–633 %) | 2 von 10 |

p99 bleibt bei ≤ 12 % des Budgets, also weit unter der 50-%-Schwelle; deshalb kein Profiling und keine Optimierung. Die Maxima sind einzelne Blöcke unter 4 000 (je Messung höchstens einer über dem Budget); vermutete Ursache sind GC-/JIT-Safepoints der Test-JVM und die Last des parallel laufenden Agenten (nicht gesondert belegt). Am Gerät puffert AudioTrack 8 Blöcke, ein einzelner Ausreißer dieser Größe wäre dort kein Unterlauf; ob ART solche Pausen hat, ist offen. Der Test prüft nur grob (Median < Budget). Ein Telefon-Kern ist um ein Vielfaches langsamer und ART kompiliert anders: die Gerätewerte (Diagnoseseite, Unterlaufzähler) bleiben offen.

**3. Synchronität** (`Qa20SyncTest`): je Quelle ein kurzer Schlag pro Zählzeit (Gitarre HG 16tel, Bass 16tel, Drums Cross-Stick, Metronom, Clip mit 2-ms-Burst auf jeder Zählzeit des Plans), jede Quelle allein gerendert; Onset = erstes Sample ≥ −40 dB unter der Spitze des Schlags. Szenarien: Tempo 90/150/200, Tempowechsel 90 → 150 (Takt 3) → 200 (Takt 4, Zz. 3), Wiederholung Takt 2–3 ×2, Vorzähler ab Takt 1 und ab Takt 2, Schleife Takt 2–3 über 3 Durchläufe, Speed 0,5 und 1,25, Speed-Trainer-Schritt 0,5 → 1,25 während der Wiedergabe (Reprogram).
- *Rot:* Gitarre 72–81 und Bass 70–76 Frames nach der Soll-Zählzeit, Drums/Klick 0–1, Clip 1 Frame → **bis 81 Frames (1,7 ms) zwischen den Quellen**, in jedem Szenario. Ursache: die Cabinet-Faltung (gleichmäßig partitioniert, 64er-Partition) verzögert jede Kette mit Box um 64 Frames (1,3 ms); der Rest (7–17 Frames) ist Anschlag und Filterlaufzeit der Kette (Einzelnoten: HG E2 81, HG-Powerchord 77, HG PM 86, Clean E2 75, Bass A1 71 Frames bis −40 dB; ohne die Box 7–22).
- *Reparatur:* Latenzausgleich wie in einer DAW: `Effect.latencyFrames` (Cabinet 64), `ChannelRack.latencyFrames` = langsamste Kette; jeder Kanal mit weniger Latenz, das Metronom und die Clips werden um die Differenz verzögert (Verzögerungsleitungen beim Rack-Bau, keine Allokation im Render-Pfad). Nachher: Gitarre 72–81, Bass 70–76, Drums 64–66, Klick 64, Clip 65–66 → **größte Spreizung 16–17 Frames (0,35 ms)** in allen 13 Szenarien; live = offline 0 Frames (Start ab Takt 2 mit Chase: 5 Frames Gitarre).
- *Verworfene Alternative:* eine latenzfreie Faltung (64 Taps direkt, Rest per FFT; mathematisch exakt, `Qa20ConvolverTest`-Vorfassung) hätte jede Gitarrenausgabe um 64 Frames verschoben; das veränderte die Qa19-Tabelle in ihren 5-ms-Fenstern und kippte die Clean-E2-Dead-Note von 115 auf 120 ms (Grenze < 120). Mit dem Ausgleich bleibt jede Gitarren-/Bass-only-Wiedergabe **bitidentisch** (Qa19-Ausgaben gegen c1ebdab zeilengleich). Verändert sind nur Mischungen mit Box und Drums/Klick/Clip: Standardband (D19) Master vor Limiter −2,2 → −2,5 dBFS, Default-Band (D2) −2,1 → −1,9, Demo-Band −0,2 → −0,4 dBFS (max. GR 0,3 → 0,1 dB), Limiter weiter 0 %, Stems unverändert. `AudioClipTest` erwartet den Clip jetzt bei Planframe + Rack-Latenz (das Projekt hat eine Gitarrenspur mit Box; der Clip sitzt damit auf dem Frame der Gitarre).
- *Rot:* Vorzähler ab einer Achtel zwischen zwei Zählzeiten (Takt 1, Zz. 2+): der Vorzähler endete auf dem Startframe, vom letzten Klick bis Zz. 3 lagen 1,5 Zählzeiten (28 800 statt 19 200 Frames bei 150 BPM). → Der Vorzähler verlängert sich um den Abstand zur vorigen Zählzeit und klickt auch auf ihr; Starts auf einer Zählzeit unverändert (`CountInRegressionTest`). Nachher 0 Frames Versatz.
- Grün ohne Änderung: Notenraster bei allen Tempi, Tempowechseln, Wiederholungen, Speed 0,5/1,25 und nach dem Trainer-Schritt (Reprogram) lückenlos; Vorzähler-Klicks auf dem Raster der Takte.
- Hinweis: Der gemeldete Zeitzeiger (`HostSnapshot.planFrame`) ist weiterhin der Engine-Takt, d. h. 64 Frames (1,3 ms) vor dem Hörbaren bei Projekten mit Box — wie zuvor für die Gitarre, jetzt für alle Quellen gleich (unter einem Bildschirmframe, bewusst nicht verrechnet). Referenz-Audio bleibt bei Speed ≠ 1 stumm (dokumentiert, C-STRETCH).

**4. Start/Stop/Seek-Knackser** (`Qa20DeclickTest`): HG- und Clean-Gitarre (ganze Noten), Bass, Drums (Crash + Ride), Clip (Sinus 221,3 Hz, 0,5) je allein; größter Sprung zwischen zwei Samples im Fenster −2…+40 ms um Stop mitten in der Note, Seek während der Wiedergabe in eine Pause, Loop-Umlauf in eine Pause, Pause und Pause → Play; Knacks = Sprung > −20 dBFS und > 2× größter Sprung der 200 ms davor.
- Synthesestimmen ohne Befund (blenden schon aus: Saiten 8 ms, Samples 6 ms, Drums 15 ms): HG −22,0…−26,3 dBFS (davor −25,7), Clean ≤ −47,3, Bass ≤ −33,0, Drums ≤ −16,7 dBFS bei −14,6…−16,7 davor (Becken-Rauschen).
- *Rot:* Clip: Stop/Pause −12,8, Seek −6,4, Loop-Umlauf −16,2, Pause → Play mitten im Clip −12,7 dBFS (harter Schnitt bzw. Sprung der Leseposition). Die erste Testfassung mit 220 Hz war zufällig grün: jedes Ereignis lag auf einer vollen Sekunde und damit auf einem Nulldurchgang. → Die alte Leseposition blendet über 3 ms aus, die neue ein (Kreuzblende); ein Start am ersten Frame eines Clips bleibt unverblendet. Nachher alle Clip-Ereignisse ≤ −41,7 dBFS (Sinus selbst −42,8). `Qa18FirstBeatTest`, `Qa19DrumFirstHitTest`, `AudioClipTest` grün.

**5. Nebenbefund beim 5-fach-Lauf (Transport, kein Audio-Thread):** `Qa19PlayStartRaceTest.noPausedFlickerBeforeTheFirstBlockUnderAHotPoller` war in 2 von 15 Läufen dieses Stands rot („PLAYING fiel vor dem ersten Block 1-mal ab“), auf c1ebdab in 2 von 30 Einzelläufen der Testklasse (vorbestehend, zeitabhängig; der Code dieses Pfads war unverändert). Ursache: Der Poller prüfte die Schreib-Epoche innerhalb von `MutableStateFlow.update`, dessen Compare-and-set auf *Gleichheit* vergleicht; hatten stop() und das nächste play() inzwischen einen gleichen PLAYING-Zustand geschrieben (gleiche Position, gleiches Sample), ging das veraltete STOPPED des Polls trotzdem durch (ABA). → Epochen-Prüfung und Schreiben teilen sich eine Sperre mit `place()` (nur Steuer-Threads). `Qa20PollEpochTest` reproduziert das deterministisch (rot vorher, grün nachher). Nach der Reparatur 0 von 35 Einzelläufen. **Weiterhin offen:** der Schwester-Test `noPausedFlickerWhileTheFirstBlockIsRendered` war in 2 von 35 Einzelläufen dieses Stands rot (beide Male PAUSED in einem der ersten Durchläufe einer frischen JVM, also bei kaltem JIT), auf c1ebdab 0 von 30; 60 warme Wiederholungen in einer JVM blieben auf beiden Ständen grün. Die Reparatur lässt nur weniger Poller-Schreibungen durch als vorher, eine neue PAUSED-Quelle entsteht dadurch nicht; die Ursache ist nicht gefunden (vermutlich ein weiteres Zeitfenster im Startpfad, Ziel für Zyklus 21). In den fünf vollen Suiten-Läufen trat er nicht auf.

**Stabilität:** Neue Tests deterministisch (Host ohne Thread, manuelles `renderBlock`, kein Poller); die einzige Zeitmessung (`Qa20RenderCostTest`) prüft nur Median < Budget. Volle Suiten core/synth, adapter/audio, core/playback auf dem Endstand 5× hintereinander grün (235/235, je ≈ 130 s, `--rerun-tasks`).

**Offen / Restrisiken:** Geräte- und Hörtests (ART-Allokationsverhalten, CPU/Unterläufe/Thermik am Telefon, Latenz von AudioTrack) nicht möglich; `AudioTrackSink.presentedFrameOrNegative` nur per Code geprüft (CI). Der Latenzausgleich verzögert Drums/Klick/Clip in Projekten mit Box um 1,3 ms (Gesamtlatenz +1,3 ms für diese Quellen, Gitarre unverändert); eine Effektkette mit mehreren Boxen verzögert entsprechend mehr (Obergrenze 4 096 Frames für Metronom/Clips). Rack-Tausch mit geänderter Latenz (Box hinzu/weg während der Wiedergabe) setzt die Verzögerungsleitungen zurück (kurzer Aussetzer möglich, nicht gemessen). Ein zweiter Clip-Sprung innerhalb von 3 ms ersetzt die laufende Ausblendung. Restspreizung 16–17 Frames ist Anschlagsform (Sample-Attack, Gate, Amp-Filter), keine Zeitbasis.

Ergebnis Zyklus 20 (Branch `worktree-agent-a188e7a4d69835a17`, Commits 1c58d88, 613385a, c66d9df, 2b32a0d, 61d1e87, 6357258, 724fbbc): **5 Befundgruppen mit roten Tests behoben** (3 Allokationen auf dem Audio-Thread, 1,3-ms-Versatz Gitarre/Bass gegen Drums/Klick/Clip, Vorzähler-Phase bei Start zwischen Zählzeiten, Clip-Knackser bei Stop/Seek/Loop/Play, ABA im Transport-Poller); CPU je Block ohne Befund (JVM-Desktop).
