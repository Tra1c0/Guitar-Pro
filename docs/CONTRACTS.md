# Implementierungs- und Integrationsverträge

Die Ports sind die verbindlichen öffentlichen Grenzen. Jede nicht fertige Implementierung liefert `Outcome.Failure(UNAVAILABLE, contractId=…)`; sie liefert weder leere Erfolgsdateien noch simulierte Wiedergabe. Das Funktionsregister bewertet die fünf Fähigkeiten getrennt.

Gemeinsam gilt: Zustandsänderungen nur über Commands; asynchrone Ergebnisse tragen Ausgangsrevision/Identität. Fehler werden als VALIDATION, IO, CORRUPT, INCOMPATIBLE_VERSION, PERMISSION, UNAVAILABLE, CONFLICT, CANCELLED oder RESOURCE_LIMIT ausgewiesen. Suspend-Aufträge sind abbrechbar; Coroutine-Cancellation wird nicht in Erfolg umgewandelt. Langlaufende Ports berichten OperationProgress, soweit eine Implementierung vorhanden ist. Neue Adapter dürfen keine Kernabhängigkeit auf Android oder Drittanbietermodelle einführen.

Aktuelle native Speicherung garantiert atomare Sichtbarkeit eines vollständig geschriebenen Commits bei Prozessabbruch. Hardware-/Dateisystemverhalten bei vollständigem Stromausfall ist eine eigene Geräteabnahme; Directory-fsync wird in dieser Phase nicht zugesichert.

## C-PROJECT

**Projekt-Repository · Implementiert / Phase 1**

- Grenze: `ProjectRepository` → `FileProjectRepository`; UI liest `projects` und `issues`.
- Ein-/Ausgabe: Projekt-ID und komplette Revision hinein; atomarer Commit oder typisierter Fehler heraus. `expectedRevision=null` ist create-only, sonst muss die gespeicherte Revision übereinstimmen.
- Ausführung und Fehler: IO-Dispatcher und Repository-Mutex. Revisionsdatei fsync → vorherigen Head sichern → neuen Head atomar veröffentlichen. Kein nichtatomarer Fallback.
- Abnahme: Neustart erhält Musik; unvollständiger Commit bleibt unsichtbar; beschädigter Head fällt auf den letzten intakten Stand zurück. Neuere Schema-Versionen blockieren eine Rückstufung. Tests: ProjectStorageTest.

## C-SNAPSHOT

**Schnappschüsse · Implementiert / Phase 1**

- Grenze: `SnapshotRepository` → `FileProjectRepository`.
- Ein-/Ausgabe: Projekt und Name → unveränderliche SnapshotInfo; Load liefert ein abgetrenntes Projekt. Wiederherstellen läuft als Replace-Command mit geprüftem Ausgangsstand.
- Ausführung und Fehler: IO-Dispatcher; Payload wird vor Metadaten veröffentlicht. Snapshot-IDs sind validiert.
- Abnahme: Snapshot beeinflusst den Live-Head nicht; Wiederherstellung ist undo-fähig; fehlerhafte IDs werden abgelehnt.

## C-ASSET

**Assets · Implementiert / Phase 1**

- Grenze: `AssetStore` → `FileAssetStore`.
- Ein-/Ausgabe: Bytes, MIME-Typ und Rechteherkunft → SHA-256-Descriptor; get prüft den Inhalt erneut.
- Ausführung und Fehler: IO-Dispatcher, unveränderliche Dateien, maximal 64 MiB pro Asset. Deduplizierung nach Inhalt.
- Abnahme: Identische Bytes erhalten denselben Hash; Korruption und fehlende Dateien liefern Fehler. Keine automatische Rechtefreigabe.

## C-NATIVE

**Natives Projektformat · Implementiert / Phase 1**

- Grenze: `NativeProjectExchange` → `FileNativeProjectExchange` / NativeProjectCodec.
- Ein-/Ausgabe: `.riffproj` enthält manifest.json, project.json und assets/<sha256>. Manifest benennt Format, Schema, Projekt-Hash und alle Assets. Import erhält eine neue Projekt-ID; interne IDs bleiben stabil.
- Ausführung und Fehler: 64 MiB komprimiert, 64 MiB pro Eintrag, 256 MiB gesamt, 1024 Einträge, JSON-Tiefe 128. Ausschließlich erlaubte Dateinamen; keine Pfade aus dem Archiv werden extrahiert. Vollständige Prüfung vor Asset-Übernahme und Projekt-Commit.
- Abnahme: Roundtrip einschließlich Originalbytes und Parametern; Traversal, Hashfehler, fehlende Assets, Bomben und neuere Versionen werden abgelehnt. Tests: NativeArchiveTest.

## C-SETTINGS

