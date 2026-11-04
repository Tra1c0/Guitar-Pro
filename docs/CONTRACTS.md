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

**Editortransaktionen · Implementiert im kleinen Kern / Phase 1**

- Grenze: `EditorSession`, EditorCommand, ScoreValidator.
- Ein-/Ausgabe: Command und aktuelle Auswahl → neue immutable Revision oder Validation-Fehler. Undo/Redo verwendet Musik-Snapshots, erhöht jedoch weiterhin die Revisionsnummer.
- Ausführung und Fehler: Ein Eigentümer auf dem UI-Dispatcher. Keine Repository- oder Renderer-Mutation innerhalb von Commands. Maximal 200 Undo-Stände; neue Änderung verwirft Redo.
- Abnahme: Noteneingabe, vier Stimmen, Akkorde, Takte, Spuren und Mixer ändern sich rückgängig. Ungültige Änderungen behalten Musik und Historie. Tests: EditorSessionTest.

## C-ARRANGEMENT

**Erweiterte Strukturaktionen · Scaffold / B–C**

- Grenze: `AdvancedEditorService`, AdvancedEdit und EditProposal; PendingEditorServices.
- Ein-/Ausgabe: Bereich, Ziel, TRACK_ONLY/ALL_TRACKS/RIPPLE und erwartete Revision → abgetrennte Vorschau. Bestätigung publiziert genau einen Replace-Command.
- Ausführung und Fehler: Vorschau auf Worker; UI veröffentlicht nur bei passender baseRevision. ID-Neuvergabe und externe Notenlinks beim Kopieren prüfen. Ripple muss Marker, Tempo, Taktwechsel und Sync-Anker mit Vorschau verschieben.
- Abnahme: Bereich duplizieren, verschieben, löschen und Clipboard roundtrippen; Abbruch ohne Save/Audio; ein Undo stellt alles zurück. Bis dahin UNAVAILABLE.

## C-ARTICULATION

**Artikulationen · Palm-Mute/Dead-Note-Kern implementiert; Rest Scaffold / B–D**

- Grenze: `Articulation`, PitchCurvePoint, NoteLink; AdvancedEditorService für komplexe Bearbeitung.
- Ein-/Ausgabe: Typisierte Technikparameter → Noten-/Beatänderung. Bend-Punkte verwenden rationale Position und Cents, natürliche/künstliche/Pinch-Harmonics bleiben verschieden.
- Ausführung und Fehler: Editier- und Gravurunterstützung getrennt von hörbarem Mapping. Parameterprüfung vor Commit; keine automatische Pitchbend-Kanalteilung im Modell.
- Abnahme: Metal-Referenzphrase darstellen/editieren; unterschiedliche Bends auf zwei Saiten getrennt halten. Hörtest und Sample-Mapping gesondert.

## C-INSTRUMENT

**Instrumente und Bibliotheken · Vorlagen und Griffbrett-Eingabe vorhanden; freie Bearbeitung/Bibliotheken Scaffold / B–C**

- Grenze: InstrumentDefinition, RetuningPolicy, ChordLibrary, ScaleLibrary und AdvancedEdit.Retune.
- Ein-/Ausgabe: Stimmung/Capo und explizite KEEP_FINGER_POSITION/KEEP_SOUNDING_PITCH-Regel → validierte neue Positionen oder sichtbare Konflikte. Suchdienste liefern Akkorde, Diagramme und Skalen.
- Ausführung und Fehler: Kein stilles Verschieben unspielbarer Töne. Vierstimmige Noten behalten ihre IDs. Kleinere Saitenzahl und Kollisionsfälle müssen vor Commit aufgelöst werden.
- Abnahme: 6/7/8-Saiter, Bass, Teil-Capo, Transposition und unspielbare Akkorde. Unfertige Bibliothekssuche liefert UNAVAILABLE.

## C-DRUM

**Drums und Band · Einfaches Stepgrid implementiert; Velocity/Chokes/ungerade Raster Scaffold / B–C**

