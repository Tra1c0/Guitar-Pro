# Architektur und Zustandsflüsse

## Entscheidungen

Diese Entscheidungen wurden bei der Fertigstellung bewusst getroffen. Jede ist über einen Port umkehrbar, ohne das Musikmodell umzubauen.

1. **Native Android-App mit Kotlin/Compose, portabler Kern.** Modelle, Editor, Wiedergabe, Synthesizer und Notensatz sind gewöhnliche Kotlin/JVM-Module ohne Android-, Java- oder Drittanbieterimporte (von `verifyArchitecture` geprüft). Eine spätere Kotlin-Multiplatform-Umstellung braucht keine neuen musikalischen Schnittstellen.
2. **Eigener Notensatz statt alphaTab.** alphaTab 1.9.0 verlangt laut Release-Quellen SDK 37 und Kotlin-stdlib 2.4; das passt nicht zur gepinnten Toolchain. Außerdem müsste jede Bearbeitung über Bounds zurück auf eigene IDs übersetzt werden. `core:notation` setzt direkt aus dem Modell, erzeugt eine renderer-neutrale Anzeigeliste mit Trefferflächen und dient Bildschirm, SVG, PDF und Tests gleichermaßen. `adapter:alphatab` bleibt als erklärte UNAVAILABLE-Grenze bestehen.
3. **Eigener Synthesizer statt SoundFont/RSE.** E-Gitarren klingen aus einer eigenen Sample-Bank (CC0, Karoryfer Emilyguitar, RWSB-Format); für alles andere gibt es keine mitlieferbare Bank mit klaren Rechten. `core:synth` erzeugt Saiten physikalisch (erweitertes Karplus-Strong mit Bruchteil-Verzögerung), dadurch sind Bends, Vibrato, Palm-Mute, Dead Notes, Flageoletts, Legato und Saiten-Chokes pro Note möglich, ohne Kanal-Controller. Das Schlagzeug spielt ein gesampeltes Kit (ArtiFret Pearl Kit, abgeleitet von AVL Black Pearl 4, CC BY-SA 3.0, RWDK-Format aus `tools/make_drum_bank.py`: Velocity-Layer, Choke-Klassen aus der SF2, Rimshot); GM-Noten ohne Sample und jede Note, solange das Kit lädt oder fehlt, spielt das synthetische Kit. Keys und Klick sind synthetisch; Amp, Cabinet und Effekte sind eigene DSP-Blöcke. Alles ist deterministisch und auf der JVM testbar. SF2-Import bleibt ein eigener Vertrag (C-SOUNDBANK).
4. **AudioTrack-Renderthread in Kotlin statt Oboe/NDK.** Float-PCM im Low-Latency-Modus mit nativer Rate/Burst und Hardware-Zeitstempeln. Damit läuft die komplette Engine samt Transport in JVM-Tests gegen eine virtuelle Senke. Falls Gerätemessungen es verlangen, ist ein Oboe-Adapter nur eine weitere `AudioSink`-Implementierung.
5. **Eigene Formatimplementierungen.** GP3/4/5, GPX, GP7/8 (GPIF), MIDI, MusicXML und ASCII-TAB werden selbst gelesen bzw. geschrieben, mit Inhaltserkennung, strikten Parsern, Ressourcenlimits und Verlustberichten. Originalbytes bleiben als Asset erhalten.
6. **Natives Schema bleibt Version 1.** Alle neuen Fähigkeiten nutzen bereits vorhandene typisierte Modellfelder. Neu sind nur Laufzeitklassen (Wiedergabeplan, Anzeigeliste), die nie gespeichert werden.
7. **Referenz-Audio über die Gerätedecoder.** MediaCodec deckt MP3, AAC/M4A, Ogg und FLAC ab, ohne Codecs mitzuliefern; WAV wird in Kotlin decodiert. Bis Time-Stretch existiert, ist Referenz-Audio nur bei 100 % Tempo hörbar statt falsch synchron.
8. **Updates aus GitHub Releases (Zyklus 27).** Signierte Debug-APK aus der CI, Prüfung und Download durch die App selbst; einzige Netzwerkverbindung, abschaltbar. Siehe [Updates](#updates-zyklus-27).

## Module

```mermaid
flowchart TD
  App[app: Navigation und Composition Root] --> Features[feature: library / workspace / settings]
  Features --> UI[ui: components / score]
  Features --> Editor[core: editor]
  Features --> Playback[core: playback]
  Features --> Synth[core: synth]
  UI --> Notation[core: notation]
  UI --> Editor
  Editor --> Ports[core: ports]
  Playback --> Ports
  Synth --> Playback
  Notation --> Model
  App --> Adapters[adapter: formats / audio / alphatab / integrations]
  App --> Local[data: local]
  App --> Android[platform: android]
  Adapters --> Ports
  Adapters --> Synth
  Local --> Ports
  Local --> Editor
  Android --> Ports
  Ports --> Model[core: model]
  Ports --> Capabilities[core: capabilities]
  Capabilities --> Model
```

Manuelle Dependency Injection lebt ausschließlich in `AppContainer`. Features kennen Repositories und Ports, nicht deren Dateisystem-, Geräte- oder Formatimplementierung. Testabhängigkeiten dürfen konkrete Adapter einsetzen. Der Fixture-Generator hat einen eigenen Source-Set und gelangt nicht in die APK.

## Projekt und Zeit

Project besitzt Metadaten, globale Measures, Tracks/Staffs/Bars/Voices/Beats/Notes, Tempo/Abschnitte, Layout, Text/Lyrics, Akkorde/Skalen, Audio, Mixgraph, Klangbankdefinitionen, Assets und Importherkunft. Jeder Staff referenziert dieselben globalen Takte in derselben Reihenfolge. Pro Bar stehen vier stabile Stimmen zur Verfügung. IDs sind unabhängig von Listenindizes.

- Fraction beschreibt exakte Bruchteile einer ganzen Note mit normalisierter positiver Basis und geprüfter Arithmetik. Eine punktierte Achtel ist 3/16; eine Achtel unter 3:2 und 5:4 ist 1/15.
- ScorePosition bezeichnet Takt und Offset. PlaybackOccurrence ergänzt Durchlauf und Routenindex. Eine Takt-ID allein ist für Wiederholungs-Playback unzureichend.
- Pixel und Samplepositionen sind abgeleitet. Floating Point wird nur für Anzeige und Audio verwendet; die Umrechnung Partitur → Frames ist eine stückweise Zeitabbildung pro Routenabschnitt.
- Note hält Tonhöhe sowie gegebenenfalls Saite/Bund. Tuning ist tief→hoch; Drop A ist MIDI 33/40/45/50/55/59/64. Neue Projekte (ProjectFactory.create) starten mit einem 4/4-Takt und einer 6-saitigen E-Standard-Gitarre 40/45/50/55/59/64; der Modell-Standardwert InstrumentDefinition() bleibt Drop A. Capo ist pro Saite gespeichert.
- Ties/Slurs/Legato referenzieren Zielnoten-IDs. Bends/Whammy halten rationale Kurvenpunkte in Cents. Bend ist niemals eine globale Kanaländerung.
- SyncMap verbindet konkrete Wiederholungsdurchläufe mit Audiozeit. Zeitgleiche Stems dürfen eine Map und eigene Offsets nutzen; verschiedene Aufnahmen dürfen getrennte Maps haben.

## Wiedergabe

`Project → UnfoldingRepeatCompiler → Timing → ScorePlaybackCompiler → PlaybackPlan → EngineProgram → SynthEngine → RealtimeHost → AudioSink`

- **Route:** Wiederholungen werden vollständig aufgelöst: `endCount` ist die Zahl der Durchläufe, Voltas gelten pro Durchlauf, D.C./D.S. springen einmal und spielen danach den letzten Durchlauf bis Fine bzw. ab „To Coda“ in die Coda. Jede Station kennt Takt, Durchlauf und Routenindex.
- **Zeit:** Tempo gilt in Schreibreihenfolge und wird auf jedem Durchlauf gleich angewandt. Swing verzerrt die Zeit innerhalb der Zählzeit, Fermaten verlängern lokal. Ergebnis sind `RouteSpan`-Zeitabbildungen (Offset ↔ Frame).
- **Noten:** Der Sequencer löst Artikulationen in Aufführungsdaten auf: Vorschläge, Staccato, Dynamik, Ghost/Akzent, Flageoletts über die Obertonreihe, Bends/Whammy/Slides als eigene Tonhöhenkurve pro Note, gebundene Noten verschmolzen, Hammer-on/Pull-off/Tapping/Slap/Pop als Anschlagsart, Let Ring, Tremolo und Triller expandiert. Pro Saite klingt höchstens eine Note (Saiten-Choke), Drums nutzen Choke-Gruppen.
- **Engine:** Der Plan ist unveränderlich, auf die Geräterate gerechnet und mit Geschwindigkeit skaliert. `EngineProgram` legt Ereignisse vorab in Arrays ab. `SynthEngine` rendert ohne Allokation, I/O oder Locks: Stimmenpools (72 Saiten, 96 Gitarren-Samples mit Bank, 48 synthetische Drums, 64 Drum-Samples mit Kit, 48 Keys), ein Kanal pro Spur mit Ton-Preset und Effektkette, Master-Limiter, Flush-to-Zero gegen Subnormalzahlen.
- **Host:** `RealtimeHost` besitzt den Renderthread mit Audio-Priorität. Befehle laufen über eine lock-freie Queue; neue Pläne werden blockgenau getauscht und behalten die musikalische Position. Seek rekonstruiert klingende Noten (Chase). Ein Plan, der den alten nur verlängert (gleiche Spans, Klicks und Einsätze bis zum alten Ende, z. B. die Tipp-Kopie von „Rhythmus tippen“ mit angehängten Takten), wird ohne Umrechnung, Seek oder Freigabe der Stimmen getauscht (`Reprogram.seamless`, Zyklus 27).
- **Uhr:** Die Position stammt aus den AudioTrack-Zeitstempeln (präsentierter Frame) und wird über den Plan in Takt, Offset und Durchlauf zurückgerechnet. Keine UI-Uhr treibt den Transport. `SinkFormat.sampleRate` ist die Rate, die der geöffnete Track tatsächlich verbraucht (nach dem Bauen zurückgelesen, Zyklus 28); Engine, Plan und Zeitstempel-Extrapolation nutzen genau diese, die Diagnose zeigt „Ausgabe/Engine“ und die Mixer-Rate des Geräts. Die Tipp-Zeitabbildung (`audiblePlanFrameAt`) nutzt bei jedem Tipp ein frisches (Frame, Zeit)-Paar, keinen einmaligen Anker: Uhr-Drift zwischen `System.nanoTime` und Audio-Uhr wirkt nur über Zeitstempel-Alter und Zustellverzögerung (±200 ppm über 32 Takte: ≤ 0,1 ms).
- **Offline:** `OfflineMixdown` nutzt denselben Compiler und dieselbe Engine für WAV-Mix und Stems; Stems teilen Startframe und Länge. Die Effektlatenz ist ausgeglichen (Planframe 0 = Dateiframe 0), die Stems sind unabhängig vom Master-Fader (Werkspegel) und summieren sich bei Werks-Master-Fader zum Mix ohne Master-Kette.

## Rhythmus tippen (Zyklen 25–28)

`Tipp (Ereigniszeit) → audiblePlanFrameAt − Gerätelatenz → TapStroke (Planframes) → RhythmQuantizer / RhythmSmoother → RhythmResult → EditorCommand.ApplyRhythm`

- **Auto (Standard, Zyklus 28, `RhythmSmoother`, core:editor):** Pulsverfolgung (robuste, nicht-kausale, leave-one-out Kernregression der Rasterresiduen, σ = 1 Viertel, Huber, Grenze ⅛ Viertel; Downs und Ups entzerrt), Rasterwahl je Zählzeit und je Viertelpaar als MDL-Modellwahl (Daten- + Kodier- + Modellkosten; binär, 32tel/64tel, Triolen, Sextolen, Quintolen, Septolen, Viertel- und verschachtelte Triolen), wiederholte Figuren gemeinsam entschieden und aus dem Median ihrer Takte notiert, Kontext-Prior je Tuplet-Familie, Haltedauern mit derselben Rasterwahl. Die festen Raster (1/8 … 1/32, das Zyklus-25-„16tel/Triolen“) bleiben unter „Raster manuell“.
- **Taktart (Zyklus 28, `MeterDetector`):** Selbstähnlichkeit der quantisierten Anschläge je Kandidaten-Taktart (Lag 1 und 2 Takte, Komplexitätsstrafe, 3/4 ↔ 6/8 über das Akzentprofil), Wechsel nur mit klarer Evidenz. Angewandt nur auf angehängte Takte und auf leere Zieltakte am Stückende (`meterEligible`); dann werden die Spans neu geteilt (`rebarSpans`, gleiche Zeitabbildung, Tempo in Vierteln/min bleibt) und neu quantisiert; `ApplyRhythm.signatures` setzt die Taktarten im selben Undo-Schritt.

## Notensatz

`LayoutRequest → ScoreLayouter → ScoreLayout (Systeme, Takte, Spalten, Noten, Glyphen) → GlyphPainter / SvgExport / PdfExport`

Die Anzeigeliste besteht aus Linien, Rechtecken, Texten, Ovalen und Pfaden mit semantischer Farbe (Ink). Der Bildschirm zeichnet nur sichtbare Glyphen, SVG/PDF und der Java2D-Testrenderer zeichnen dieselbe Liste. Treffer werden über Spalten und Saitenlagen auf Takt, Offset und Saite zurückgeführt. TAB mit Rhythmus, Standardnotation mit Schlüsseln, Vorzeichen, Balken und Haltebögen sowie Drum-Notation entstehen aus demselben Layouter. 200 Takte mit je 16 Noten brauchen auf der JVM etwa 35 ms; das Layout läuft im ViewModel auf einem Hintergrund-Dispatcher.

## Formate

`Bytes → DefaultFormatCatalog.detect → ScoreImporter → ImportResult(Projekt, Verluste) → Library: Originalbytes als Asset + ImportProvenance → ScoreValidator → Repository`

Die Erkennung prüft Inhalte (ZIP-Einträge, Binärköpfe, XML-Wurzel), nicht die Dateiendung. XML wird ohne DTD/Entitäten gelesen, ZIP-Größen, Eintragszahlen und Verschachtelung sind begrenzt. Was ein Format nicht abbilden kann, steht als Verlusteintrag im Projekt und wird vor einem Export angezeigt.

## Referenz-Audio

Import: Dateiauswahl → `AudioDecoder.probe` (WAV in Kotlin, sonst MediaCodec) → AssetStore (SHA-256) → `AudioClip` im Projekt. Wiedergabe: `EngineTransport` lädt Clips über `DecodingClipLoader` (Asset → Decoder → `PcmBuilder`, der während des Decodierens auf die Geräterate resampelt; höchstens 10 Minuten) und übergibt ein `ClipSet`. Clips liegen in Planframes, deshalb bleiben sie bei Loop, Seek und Count-in sample-genau zur Partitur. Ein gemeinsames Solo gilt für Spuren und Clips.

## Updates (Zyklus 27)

Einzige Netzwerkverbindung der App, abschaltbar. Entscheidung: Die Debug-APK (die vom Nutzer getestete Variante; der minifizierte Release-Build ist nie auf einem Gerät geprüft) wird in der CI mit einem festen Schlüssel aus GitHub Secrets signiert und als GitHub Release veröffentlicht; die App holt Updates selbst, Android verlangt bei jeder Installation die Bestätigung des Nutzers.

- **Version:** CI setzt `RW_VERSION_CODE` = Laufnummer + 1000 und `RW_VERSION_NAME` = „0.<Laufnummer>“ (`tools/ci_update_signing.sh`), lokal versionCode 1 / „0.1.0-dev“. `BuildConfig.VERSION_CODE/VERSION_NAME` nennen dem Updater die installierte Version. Signatur `signingConfigs.updates` (PKCS12) nur, wenn `RW_KEYSTORE_FILE`, `RW_KEYSTORE_PASSWORD`, `RW_KEY_ALIAS`, `RW_KEY_PASSWORD` gesetzt sind; sonst bleibt der Build unverändert.
- **Kanal:** Job `release` (nur Push auf `ccr-0b650dff-jnhups-qa`, nach grünem `verify`, nur mit Keystore-Secret) → Release `v<versionCode>`, Asset `ArtiFret-<versionName>.apk`, Text = Kurzlog seit dem letzten `v*`-Tag, „latest“ nur wenn neuer als das bisherige (`tools/ci_publish_release.sh`), nie Prerelease.
- **Prüfen:** `UpdateChecker` (core:ports) → `GitHubUpdateChecker` (platform:android): `GET api.github.com/repos/Tra1c0/Guitar-Pro/releases/latest`, anonym, User-Agent „ArtiFret-Updater“, 10 s Timeout, Antwort ≤ 1 MB; `Updates.parseLatestRelease` (portabel, kotlinx-serialization) verlangt Tag `v<code>` > installiert, kein Draft/Prerelease, ein `.apk`-Asset unter `https://github.com/Tra1c0/Guitar-Pro/releases/download/`, 0 < Größe ≤ 300 MB, optional SHA-256 aus dem Asset-`digest`.
- **Ablauf:** `UpdateCoordinator` (core:ports, eine Instanz im AppContainer): Startprüfung nach dem Laden der Einstellungen nur bei `AppSettings.updateChecks` und höchstens alle 6 h (`lastUpdateCheckEpochMs` wird vor der Anfrage geschrieben); still bei offline/aktuell/Fehler, sonst Overlay „Update <version> verfügbar“ mit „Installieren“ (öffnet Einstellungen → Updates). „Jetzt prüfen“ meldet jedes Ergebnis. Nie zwei Prüfungen/Installationen gleichzeitig.
- **Installieren:** `UpdateInstaller` → `AndroidUpdateInstaller`: Download nach `cache/updates` mit Fortschritt, Größe und (falls vorhanden) SHA-256 gegen die Release-Daten, Paketname und versionCode der APK gegen die Ankündigung; ohne „Unbekannte Apps installieren“ öffnet sich die Systemseite (`ACTION_MANAGE_UNKNOWN_APP_SOURCES`), danach erneut „Installieren“ (der Download wird wiederverwendet). Übergabe per PackageInstaller-Session (Status → `UpdateInstallReceiver`, startet die Systembestätigung, Fehler als Toast), Rückfall FileProvider (`${applicationId}.updates`, nur `cache/updates`) + `ACTION_VIEW`. Die Signaturgleichheit prüft Android selbst.
- **Tests ohne Netz:** Robolectric-Läufe der echten App erhalten `OfflineUpdateChecker` und keine Startprüfung (`Build.FINGERPRINT == "robolectric"`).

## Zustandsfluss

`UI-Intent → EditorCommand → Kandidat/Validierung → immutable Revision → StateFlow/Layout/Transport → Autosave`

Die Autosave-Queue darf Zwischenrevisionen zusammenfassen, verliert jedoch nicht den neuesten Stand. Ein Mutex serialisiert Saves und flush. Bei Fehler bleibt die Musik im Speicher und der gespeicherte Revisionsstand unverändert; Retry nutzt genau diesen Stand. Vor dem Verlassen wartet die App auf flush. Beim Hintergrundwechsel pausiert die Wiedergabe und flush wird angestoßen. Ein sofortiger Prozessabbruch kann weiterhin noch nicht gespeicherte Änderungen verlieren; die Anzeige unterscheidet gespeichert, ausstehend und fehlgeschlagen.

Undo/Redo kopiert Musikstände, erhöht aber weiterhin die Revisionsnummer. Restore ist ein Replace-Command mit Ausgangsrevision. Asynchron geladene Snapshots dürfen spätere Änderungen nicht überschreiben. Die Historie ist linear und auf 200 Schritte begrenzt; benannte Snapshots sind dauerhaft.

Während der Wiedergabe kompiliert jede Änderung einen neuen Plan, der an derselben musikalischen Position übernommen wird. Ohne laufende Wiedergabe öffnet keine Änderung das Audiogerät.

## UI und Gesten

AppRoutes umfasst library, project/{projectId} und settings. Innerhalb einer Projektsitzung identifiziert WorkspacePage 14 Bereiche (inkl. Audio-Diagnose) mit stabilen Routen-IDs; Bereichswechsel erhalten dasselbe ViewModel. Mobil gibt es scrollbare Tabs, ab 840 dp eine NavigationRail. Ab 700 dp verfügbarem Editorplatz liegt das Eingabefeld seitlich.

Die Partitur ist eine Canvas über dem Layout: Pan, Pinch-Zoom um den Fokuspunkt, Tippen setzt den Cursor, Bereichsauswahl über Takte, mitlaufender Playhead. Ziffern bleiben Vorschau bis Commit und werden beim Eintragen angespielt. Akkordmodus hält die Position. Nummerneingabe, Quick-Bar, Griffbrett und Drumgrid gehen durch dieselben Commands; externe Tastaturen nutzen dieselben Befehle. Mixer- und Clip-Slider erzeugen erst am Ende einer Geste eine Transaktion.

Player, Decoder, View-Objekte und Content-URIs gelangen nie in das Musikmodell. Alle offenen Anbieter melden UNAVAILABLE mit Vertrags-ID. Die Funktionsansicht liest dasselbe Register wie die dokumentierte Paritätsmatrix.