**Einstellungen · Teilweise implementiert / Phase 1, B**

- Grenze: `SettingsRepository` → `FileSettingsRepository`.
- Ein-/Ausgabe: AppSettings → atomar gespeicherter Stand und StateFlow. Theme und Auto-Advance sind aktiv; strikte Exportprüfung und benutzerdefinierte Shortcuts folgen.
- Ausführung und Fehler: IO-Dispatcher; transformierende UI-Updates werden serialisiert. Korruption wird angezeigt.
- Abnahme: Theme/Auto-Advance überleben Neustart. Eine fehlgeschlagene Speicherung verändert den veröffentlichten Stand nicht.

## C-DOCUMENT

**Android-Dateiauswahl · Implementiert / Phase 1**

- Grenze: `Documents`, OpenDocument/CreateDocument im Composition Root.
- Ein-/Ausgabe: Content-URI → begrenzte Bytes; Exportbytes → Provider-Stream. Keine URI im Musikmodell und keine breite Speicherberechtigung.
- Ausführung und Fehler: IO-Dispatcher. Abbruch des Pickers hat keine Projektwirkung. Provider-Ausgabe kann selbst nicht atomar garantiert werden; das private Original bleibt erhalten.
- Abnahme: Eigene Datei speichern und über einen anderen Provider öffnen; Permission-/I/O-Fehler anzeigen. Geräteabnahme offen.

## C-EDITOR

**Editortransaktionen · Implementiert**

- Grenze: `EditorSession`, EditorCommand, ScoreValidator; reine Bearbeitungsfunktionen in NoteEdits, StructureEdits und TrackEdits.
- Ein-/Ausgabe: Command und aktuelle Auswahl (Cursor, Stimme, Saite, optional Taktbereich) → neue immutable Revision oder Validation-Fehler. Undo/Redo verwendet Musik-Snapshots, erhöht jedoch weiterhin die Revisionsnummer.
- Ausführung und Fehler: Ein Eigentümer auf dem UI-Dispatcher. Keine Repository- oder Renderer-Mutation innerhalb von Commands. Maximal 200 Undo-Stände; neue Änderung verwirft Redo. Notenwert- und Tuplet-Änderungen rücken spätere Beats der Stimme nach (Ripple im Takt) und scheitern sichtbar, wenn der Takt überläuft. Eingetippte Bünde behalten die Dauer eines vorhandenen Beats.
- Abnahme: Noteneingabe, vier Stimmen, Akkorde, Artikulationen, Ties, Takte, Spuren und Mixer ändern sich rückgängig. Ungültige Änderungen behalten Musik und Historie. Tests: EditorSessionTest, EditorExtensionsTest.

## C-ARRANGEMENT

**Strukturaktionen · Implementiert für Takte, Bereiche und Spuren; Drag-Ripple über Gesten offen / B**

- Grenze: EditorCommand (InsertMeasures, DeleteMeasures, DuplicateMeasures, PasteBars, ClearBars, SetRepeat, SetNavigation, SetKey, SetFeel, SetTempoAt, SetSection, DuplicateTrack) und `DefaultAdvancedEditor` für abgetrennte Vorschläge.
- Ein-/Ausgabe: Taktbereich und Zielspur → neue Revision. Kopieren vergibt neue IDs und hält Notenlinks innerhalb des kopierten Bereichs; externe Links werden entfernt. Einfügen übernimmt Taktarten und verschiebt Saiten oben bündig zwischen 6/7/8-Saitern.
- Ausführung und Fehler: Löschen verschiebt den gültigen Tempowechsel auf den nächsten verbleibenden Takt, entfernt Abschnitte, Texte, Automationspunkte und Sync-Anker der gelöschten Takte und leert verwaiste Sprungziele. Vorschläge (`propose`) prüfen die Ausgangsrevision und werden per Replace publiziert.
- Abnahme: Bereich duplizieren, löschen, kopieren/einfügen; ein Undo stellt alles zurück. Touch-Drag mit Ripple-Vorschau ist noch nicht angebunden. Tests: EditorExtensionsTest.

## C-ARTICULATION

**Artikulationen · Eingabe, Gravur und hörbare Umsetzung implementiert; Hörabnahme offen / C**