- Grenze: Beat/Note mit MIDI-Pitch, DrumElement, PercussionTechnique; gemeinsamer EditorCommand.ToggleDrum.
- Ein-/Ausgabe: Zelle (Spur, Takt, Stimme, exakter Offset, MIDI-Note) → derselbe Score-Beat. Ein Toggle entfernt nur dieses Instrument.
- Ausführung und Fehler: 48-dp-Zellen; unabhängige Stimmen. Drum-Chokes gehören zusätzlich in die Engine, nicht nur in die Darstellung.
- Abnahme: Kick und HH am selben Beat setzen; Kick entfernen ohne HH-Verlust; Undo; später Ghost/Velocity/Chokes und ungerade Unterteilungen prüfen.

## C-NOTATION

**Notation und alphaTab · Eigene TAB implementiert; alphaTab/weitere Notation Scaffold / Spike A, C**

- Grenze: `NotationRenderer`, HitTestProvider, AlphaTabBoundary; aktive einfache Compose-ScoreView.
- Ein-/Ausgabe: Projekt + Breite → revisionsgebundene Darstellung und Element-Bounds. UI-IDs bleiben eigene IDs. Die aktive TAB-Ansicht virtualisiert Takte.
- Ausführung und Fehler: alphaTab AndroidView erst nach Toolchain-Abgleich. includeNoteBounds aktivieren; Scroll-/Zoom-Offsets zentral umrechnen. Strukturänderungen benötigen Finish/Relink; Rendering und MIDI-Cache getrennt invalidieren. Stale Render-Ergebnisse verwerfen.
- Abnahme: Select/Edit/Undo/Rerender inklusive zweistelliger Bünde. Kein Versprechen partieller Rerenders vor Messung. alphaTab.net-Attribution erhalten.

## C-GESTURE

**Gesten · Zustandsmaschine implementiert; Touch-Drag-Verbindung Scaffold / B**

- Grenze: GestureTransaction und DragPreview.
- Ein-/Ausgabe: Idle → Armed → Preview → Committed oder Cancelled. Vorschau referenziert keine Repositories und keinen Audiotransport.
- Ausführung und Fehler: Zweite Berührung/Pinch, Back/Escape und verlorener Pointer brechen ab. Scroll auf freier Fläche; Bereichsgriffe mit Autoscroll. Alternativen über Formulare und Tastatur.
- Abnahme: Keine Autosaves, Audioänderungen oder Undo-Einträge vor Commit; kompletter Drag genau ein Undo. Tests: EditorSessionTest.cancelledGestureNeverPublishesAndOneCommitMeansOneUndo.

## C-REPEAT

**Wiederholungsauflösung · Lineare Route vorhanden; echte Wiederholungen Scaffold / C**

- Grenze: RepeatCompiler → LinearRepeatCompiler für rein lineare Projekte.
- Ein-/Ausgabe: Geschriebene Takte und Navigationsmarken → endliche PlaybackOccurrence-Liste mit Durchlauf und Routenindex.
- Ausführung und Fehler: Budget für Sprünge und ausgegebene Takte. DC/DS/Coda/Fine und alternative Enden dürfen keinen unbegrenzten Ablauf erzeugen. Unterstützte Route darf nicht geraten werden.
- Abnahme: Reine Linearroute geprüft; bei vorhandenen Wiederholungen aktuell UNAVAILABLE. Später goldene Routen inklusive verschachtelter Wiederholungen.

## C-TEMPO

**Tempo-Abbildung · Scaffold / Spike B, C**

- Grenze: TempoMapper → PendingTempoMapper.
- Ein-/Ausgabe: PlaybackOccurrence + Tempo-Map → Mikrosekunden; inverse Abbildung bei der Engine-Einbindung ergänzen.
- Ausführung und Fehler: Worker; exakt rational bis zur definierten Rundung auf Mikrosekunde/Sample. Auftakt, Tempoänderungen und Swing nicht über UI-Timer berechnen.
- Abnahme: Goldene Beat-/Sample-Positionen über nichtganzzahlige BPM; monotone Abbildung und definiertes Seek. Aktuell UNAVAILABLE.