- Grenze: `Articulation`, PitchCurvePoint, NoteLink; EditorCommand.ApplyArticulation/ClearArticulation/ToggleTie; ScorePlaybackCompiler und StringVoice.
- Ein-/Ausgabe: Typisierte Technikparameter → Noten-/Beatänderung. Jede Art hat einen Slot (eine Biegung, ein Slide …); Exklusivgruppen (Hammer/Pull, Akzent/stark, Staccato/Let Ring, Ghost/Dead). Bend-Punkte verwenden rationale Position und Cents.
- Ausführung und Fehler: Palm-Mute und Dead Notes ändern Dämpfung und Anregung des Saitenmodells, Flageoletts klingen auf der Obertonreihe, Bends/Slides/Vibrato sind Tonhöhenkurven der einzelnen Saitenstimme, Hammer-on/Pull-off/Legato-Slide/Triller schlagen nicht neu an, Let Ring klingt bis zum nächsten Anschlag derselben Saite.
- Abnahme: Automatisiert: Tonhöhe, Abklingen PM/Dead, unabhängige Bends, Flageolett-Tonhöhe (SynthEngineTest, SequenceCompilerTest). Hörtest mit Gitarrist:innen und Vergleich mit GP8 ausstehend.

## C-INSTRUMENT

**Instrumente und Bibliotheken · Implementiert**

- Grenze: InstrumentDefinition, RetuningPolicy, EditorCommand.SetInstrument/MoveToString/Transpose, TuningPresets, BuiltInChordLibrary, BuiltInScaleLibrary.
- Ein-/Ausgabe: Stimmung, Saitenzahl, Capo und explizite KEEP_FINGER_POSITION/KEEP_SOUNDING_PITCH-Regel → validierte neue Positionen oder ein Fehler mit den unspielbaren Tönen. Akkordgriffe werden für die tatsächliche Stimmung gesucht (Grundton im Bass, höchstens vier Finger, Lücken begrenzt).
- Ausführung und Fehler: Kein stilles Verschieben unspielbarer Töne; zusätzliche Saiten werden oben bündig angefügt (6→7 ergänzt die tiefe Saite). Teil-Capo bleibt im Modell pro Saite.
- Abnahme: 6/7/8-Saiter, Bass, Capo, Transposition und unspielbare Fälle (EditorExtensionsTest). Teil-Capo-Bedienung fehlt in der UI.

## C-DRUM

**Drums und Band · Implementiert**

- Grenze: Beat/Note mit MIDI-Pitch, DrumElement, PercussionTechnique; EditorCommand.ToggleDrum/SetVelocity; DrumVoice.
- Ein-/Ausgabe: Zelle (Spur, Takt, Stimme, exakter Offset, MIDI-Note) → derselbe Score-Beat. Raster 1/8, 1/16, 1/32 und Triolen. Velocity, Ghost, Rimshot, Cross-Stick, Choke und offene Hi-Hat pro Schlag.
- Ausführung und Fehler: Hi-Hat-Chokes (geschlossen/Pedal beendet offen), Felldämpfung beim erneuten Anschlag, Element-Lautstärke/-Pan aus dem Kit (im Drumgrid pro Element einstellbar, eine Transaktion pro Geste). Synthetisierte Klänge ohne Samples.
- Abnahme: Kick und HH am selben Beat setzen; Kick entfernen ohne HH-Verlust; Undo (ApplicationFlowTest, SynthEngineTest); Element-Pegel/-Pan im Plan (SequenceCompilerTest). Hörvergleich offen.

## C-NOTATION

**Notation · Eigener Notensatz implementiert; alphaTab bewusst nicht eingebunden**

- Grenze: `ScoreLayouter` (core:notation) → ScoreLayout mit Anzeigeliste und Hit-Test-Geometrie; `ScoreCanvas`/`GlyphPainter` (ui:score), SvgExport, PdfExport. AlphaTabBoundary bleibt UNAVAILABLE.
- Ein-/Ausgabe: Projekt, Spur, Stil, optional Breite und Eingaberaster → Glyphen, Takte, Beat-Spalten, Saitenpositionen, Notenboxen. Auswahl und Playhead sind Overlays ohne Neu-Layout.
- Ausführung und Fehler: Layout auf einem Worker; 200 Takte × 16 Noten ≈ 35 ms auf der JVM. TAB mit Rhythmus und Techniken, Standardnotation mit Schlüsseln, Vorzeichen, Balken, Hilfslinien und Ties, Schlagzeugsystem. Entscheidung gegen alphaTab 1.9.0: verlangt SDK 37/Kotlin 2.4, eigenes Eingabemodell nötig, MPL-Pflichten; die eigene Engine teilt IDs und Zeitmodell direkt.
- Abnahme: Strukturtests und visuelle PNG-Prüfung (LayoutTest). Gravurqualität gegenüber GP8, Slash/Jianpu und Lyrics-Satz offen.

## C-GESTURE

**Gesten · Zustandsmaschine implementiert; Touch-Drag-Verbindung Scaffold / B**

- Grenze: GestureTransaction und DragPreview.
- Ein-/Ausgabe: Idle → Armed → Preview → Committed oder Cancelled. Vorschau referenziert keine Repositories und keinen Audiotransport.
- Ausführung und Fehler: Zweite Berührung/Pinch, Back/Escape und verlorener Pointer brechen ab. Scroll auf freier Fläche; Bereichsgriffe mit Autoscroll. Alternativen über Formulare und Tastatur.
- Abnahme: Keine Autosaves, Audioänderungen oder Undo-Einträge vor Commit; kompletter Drag genau ein Undo. Tests: EditorSessionTest.cancelledGestureNeverPublishesAndOneCommitMeansOneUndo.

## C-REPEAT

**Wiederholungsauflösung · Implementiert**

- Grenze: RepeatCompiler → `UnfoldingRepeatCompiler`.
- Ein-/Ausgabe: Geschriebene Takte und Navigationsmarken → endliche PlaybackOccurrence-Liste mit Durchlauf und Routenindex. `endCount` ist die Gesamtzahl der Durchläufe.
- Ausführung und Fehler: Volten pro Durchlauf; Abschnitt beginnt beim letzten Start-Takt oder nach dem vorigen abgeschlossenen Abschnitt; D.C./D.S. werden einmal genommen, danach keine Wiederholungen und die letzte Volta; To Coda/Fine gelten nach dem Sprung. Routenlimit 20 000 Takte; D.S. ohne Segno scheitert mit Diagnose.
- Abnahme: Goldene Routen für Wiederholung, Volten, drei Durchläufe, implizite Abschnitte, D.C. al Fine, D.S. al Coda (RouteCompilerTest).

## C-TEMPO

**Tempo-Abbildung · Implementiert**

- Grenze: TempoMapper → `TimelineTempoMapper`; `Timing` für Takt-Zeitkarten und RouteSpan.
- Ein-/Ausgabe: PlaybackOccurrence → Mikrosekunden; Plan-Frames ↔ Taktposition in beide Richtungen.
- Ausführung und Fehler: Tempo folgt der geschriebenen Reihenfolge (ein D.C. nutzt wieder das Tempo von Takt 1). Tempowechsel innerhalb von Takten, Swing/Triolen-Feel als stückweise lineare Verzerrung, Fermaten dehnen alle Spuren. Bruchteil-Frames verhindern Drift.
- Abnahme: Goldene Frame-Positionen (SequenceCompilerTest).

## C-SYNC

**Score-/Audio-Sync · SCORE_TEMPO-Modus mit Offset implementiert; Anker-Modus offen / C**

- Grenze: AudioClip.offsetMicros und ClipSet (Engine), AudioSyncMapper → `AnchorSyncMapper`, SyncMap, SyncAnchor.
- Ein-/Ausgabe: Clipbeginn in Planframes = Partiturbeginn + Offset. Die Anker-Abbildung rechnet Position samt Durchlauf → Audiozeit, stückweise linear zwischen Ankern, außerhalb mit der Rate des nächsten Segments.
- Ausführung und Fehler: Im Modus SCORE_TEMPO folgt das Audio der Engine-Uhr; Loop, Seek und Count-in bleiben sample-genau, solange das Partitur-Tempo zur Aufnahme passt. AUDIO_REFERENCE (Partitur folgt Ankern einer frei gespielten Aufnahme) ist berechnet, aber noch nicht an die Engine angeschlossen.
- Abnahme: Offset, Loop-Wiederholung und Solo (AudioClipTest), Anker-Abbildung (SequenceCompilerTest). Hörprobe mit echter Aufnahme auf dem Gerät offen.

## C-SEQUENCER

**Playback-Compiler · Implementiert**

- Grenze: PlaybackCompiler → `ScorePlaybackCompiler` mit PlaybackOptions (Geschwindigkeit).
- Ein-/Ausgabe: Projekt + Samplerate → immutable PlaybackPlan mit Route, Spans, Metronom-Klicks und einem ScheduledNote je gespielter Note (eigene Tonhöhenkurve, Anschlagsart, Technik, Saite, Choke-Gruppe).
- Ausführung und Fehler: Vorberechnung auf Worker. Ties über Taktgrenzen, Hammer/Pull/Legato, Slides mit Zielton, Let Ring mit Saiten-Choke, Grace Notes, Tremolo Picking, Triller, Strums, Dynamik, Akzente, Drum-Artikulationen. Automation wird nicht ausgewertet.
- Abnahme: SequenceCompilerTest; unabhängige Bends auf zwei Saiten.

## C-AUDIO

**Audio-Engine · Implementiert (Kotlin, AudioTrack); Gerätemessungen offen**