## C-SYNC

**Score-/Audio-Sync · Scaffold / C**

- Grenze: AudioSyncMapper, SyncMap, SyncAnchor.
- Ein-/Ausgabe: Geschriebene Position samt Durchlauf und ausgewählte Sync-Map → Audiozeit. Gemeinsame Stem-Map und individueller Clip-Offset bleiben getrennt.
- Ausführung und Fehler: Worker; Score-Tempo und Audio-Referenz als explizite Modi. Anker-Monotonie, Wiederholungsroute und Änderungen der Tempo-Map prüfen. Kein stilles Uminterpretieren.
- Abnahme: Anker an Transiente/Beat/Takt setzen, Offset ändern, Loop/Seek und Wiederholungen prüfen. Aktuell UNAVAILABLE.

## C-SEQUENCER

**Playback-Compiler · Scaffold / Spike B, B–D**

- Grenze: PlaybackCompiler → PendingPlaybackCompiler.
- Ein-/Ausgabe: Projekt + Samplerate → immutable PlaybackPlan mit Revision, Route und samplegenauen Events.
- Ausführung und Fehler: Vorberechnung auf Worker. Tuplets, Swing, Ties, Grace, Fermaten und Automation hier abbilden. Seek benötigt Instrument-/Controllerzustand und definierte Ausklänge.
- Abnahme: PCM-/Event-Fixtures für Rhythmus, gehaltene Noten und unabhängige Bends; UI-FPS darf Transport nicht beeinflussen. Aktuell UNAVAILABLE.

## C-AUDIO

**Native Audio-Engine · Scaffold / Spike B, B–D**

- Grenze: Transport und AudioEngine → PendingAudioServices.
- Ein-/Ausgabe: Prepare → STOPPED; Play → PLAYING; Pause/Seek/Loop/Stop; Gerätefehler → INTERRUPTED/FAILED. Aktuell ausschließlich UNAVAILABLE.
- Ausführung und Fehler: Native Sample-Clock mit Ausgabetimestamps. Oboe-Kandidat; begrenzte Eventqueues/Snapshots. Callback ohne Allokationen, Locks, I/O oder JNI pro Sample. Geräte-/Fokuswechsel über Control-Thread.
- Abnahme: Synth, Metronom und Stem unter einer Clock, sampledefinierte Loops, Seek und 30-min-Stresslauf. Kein simuliertes Playback in Phase 1.

## C-PREVIEW

**Notenvorschau · Scaffold / B**

- Grenze: PreviewPlayer → PendingAudioServices.
- Ein-/Ausgabe: Note + Instrument → kurzer, artikulationsabhängiger Preview-Voice oder Unavailable. Keine Transportänderung.
- Ausführung und Fehler: Control-Thread plant den Voice; Messpunkte für Touch→Preview und tatsächlichen Ausgabetimestamp. Fehlende Engine blockiert Noteneingabe nicht.
- Abnahme: Unter 70 ms p95 auf Xiaomi 15 über Kabel/USB als Ziel; PM/Dead/Harmonic getrennt hören. Aktuell ungemessen.

## C-SOUNDBANK

**Soundbanks und Sampler · Scaffold / D**

- Grenze: SoundBankRepository, SoundBank/Preset/SampleRegion.
- Ein-/Ausgabe: Eigene/lizenzierte SF2/SF3/Artikulationsbank → validiertes Inventar; Mapping von GM-Programmen und Artikulationen ausdrücklich ausweisen.
- Ausführung und Fehler: Off-thread lesen und vorbereiten. Rechte pro SF2, DI-Sample, Release, IR, Amp-Modell und Preset dokumentieren. Keine Arobas-RSE-Inhalte übernehmen.
- Abnahme: Velocity-Layer, Round Robins, Releases, Palm-Mute-Aufnahmen und separate Dead-Note-Samples. CPU/Polyphonie/Hörvergleich mit GM und DI+Amp. Aktuell UNAVAILABLE.