- Grenze: Transport → `EngineTransport` über `RealtimeHost` und `SynthEngine`; AudioSink → `AudioTrackSink`.
- Ein-/Ausgabe: Prepare/Play/Pause/Seek/Loop/Stop, Geschwindigkeit, Metronom, Count-in. Position aus der Hardware-Sample-Clock (AudioTrack-Timestamps), nie aus einem UI-Timer.
- Ausführung und Fehler: Eigener Render-Thread mit Audio-Priorität, Float-PCM, Low-Latency-Modus, native Rate/Burst. Befehle über lock-freie Queue, Pläne werden blockgenau getauscht. Render-Pfad ohne Allokation, I/O oder Locks; Flush-to-Zero gegen Subnormal-Einbrüche. Audio-Fokus und Kopfhörer-Abziehen pausieren. Nach 20 s Leerlauf wird das Gerät freigegeben. Entscheidung: Kotlin statt Oboe/NDK, weil die Engine dadurch auf der JVM testbar bleibt; Umstieg bleibt über AudioSink möglich.
- Abnahme: Automatisiert mit virtueller Senke (AudioAdapterTest, SynthEngineTest). Latenz, Underruns, 30-min-Stresstest und Route-Wechsel auf Xiaomi 15 offen.
- Klangkette E-Gitarre: DI-Samples (C-SOUNDBANK) oder modellierte Saite → AmpSim (1–3 asymmetrische Soft-Clip-Stufen je nach Gain, Koppel-Hochpass, Miller-Tiefpass, 4× Oversampling, Tonestack, Endstufensättigung) → Cabinet als partitionierte FFT-Faltung (Block 64, 1024 Taps bei 48 kHz). Die Box-IRs sind eigene Entwürfe (minimalphasig), keine Messungen.
- Messung: `RealtimeHost.stats()` liefert Median/P99/Max der Renderzeit pro Block (Ring über 8192 Blöcke), Überläufe gegen das Blockbudget, Underruns, Ausgabelatenz aus Timestamps und Note-on→Ausgang. Die Seite „Audio-Diagnose“ (`AudioLab`) zeigt das live, fährt einen 1-/10-min-Stresstest mit 8 Gitarrenspuren (Palm Mutes, Akkorde, 32tel, Bends, Slides) inkl. PSS-Speicher, Thermalstatus und CPU-Takt und bietet einen RMS-angeglichenen Blindvergleich modelliert vs. gesampelt für Clean/Crunch/High Gain.
- JVM-Bench (Desktop, nicht Telefon): 8 Spuren gesampelt Median ≈ 0,5 ms, P99 ≈ 0,75 ms bei 4 ms Budget (192 Frames). Gerätewerte stehen aus.

## C-PREVIEW

**Notenvorschau · Implementiert; Latenz ungemessen**

- Grenze: PreviewPlayer → EngineTransport.
- Ein-/Ausgabe: Projekt, Spur und Note → kurze Stimme mit denselben Artikulationsregeln wie die Wiedergabe. Keine Transportänderung, keine Projektänderung.
- Ausführung und Fehler: Gerät wird erst beim ersten Vorhören geöffnet. Fehlende Ausgabe blockiert die Eingabe nicht.
- Abnahme: AudioAdapterTest; Ziel unter 70 ms p95 auf dem Gerät noch nicht gemessen.

## C-SOUNDBANK

**Soundbanks und Sampler · E-Gitarre implementiert (eigenes RWSB-Format) / D; SF2 offen**

- Grenze: `SampleBank` (core:synth, reines Kotlin) mit `SampleVoice`; SoundBankRepository/SF2 weiterhin UNAVAILABLE.
- Ein-/Ausgabe: `assets/banks/guitar-emily-di.rwsb` (RWSB v2 aus `tools/make_guitar_bank.py`, Messung in `tools/bank_analysis.py`, Nachbearbeitung mit `tools/tune_guitar_bank.py`) → Regionen mit Grundton, Tastenbereich, Velocity-Layer (3), Round Robin (3), gemessenem Abklingen, nahtloser Schleife sowie je Region gemessener Feinstimmung (Cent, Tonkörper 0,25–0,9 s) und Pegelnormierung (Gain); Release-Geräusche und gedämpfte Anschläge als eigene Regionen. v1-Banken werden weiter gelesen (Feinstimmung 0, Normierung beim Laden berechnet). Rechte siehe DEPENDENCIES.md.
- Ausführung und Fehler: Laden im IO-Dispatcher beim App-Start, Streaming-Parser mit Größengrenzen; fehlt oder bricht die Bank, bleibt die modellierte Saite aktiv (kein stummer Ausfall). Render: kubische Interpolation mit Pitch-Ratio aus Grundton, Feinstimmung und Notenkurve, Regionen-Gain am Stimmenpegel, Round Robin je Saite versetzt (Unisono auf zwei Saiten ist nie dieselbe Welle), Legato/Hammer/Pull/Slide als Überblendung ohne neuen Anschlag, Retrigger blendet die alte Stimme schnell aus, Bends/Vibrato über Pitch-Ratio. Palm Mute ist emuliert (Tiefpass + schnelles Abklingen), weil die Quelle keine PM-Aufnahmen hat; Dead Notes nutzen die gedämpften Aufnahmen; Flageoletts bleiben auf der modellierten Saite. Bass, Akustik und Drums bleiben synthetisch.
- Abnahme: SampleBankTest/AudioBenchTest (JVM), Hörvergleich und Gerätelast über die Diagnose-Seite offen.