## C-DECODE

**Audio-Decode · Scaffold / C–D**

- Grenze: AudioDecoder → PendingAudioServices.
- Ein-/Ausgabe: Asset + Ziel-Samplerate → gecachtes PCM-Asset mit Decoder-Metadaten.
- Ausführung und Fehler: Worker; blockweiser Decode/Resample, begrenzte Puffer. Encoder-Delay, Seek-Preroll und unterschiedliche Raten explizit berücksichtigen.
- Abnahme: WAV/MP3/OGG-Kandidaten mit bekannten Impulsen und Seek-Positionen. Abbruch räumt temporäre Daten auf. Aktuell UNAVAILABLE.

## C-WAVEFORM

**Wellenform · Scaffold / C**

- Grenze: WaveformCache → PendingAudioServices.
- Ein-/Ausgabe: Asset + Peak-Auflösung → Minimum/Maximum pro Fenster, mehrstufig gecacht.
- Ausführung und Fehler: Worker; Asset-Hash, Decoder-Version und Auflösung bilden Cache-Schlüssel. Kein Decode beim Zeichnen.
- Abnahme: Impulse in allen Zoomstufen korrekt lokalisieren; lange Stems ohne UI-Blockade. Aktuell UNAVAILABLE.

## C-STRETCH

**Stretch und Pitch · Scaffold / D**

- Grenze: TimeStretchProcessor → PendingAudioServices.
- Ein-/Ausgabe: Asset, Geschwindigkeitsfaktor und Pitch-Cents → verarbeitetes Asset beziehungsweise vorbereitete Pipeline.
- Ausführung und Fehler: Signalsmith-Kandidat; Latenz/Preroll und deterministisches Seek müssen in denselben Transport eingehen. Tonhöhe und Tempo getrennt.
- Abnahme: 50–90% bei Drums und verzerrten Riffs; Loopknackser und Drift messen; Blindhörtest. Aktuell UNAVAILABLE.

## C-RENDER

**Offline-Export und Cubase · Scaffold / B–D**

- Grenze: OfflineRenderer, RenderOptions → PendingAudioServices.
- Ein-/Ausgabe: Projekt + Samplerate/Bit-Tiefe/FX/Tail/Start → echte WAV-Dateien und synchrone Stems. MIDI und Tempo-Map separat.
- Ausführung und Fehler: Derselbe Event-Compiler und Mixgraph wie Playback. Alle Stems identischer Ursprung, Samplezahl und dokumentierter FX-/Master-Status. Abbruch statt leerer Erfolgsdatei.
- Abnahme: In Cubase an identischem Ursprung importieren, Referenzmix vergleichen und Impulse/Tempo prüfen. Aktuell UNAVAILABLE.

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

**Übemodus · Scaffold / B–D**

- Grenze: PracticeSettings, SpeedTrainer, CursorMode und Transport.
- Ein-/Ausgabe: Geschwindigkeit, Count-in, Metronom, Loop und Trainer → Transportkonfiguration; Abspielposition enthält Durchlauf.
- Ausführung und Fehler: UI liest die Audio-Uhr; visuelles Metronom folgt ihr. Synth- und Stem-Mute getrennt. Eingabe bleibt offline verfügbar.
- Abnahme: 70%-Loop, Trainersteigerungen, Count-in und mitlaufender/fixierter Cursor; Playback-Griffbrett/Keyboard erst mit echten Events. Steuerelemente aktuell deaktiviert.

## C-TUNER

**Stimmgerät · Scaffold / C–D**

- Grenze: Tuner → PendingTuner.
- Ein-/Ausgabe: Explizit gewählter Eingang → TuningReading mit Tonhöhe, Abweichung, Konfidenz und Timestamp.
- Ausführung und Fehler: Pitchanalyse auf Worker; Start behandelt Berechtigung, Gerätewechsel und Stille. Keine sichere Tonhöhe ohne ausreichendes Signal behaupten.
- Abnahme: Synthetische bekannte Töne plus reale tiefe Drop-A-Saite; Oktavfehler und Störgeräusche. Aktuell UNAVAILABLE.

## C-TRANSCRIBE

**Transkriptionsvorschläge · Scaffold / optional D**

- Grenze: TranscriptionService → PendingTranscription.
- Ein-/Ausgabe: Audioasset → editierbarer Kandidat mit Konfidenz pro Note und Diagnosen; explizite Annahme erzeugt neues Projekt.
- Ausführung und Fehler: On-device Basic-Pitch-Kandidat separat evaluieren. Keine automatische Ableitung sicherer Fingersätze oder Metal-Artikulationen aus einem Mix behaupten.
- Abnahme: Monophone/polypone Fixtures, falsche Oktaven und unsichere Noten prüfen; keine ungeprüften Noten im aktiven Projekt. Aktuell UNAVAILABLE.

## C-FORMAT

**Fremdformate und Dateitreue · Scaffold / Spike A, B–D**

- Grenze: FormatDetector, ScoreImporter, ScoreExporter; PendingFormatAdapter.
- Ein-/Ausgabe: Inhaltsbasierte Familienerkennung, begrenzte Bytes + IdSource → Projekt/Verlustbericht. Export liefert echte Bytes oder Fehler. GP7-Ziel und GP8-Treue separat.
- Ausführung und Fehler: GP3–5 binär, GP6 BCFS/BCFZ, GP7/8 ZIP/GPIF. Parser auf Worker, Ressourcenlimits, XML ohne DTD/externen Entitäten. Originalbytes bewahren. Unbekannte Felder nur nach Referenz- und Semantikprüfung wiedereinsetzen.
- Abnahme: GP-Original/Edit/Export/Reimport auf Track/Beat/Note vergleichen. MusicXML/MIDI/ASCII/PowerTab/TablEdit und PDF/PNG/SVG pro Fähigkeit testen. Alle GP-Adapter aktuell UNAVAILABLE.

## C-INTEGRATION

**Externe Integrationen · Scaffold / D**

- Grenze: ExternalIntegration → PendingIntegration, IntegrationCatalog.
- Ein-/Ausgabe: Explizite Integration und Geräte-ID → Geräteinventar/Verbindung mit technischem und vertraglichem Status.
- Ausführung und Fehler: Fretlight und mySongBook lizenzabhängig. VST-Verfügbarkeit mobiler Binaries prüfen; AUv3 nur auf iOS. Keine Zugangsdaten, Kataloginhalte oder Markenassets eingebunden.
- Abnahme: Anbieter-/Gerätevertrag, Fehler und Disconnect prüfen. Integrationen derzeit UNAVAILABLE; keine automatische externe Kommunikation.

## C-WORKFLOW

**Befehle, Accessibility und Gerätestruktur · Grundstruktur/Tastatur teilweise implementiert; Rest B–D**

- Grenze: DefaultCommandCatalog, UI-Routen, AppSettings, FeatureRegistry.
- Ein-/Ausgabe: Suchbare CommandDescriptor-Einträge; Befehlspalette und Custom-Shortcuts folgen. Tastaturereignisse benutzen denselben Dispatcher wie Touch.
- Ausführung und Fehler: 48-dp-Aktionen, beschriftete Controls, Fokus-/Pfeilalternativen; Querformat und Tablet mit eigener Anordnung. Long Press bleibt optionaler Shortcut. Performance getrennt nach Touch→Paint/Preview erfassen.
- Abnahme: Tastatur und Screenreader, 411-dp-Handy und 1280-dp-Tablet prüfen. 60 fps und <50 ms p95 Touch→Paint sind ungemessene Ziele.