## C-DECODE

**Audio-Decode · Implementiert (WAV in Kotlin, sonst MediaCodec) / C**

- Grenze: `AudioDecoder` (probe/decode mit `PcmSink`) → `AudioDecoders` aus `WavAudioDecoder` und `MediaCodecAudioDecoder`; `DecodingClipLoader` und `PcmBuilder` für Engine-PCM.
- Ein-/Ausgabe: Untrusted Bytes → Format (MIME, Rate, Kanäle, Dauer) bzw. 16-Bit-PCM-Blöcke → Stereo-PCM in Geräterate. WAV: PCM 8/16/24/32 Bit, Float 32/64 Bit, Extensible, 1–8 Kanäle; nicht nachgetragene Datengrößen werden auf die vorhandenen Bytes begrenzt.
- Ausführung und Fehler: Decodieren aus dem Speicher ohne Temporärdatei, blockweises Resampling während des Decodierens (nur das Ergebnis liegt im Speicher, 4 Byte pro Frame), Abbruch über Coroutine-Cancellation. Grenzen: 64 MiB Datei, 10 Minuten Wiedergabe. Kein Decoder oder beschädigte Daten → VALIDATION/CORRUPT mit Meldung, nie eine stille Spur.
- Abnahme: WAV-Formate, Resampling-Impulslage, Längenlimit, Fehlerfälle und Plattform-Weiterleitung (AudioDecodingTest), Streaming = Einmalumwandlung (AudioClipTest). MediaCodec-Decoder nur auf CI kompiliert; MP3/AAC/Ogg/FLAC-Gerätetest und Encoder-Delay offen.

## C-WAVEFORM

**Wellenform · Implementiert (eine Stufe, ~100 Peaks/s) / C**

- Grenze: WaveformCache → `StreamingWaveforms` (adapter:audio).
- Ein-/Ausgabe: Asset + Peak-Auflösung → Minimum/Maximum des Mono-Mixes pro Fenster samt Decoder-Rate.
- Ausführung und Fehler: Berechnung beim Decodieren ohne PCM-Puffer, außerhalb des UI-Threads; Ergebnis pro Asset-Hash und Auflösung gecacht (höchstens 16 Einträge). Beim Zeichnen wird nicht decodiert. Mehrstufige Pyramide für sehr lange Stems offen.
- Abnahme: Impulslage, Dauer, Cache und ungültige Auflösung (AudioDecodingTest). Antippen der Wellenform setzt Takt 1 im Clip.

## C-STRETCH

**Stretch und Pitch · Scaffold / D**

- Grenze: TimeStretchProcessor → PendingAudioServices.
- Ein-/Ausgabe: Asset, Geschwindigkeitsfaktor und Pitch-Cents → verarbeitetes Asset beziehungsweise vorbereitete Pipeline.
- Ausführung und Fehler: Signalsmith-Kandidat; Latenz/Preroll und deterministisches Seek müssen in denselben Transport eingehen. Tonhöhe und Tempo getrennt.
- Abnahme: 50–90% bei Drums und verzerrten Riffs; Loopknackser und Drift messen; Blindhörtest. Aktuell UNAVAILABLE.

## C-RENDER

**Offline-Export · Implementiert; Cubase-Abnahme offen**

- Grenze: OfflineRenderer → EngineTransport/`OfflineMixdown`; streamt WAV über ByteSink.
- Ein-/Ausgabe: Projekt + Samplerate/Bit-Tiefe/Tail → WAV-Mix oder isolierte Spur. Stems-ZIP: eine WAV pro Spur ohne Master-Kette und unabhängig vom Master-Fader (gerendert auf dem Werkspegel 0,8), Mix mit Master-Fader und Master-Kette; übersteuerte Dateien nennt die Exportmeldung, MIDI mit Tempo-Map, Liesmich.
- Ausführung und Fehler: Derselbe Compiler, dieselbe Engine und Effektkette wie die Wiedergabe. Alle Stems beginnen bei Frame 0 der aufgelösten Route und sind gleich lang. Die Effektlatenz (Cabinet-Faltung 64 Frames, ggf. Master-Kette) ist ausgeglichen wie in einer DAW: Planframe 0 liegt auf Frame 0 der Datei, Plan + Ausklang sind vollständig enthalten; Exportframe i = Live-Frame i + Latenz. Jede Spur hat ihren eigenen Rausch-Seed, daher ergibt die Summe der Stems den Mix ohne Master-Kette bei Werks-Master-Fader (bis auf Rundung und Stimmenraub bei vollen Stimmenpools). Abbruch liefert CANCELLED statt einer leeren Datei.
- Abnahme: Längen/Startpunkte getestet (SynthEngineTest, AudioAdapterTest, Qa21ExportAlignmentTest: Zeitlage, Σ Stems = Mix). Import in Cubase und Vergleich gegen den Mix ausstehend.

## C-MIDI

**MIDI-Eingabe · Scaffold / D**

- Grenze: MidiInput → PendingAudioServices.
- Ein-/Ausgabe: Geräte-ID → zeitgestempelter MIDI-Capture-Flow. Quantisierung ist ein expliziter nachgelagerter Editor-Schritt.
- Ausführung und Fehler: Android USB-MIDI separat vom Audio-Callback; Hotplug, Timestamp-Konvertierung und Verbindungsfehler behandeln.
- Abnahme: Noten, Velocity, mehrere Kanäle und Disconnect prüfen. Kein Event-Flow wird als aktive Verbindung dargestellt; connect aktuell UNAVAILABLE.

## C-LIVE

**Live-Eingang · Scaffold / D**

- Grenze: LiveInput und LiveRouting → PendingAudioServices.
- Ein-/Ausgabe: USB-/Line-in-Routing → Monitoring/Aufnahme nach expliziter Aktivierung und nötiger Berechtigung.
- Ausführung und Fehler: Keine Mikrofonberechtigung beim App-Start. Hardware-Monitoring über Kabel/USB testen; Bluetooth gesondert messen. Input und Ausgangslatenz separat.
- Abnahme: Interface-Wechsel, Fokusverlust, Wiederanlauf, Feedback-Vermeidung und Thermik. Aktuell UNAVAILABLE.

## C-PRACTICE

**Übemodus · Implementiert; Referenz-Audio nur bei 100 % Tempo**

- Grenze: PracticeSettings, SpeedTrainer, Transport und AudioClip.
- Ein-/Ausgabe: Geschwindigkeit 25–150 %, Count-in (0–2 Takte in der Oberfläche, 0–4 in Transport und Modell), Metronom, Loop über Taktbereich, Speed-Trainer, Cursor folgt der Wiedergabe. Referenzaufnahmen mit Offset, Pegel, Pan, Mute und Solo unter derselben Uhr wie der Synthesizer.
- Ausführung und Fehler: UI liest die Audio-Uhr; Geschwindigkeitswechsel kompiliert neu und bleibt an derselben musikalischen Position. Trainer erhöht nach N Loop-Durchläufen bis zum Zieltempo. Bei Tempo ≠ 100 % bleiben Clips stumm und die Audio-Seite sagt das, bis C-STRETCH umgesetzt ist.
- Abnahme: Loop, Count-in, Trainer (AudioAdapterTest), Clip-Synchronität (AudioClipTest), Import/Ausrichten/Entfernen im ViewModel (ReferenceAudioTest). Visuelles Metronom als Zählzeit-Punkte an der Audio-Uhr; Playback-Griffbrett offen.

## C-TUNER

**Stimmgerät · Implementiert; reale Saitenabnahme offen**

- Grenze: Tuner → `InputTuner` (YIN) über AudioInputSource → `AudioRecordSource`.
- Ein-/Ausgabe: Mikrofon nach Laufzeit-Berechtigung → TuningReading mit nächstem Ton, Cent-Abweichung, Konfidenz und Zeitstempel.
- Ausführung und Fehler: Analyse auf eigenem Thread, Dezimierung auf 12 kHz, Fenster 1024 (bis ca. 25 Hz hinab, also Drop-A- und 5-Saiter-Bass-Saiten). Mikrofon nur während der Messung. Unsichere Messungen (Konfidenz < 0,75) werden verworfen.
- Abnahme: Synthetische Töne 55–659 Hz (AudioAdapterTest). Reale tiefe Drop-A-Saite, Störgeräusche und Oktavfehler auf dem Gerät offen.

## C-TRANSCRIBE

**Transkriptionsvorschläge · Scaffold / optional D**

- Grenze: TranscriptionService → PendingTranscription.
- Ein-/Ausgabe: Audioasset → editierbarer Kandidat mit Konfidenz pro Note und Diagnosen; explizite Annahme erzeugt neues Projekt.
- Ausführung und Fehler: On-device Basic-Pitch-Kandidat separat evaluieren. Keine automatische Ableitung sicherer Fingersätze oder Metal-Artikulationen aus einem Mix behaupten.
- Abnahme: Monophone/polypone Fixtures, falsche Oktaven und unsichere Noten prüfen; keine ungeprüften Noten im aktiven Projekt. Aktuell UNAVAILABLE.

## C-FORMAT

**Fremdformate und Dateitreue · Implementiert; Desktop-Gegencheck offen / C**

- Grenze: `DefaultFormatCatalog` (Erkennung) über `FormatRegistry`: `GpBinaryImporter` (GP3/4/5), `GpxImporter` (GP6, experimentell), `GpifImporter` (GP7/GP8), `Gp7Exporter`, `MidiImporter`/`MidiExporter`, `MusicXmlImporter`/`MusicXmlExporter` (.musicxml und .mxl), `AsciiTabExporter`. PowerTab, TablEdit, ASCII-Import und PNG bleiben UNAVAILABLE (`PendingFormatAdapter`).
- Ein-/Ausgabe: Untrusted Bytes + IdSource → Projekt mit null Validator-Fehlern und Verlustbericht. Export liefert echte Bytes plus Verluste oder einen Fehler, nie eine leere Erfolgsdatei. MIDI exportiert die aufgelöste Wiedergabereihenfolge mit Tempo-Map (Tick 0 = Frame 0 der Stems).
- Ausführung und Fehler: Erkennung nach Inhalt (ZIP-Einträge, BCFZ/BCFS, GP-Kopf, MThd, XML-Wurzel; GP8 an GPIF-Version bzw. GP8-only-Abschnitten). Parser laufen auf einem Worker-Dispatcher; Limits für Eingabegröße, ZIP-Einträge, Entpackgröße, Verschachtelung, Zähler und Notenbudget. XML ohne DTD und Entitäten. Einzige Ausnahme: TablEdit (`.tef`) wird nur an der Endung erkannt und als nicht verfügbar gemeldet; rohes GPIF-XML ebenso. Beschädigte Daten → CORRUPT, Überschreitungen → RESOURCE_LIMIT, alte GP-Versionen → INCOMPATIBLE_VERSION, jeweils mit deutscher Meldung. Die Bibliothek speichert die Originalbytes als Asset und genau einen ImportProvenance-Eintrag. Unbekannte GPIF-Felder werden gemeldet, aber nicht als ForeignData wiedereingesetzt.
- Abnahme: GP3/4/5 gegen eigene PyGuitarPro-Orakel samt Verlustliste, Trunkierungs- und Mutationstests; GPIF-Rundreise, Sicherheits- und Container-Tests; MIDI/MusicXML/ASCII-Tests; Katalogtest exportiert nach GP7, MIDI, MusicXML und ASCII und importiert die Austauschformate wieder. Offen: Öffnen der GP7-Exporte in Guitar Pro 7/8 Desktop, Import echter Desktop-Dateien, MusicXML in anderen Notenprogrammen, MIDI in Cubase.

## C-INTEGRATION

**Externe Integrationen · Scaffold / D**

- Grenze: ExternalIntegration → PendingIntegration, IntegrationCatalog.
- Ein-/Ausgabe: Explizite Integration und Geräte-ID → Geräteinventar/Verbindung mit technischem und vertraglichem Status.
- Ausführung und Fehler: Fretlight und mySongBook lizenzabhängig. VST-Verfügbarkeit mobiler Binaries prüfen; AUv3 nur auf iOS. Keine Zugangsdaten, Kataloginhalte oder Markenassets eingebunden.
- Abnahme: Anbieter-/Gerätevertrag, Fehler und Disconnect prüfen. Integrationen derzeit UNAVAILABLE; keine automatische externe Kommunikation.

## C-WORKFLOW

**Befehle, Tastatur, Accessibility und Gerätestruktur · Implementiert bis auf eigene Kürzel**

- Grenze: DefaultCommandCatalog, Tastaturbehandlung im Arbeitsbereich, UI-Routen, AppSettings, FeatureRegistry.
- Ein-/Ausgabe: Tastatur (Ziffern, Enter, Pfeile, Bild auf/ab, Leertaste, P/X/G/H/I/T/L/S/B/V/N/D, Strg+Z/Y/S/C/V/D, Strg+↑/↓, F4) und Touch nutzen dieselben Befehle. Eigene Tastenbelegung und Befehlspalette folgen.
- Ausführung und Fehler: 48-dp-Aktionen, beschriftete Controls, Pfeil-/Saitenalternativen zum Antippen der Partitur; Querformat und Tablet mit seitlichem Eingabefeld und NavigationRail.
- Abnahme: Tastatur und Screenreader auf dem Gerät, 60 fps und <50 ms p95 Touch→Paint sind ungemessen.
