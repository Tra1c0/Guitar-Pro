# Guitar Pro fürs Handy
## Recherche, Produktbriefing und Startprompt für Claude Code

Stand 4. November 2026. Ziel ist ein eigenständiger, vollwertiger Editor auf dem Handy mit dem Funktionsumfang von Guitar Pro Desktop und besseren Abläufen beim Schreiben, Üben und Austausch. Android zuerst mit Kotlin und Jetpack Compose ist eine Arbeitsannahme, passend zum Xiaomi 15 als erstem Testgerät. iOS und Tablets bleiben Architekturziele. Die Recherche stützt sich auf Herstellerdokumentation, Store-Angaben, Repos und öffentliche Wünsche. Die Apps und Bibliotheken wurden für dieses Briefing weder installiert noch praktisch getestet.

## 1. Welches Guitar Pro ist die Referenz?

Arobas führt **Guitar Pro 8** für Windows und macOS. **8.1.5** ist der jüngste Patchstand im öffentlich dokumentierten Hersteller-Changelog, den ich verifizieren konnte. Die öffentlich gelistete Android-App liest GP3 bis GP8, bietet Mehrspur-Wiedergabe und einen Einspur-Notizblock. Importierte Partituren lassen sich darin nicht umfassend bearbeiten. Das NotePad kann keine Drumspur bearbeiten. Eingebettetes GP8-Audio wird in der gelisteten Android-App weder angezeigt noch abgespielt. Der iOS-Support bestätigt dieselbe Grenze beim Editieren importierter Dateien. Arobas hat 2026 einen künftigen **Guitar Pro Player** mit GP8-Klang, Audio-Track, Mehrspuransicht, dynamischem Zoom, Dark Mode, relativer Geschwindigkeit und aufgelösten Wiederholungen vorgeführt. Die Ankündigung belegt den Prototyp. Sie belegt weder eine öffentliche Freigabe noch einen vollwertigen Mehrspur-Editor.

[Desktop-Changelog](https://www.guitar-pro.com/c/10-guitar-pro-new-features) · [Offizielle Android-App](https://play.google.com/store/apps/details?id=com.arobasmusic.guitarpro) · [iOS-NotePad](https://support.guitar-pro.com/hc/en-us/articles/360001429937-GP-Mobile-iOS-Edition-and-NotePad) · [Arobas-Demo 2026](https://www.guitar-pro.com/blog/p/56937-namm-show-2026-prs-walrus-marcus-miller-and-the-future-guitar-pro-mobile-app-exclusive-demo)

### Funktionsmatrix für Desktop-Parität

Für jede Zeile führt das Projekt getrennte Statusfelder für **lesen, darstellen, bearbeiten, hörbar wiedergeben und exportieren**. Beispielsweise können Palm-Mutes sichtbar sein, obwohl sie beim Playback nur angenähert werden. Produktumfang und GP-Dateitreue sind getrennte Ziele.

| Bereich | Referenz und Anforderungen an die App |
|---|---|
| Projekte und Arrangement | Mehrspur-Partitur, Instrumentvorlagen, Metadaten, Spuren anlegen/duplizieren/umsortieren, Abschnittsmarker, Takte einfügen/löschen, Copy/Paste von Noten bis zu Abschnitten, Auswahlbearbeitung, Undo/Redo. Takt-, Tonart- und Tempowechsel, Auftakt, Wiederholungen mit alternativen Enden, Da Capo/Dal Segno, Coda/Fine und aufgelöste Wiedergabe. |
| Rhythmus und Notation | TAB, klassische Notation, Slash, Schlagzeugdarstellung und Jianpu. Punktierung, Pausen, Halte- und Bindebögen, Auftakt, Swing/Triplet Feel, verschachtelte Tuplets, Verzierungen, Fermaten und Vorschlagsnoten. Vier Stimmen pro Track im GP8-Workflow. Gleichzeitige Stimmen müssen unabhängig bearbeitbar bleiben. |
| Saiteninstrumente | GP8 dokumentiert 3–10 Saiten, freie Stimmung, Capo und Teil-Capo. Dazu Bund-/Saiteneingabe, alternative Fingersätze, Transposition, Akkord- und Skalenbibliothek, Griffdiagramme, Fingerings, Fretboard, virtuelle Tastatur und Drumkit. Presets für 6/7/8-Saiter und Bass. Stimmungsänderung muss explizit zwischen gleicher Griffposition und gleicher klingender Tonhöhe unterscheiden. |
| Gitarrenartikulationen | Palm-Mute, Dead/Ghost Notes, Akzente, Dynamik, Let Ring, Staccato, Hammer-on/Pull-off, Slide-Varianten, Vibrato, Bends inklusive Prebend/Release, Whammy und Vierteltöne, natürliche/künstliche/Pinch-Harmonics, Tapping, Tremolo-Picking, Triller, Pickstroke, Pick Scrape, Slap/Pop, Strum, Arpeggio und Rasgueado. Erfassungsumfang, Gravur und hörbares Ergebnis einzeln prüfen. |
| Schlagzeug und Band | Percussion-Artikulationen, Notation/TAB, Elemente im Drumkit mit Lautstärke/Pan, Bass, Piano und weitere Instrumente. Ein Touch-Stepgrid ist eine zusätzliche Bearbeitungsansicht auf derselben Partitur. Es muss Hi-Hat offen/geschlossen, Ghost Notes, Velocity, Chokes, unabhängige Stimmen und ungerade Raster abbilden können. |
| Sound und Mix | RSE-Klangbibliothek, MIDI-Wiedergabe, Mixer mit Solo/Mute/Volume/Pan und track-/masterbezogene Klangbearbeitung, EQ und virtuelle Effekte/Amps, Pedalboard, Sound- und Mixautomation, Fine-Tuning, Stereo-Position und Gitarren-Line-in. Der Hersteller listet 200 RSE-Soundbanks und 80 Effekte/Amps. Funktionale Parität erfordert eigene oder lizenzierte Klanginhalte und hörbare Artikulationen. |
| Audio-Referenz und Üben | Eine GP8-Audiospur als Aufnahme/Backing, Wellenform, Sync-Punkte, Offset, Tonhöhenkorrektur und Filter, relative Geschwindigkeit, Loop, Einzähler, visuelles Metronom, Speed Trainer, Stimmgerät und Fretboard/Keyboard beim Abspielen. Score-Tempo und Audio-Sync sind unterschiedliche Modi. GP8 richtet die Audiospur an der aufgelösten, linearen Struktur aus. |
| Layout und Austausch | Seiten- und fortlaufende Ansicht, Mehrspuransicht, Layout/Stylesheets, Takt- und Systemumbrüche, Texte/Lyrics, Akkordsymbole und Formatierung. GP-Import, MusicXML, MIDI, ASCII und Altformate wie PowerTab/TablEdit. Exporte für GP, MIDI, MusicXML, ASCII, PDF, PNG, SVG und gerendertes Audio mit getrennten Spuren. Export pro Format auf Musikdaten und Darstellungsdetails prüfen. |
| Workflow und Zubehör | Befehlspalette, wiederholbare Aktionen, Tastenkürzel, MIDI-Eingabe, Wiederherstellung/Autosave und Einstellungen. Fretlight und der lizenzierte mySongBook-Katalog sind eigenständige Integrationen. Der Paritätsplan erfasst sie separat mit technischen und vertraglichen Abhängigkeiten. |

[GP8-Handbuch und Schnellstart](https://www.guitar-pro.com/docs/gp8/basics/intro/quick-start) · [Neue Funktionen](https://www.guitar-pro.com/docs/gp8/basics/intro/whats-new) · [Vier Stimmen](https://www.guitar-pro.com/docs/gp8/score/voices) · [Stimmungen und Capo](https://www.guitar-pro.com/docs/gp8/score/track-settings/track-tuning) · [Klang und Pedalboard](https://www.guitar-pro.com/docs/gp8/audio/soundsetup/sound-settings) · [Audio und Sync](https://www.guitar-pro.com/docs/gp8/audio/backing-track) · [Export](https://www.guitar-pro.com/docs/gp8/import-export/export) · [Audioexport](https://www.guitar-pro.com/docs/gp8/import-export/export/export-audio) · [Hersteller-Produktdetails](https://www.guitar-pro.com/10-products)

## 2. Was Leute vermissen und wo sich das Projekt absetzen kann

Öffentliche Posts und Store-Rezensionen zeigen konkrete Arbeitsabläufe. Die Prioritäten unten sind eine Produkteinschätzung aus diesen Beispielen und der Zielsetzung. Es liegt keine repräsentative Befragung vor.

| Priorität | Öffentlicher Wunsch oder beobachtete Lücke | Konkreter Produktvorschlag |
|---|---|---|
| Sehr hoch | Importierte Tabs mobil ändern, Drums schreiben, vollständige Mehrspurprojekte am Handy bearbeiten | Vollständiger Editor. Ein importiertes Riff kann neu getaktet, mit Schlagzeug versehen und in GP Desktop weiterbearbeitet werden. |
| Hoch | Mehrere Referenz-Audiodateien oder Stems gemeinsam verwenden. Leute wechseln zwischen Original, isolierter Gitarre/Bass und Mix ohne eigenes Instrument. | Beliebig mehrere Audioclips im Projektmodell, gemeinsame Sync-Map für zeitgleiche Stems, individuelle Offsets, optional getrennte Sync-Maps für verschiedene Aufnahmen. Stem-Mute/Solo im Übemodus. |
| Hoch | Eigene Soundbanks und DAW-Anbindung. Wünsche nennen SF2, VST und weniger Umwege für MIDI. | Eigene SF2/Preset-Banks, synchroner WAV-Stem-Export plus Tempo-Map/MIDI und eindeutige Startpunkte. Zieltest mit Cubase. Plugin-Hosting als separates Vorhaben pro Plattform. |
| Mittel | Mehrspuransicht, ordentliches Zoomen, eingebettete Audiospur und moderner Player auf dem Handy | Wichtig für Üben, aber bereits Teil der Arobas-Demo. Mehrwert entsteht durch Editierbarkeit, Audio und Synth gleichzeitig, schnelle Navigation und synchronisierte Korrekturen. |
| Qualitätsziel | Übernahme, Fortsetzen, Varianten und Fehlerkorrektur bei langen Sessions | Autosave mit Wiederherstellung, benannte Schnappschüsse, Originaldatei separat, sichtbare Import-/Exportverluste, Undo für ganze Gesten. Versionierung ist eine Produktidee mit existierender Konkurrenz. |

[Mehrere Audiotracks 2023](https://www.reddit.com/r/GuitarPro/comments/148ice6) · [Stems 2026](https://www.reddit.com/r/GuitarPro/comments/1s966wz/separate_audio_stems_multiple_audio_files_in_gp9/) · [Soundbanks, VST und Audio in der Community](https://www.reddit.com/r/GuitarPro/comments/1isrjsn/guitar_pro_9/) · [Mobile Store-Rezensionen](https://apps.apple.com/us/app/guitar-pro/id400666114?see-all=reviews) · [Melodist Composer](https://melodist.studio/composer)

### Was es schon gibt

| Vergleich | Wofür relevant |
|---|---|
| **Melodist Composer** | Der direkte Vergleich auf Android und iOS. Hersteller dokumentiert Offline-Mehrspur-Editing, Drums, freie Saitenzahl, GP3–8-Import, SoundFonts und verzweigte Edit-Historie. Publizierte Exportliste nennt MIDI, MusicXML und MDST. GP-Rückexport, hörbare Tiefe einzelner Artikulationen, Stems und Gesten praktisch vergleichen. |
| **TuxGuitar** | Vollwertiges Open-Source-Editorprojekt mit Android-Code und Desktop-Versionen. Gute Referenz für Partituraktionen, GP-Kompatibilität und Drum-/Matrix-Editor. |
| **TEFpad** | Etablierter Android-/iOS-Editor mit Guitar-Pro-Import und eigenem TablEdit-Workflow. Relevant für Dateiaustausch und kleine Bildschirme. |
| **Soundslice** | Touchscreen-Referenz mit einer Eingabeleiste, explizitem Auto-Advance und getrennten Ansichten für Partitur und Medien. Dokumentiert mehrere Aufnahmen sowie Multitrack-Stems. |

[Melodist](https://melodist.studio/composer) · [TuxGuitar](https://github.com/helge17/tuxguitar) · [TEFpad](https://play.google.com/store/apps/details?id=com.tabledit.TEFpad) · [Soundslice-Touchbedienung](https://www.soundslice.com/help/en/creating/basics/255/tablet-interface/) · [Soundslice-Editor](https://www.soundslice.com/notation-editor/)

## 3. Bedienung auf dem Handy

**Schreiben.** Oben Songname, Abschnitt und Undo/Redo. In der Mitte eine dominante aktive TAB-Spur, horizontal mit Zeitlineal und fixierbaren Saitenlabels. Unten eine feste Transportleiste und eine einklappbare Eingabefläche. Bundnummern per Ziffernfeld oder Griffbrett, dedizierter Bund 0, X für Dead Note, Löschen und Vor/Zurück. Werte von 10 aufwärts als eindeutige Eingabe mit Vorschau und Commit-Regel. Letzten Rhythmuswert merken. Bundvorschau erklingt sofort, soweit die Audiopipeline bereit ist. Einzelnotenmodus kann nach Commit zum nächsten Beat springen, Akkordmodus hält die Position. Quick-Bar mit Notenwert, Pause, Tie, Palm-Mute, Slide und Bend. Weitere Effekte per Suche, Favoriten und langem Druck auf die Quick-Bar. Mit externer Tastatur Tastenbefehle und Zifferneingabe ergänzen.

**Arrangieren.** Übersichtsansicht aus Abschnittschips, Taktblöcken, kompakten Spurzeilen und einer Mini-Timeline. Ein Tipp springt zu Abschnitt oder Spur. An sichtbaren Griffen Spuren neu ordnen oder einen Taktbereich bewegen/duplizieren. Aktionen klar trennen in einzelne Spur, alle Spuren und Ripple. Ein globaler Taktverschub bewegt Marker, Tempo-/Taktwechsel und Sync-Anker gemäß sichtbarer Vorschau mit. Kritische Optionen per Sheet zugänglich, mit Vorschau und Undo.

**Üben und Transkribieren.** Größere Lesefläche, fixierter oder mitlaufender Cursor, A/B-Loop, Tempo, Count-in, Solo/Mute und Loop-Trainer im Daumenbereich. Wellenform kann aufgezogen werden. Sync-Anker lassen sich an Transienten, Beat oder Takt ausrichten. Stems und Synth haben eigene Lautstärke und Mute. Auf dem Tablet Partitur, Mixer und Eingabe nebeneinander. Im Querformat auf dem Handy mehr Takte zeigen und das Keypad seitlich platzieren.

| Geste | Erwartbares Verhalten |
|---|---|
| Tipp auf TAB | Saite und Beat wählen, bestehende Note selektieren. Stift, D-Pad oder Tasten bieten denselben Zugriff. |
| Wischen auf freier Partitur | Scrollen. Pinch zoomt um den Fokuspunkt. Eine erkannte Zwei-Finger-Geste beendet einen noch nicht bestätigten Drag. |
| Ziehen an Auswahlgriffen | Bereich mit sichtbarem Anfang und Ende setzen. An Kanten langsam autoscrollen. |
| Ziehen einer gewählten Note | Geistervorschau und Einrasten auf das aktive Rhythmusraster. Horizontal Zeit ändern. Saitenwechsel zeigt die neue Bundnummer und ob der Originalton spielbar ist. Einstellung für gleiche Tonhöhe oder gleiche Griffposition. Konflikte wie zwei Noten auf derselben Saite im selben Beat sichtbar lösen. |
| Effekt auf Noten ziehen | Vorschau auf die Zielauswahl, mit derselben Aktion über Quick-Bar oder Menü. Mehrere betroffene Noten ergeben einen Undo-Schritt. |
| Drag abbrechen | Vor dem Commit Ausgangszustand wiederherstellen. Aus der Vorschau gehen keine Autosaves oder Audioänderungen hervor. |

Bedienelemente im Daumenbereich mindestens 48 dp als Android-Ziel. Dichte Notation bekommt gezieltes Hit-Testing, Zoom, Pfeilnavigation und beschriftete Accessibility-Aktionen. Long Press ist ein Shortcut zu erreichbaren Aktionen. Compose-Gesten für den Partiturbereich getrennt vom System-Drag-and-drop für Dateiaustausch behandeln. Bei Scrollen und Zoomen können Canvas, sichtbare Systeme, hit-testbare Beat-/Notengrenzen und ein separat gezeichneter Playhead die UI-Arbeit begrenzen.

**UX-Testaufgabe.** Handy im Hochformat. Siebensaiter Drop A (A–E–A–D–G–B–E, tief nach hoch), 150 BPM, ein erster Takt mit 16tel-Palm-Mutes auf der tiefen Saite, Dead Note, Slide, Pinch-Harmonic und Bend. Ein zweiter Takt in 7/8 folgt, dazu Kick/Snare/Hi-Hat. Eine Phrase duplizieren, Loop bei 70 %, Fehler rückgängig machen und Datei exportieren. Teste mit Menschen, die Guitar Pro kennen, und mit Gitarristen ohne Desktop-Routine. Messe Zeit bis zum ersten korrekt klingenden Takt, Tippzahl, Fehlgriffe und Aktionen, die sie nicht selbst finden. Prototypziel für den ersten Takt nach kurzer Einweisung ist unter einer Minute. Vergleiche tatsächliche Ergebnisse mit dem schnellsten getesteten mobilen Workflow.

[Android-Gesten](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures) · [System-Drag-and-drop](https://developer.android.com/develop/ui/compose/touch-input/user-interactions/drag-and-drop) · [Touchflächen](https://support.google.com/accessibility/android/answer/7101858) · [Soundslice Auto-Advance](https://www.soundslice.com/help/en/creating/basics/255/tablet-interface/)

## 4. Architektur und Audio

### Partitur und Kompatibilität

Eigener versionierter Projektkern mit stabilen IDs für Projekt, Spur, Staff, Stimme, Takt, Beat und Note. Geschriebene und gespielte Zeit getrennt halten. Noten speichern Bund, Saite und musikalische Tonhöhe bzw. Regeln zur Herleitung und einen typisierten Effekt mit Parametern. Zeit intern exakt rational modellieren, auch für verschachtelte Tuplets. Render- und Playback-Compiler erzeugen abgeleitete Ansichten. Repeat-Compiler macht aus geschriebenen Takten eine lineare Abspielroute. Eine Position für die Playback-UI braucht Takt-ID **und Durchlauf**. Separates Mapping zwischen Score-Position, Tempo-Map, Audio-Zeit und Sample-Frame. Bei Backing-Audio müssen Loop, Wiederholungen und Änderungen der Tempo-Map nachvollziehbare Auswirkungen auf den Abspielpfad haben.

Editoränderungen als Transaktionen mit validierbaren Commands. Freies Entwerfen kann unvollständige Takte markieren, während strikte Modi Fehler anzeigen. Autosave lokal mit atomarem Commit oder Journal und versionierten Snapshots, getrennte Assets per Inhaltshash. Dateiauswahl über Android Storage Access Framework. Importierte Bytes für Wiederherstellung aufheben. Unverstandene GPIF-Felder nur dann wieder einsetzen, wenn ihre Referenzen nach der Bearbeitung valide bleiben. Alles übrige in einem Verlustbericht ausweisen. ZIP-/XML-Import unter Ressourcenlimits, mit Schutz gegen Pfadüberschreitung, Dekompressionsbomben und unsichere XML-Entitäten.

GP3/4/5 sind Binärformate, GP6 verwendet GPX/BCFS/BCFZ, GP7/8 verwenden .gp mit ZIP/GPIF. Versionsfamilien über Inhalt erkennen. GP8 kann zusätzliche Assets und erweiterte XML-Strukturen enthalten. **GP7-kompatibler .gp-Export** ist eine überprüfbare erste Ziellinie. **GP8-fidel exportieren** ist ein eigener Track, besonders für eingebettetes Audio, Anker, verschachtelte Tuplets, Presets, Automation und Layout. Dateiendung und erfolgreiches Öffnen allein reichen als Nachweis nicht. Original und Export in Guitar Pro Desktop öffnen, gezielt prüfen und zurückimportieren. MIDI eignet sich für DAW-Ereignisse, MusicXML für Notation und Tab-Angaben, PDF/SVG für Darstellung. Native Projektdatei bleibt die vollständige editierbare Fassung.

[Formatmatrix von alphaTab](https://www.alphatab.net/docs/formats/guitar-pro-8) · [GP-Importfamilien](https://next.alphatab.net/docs/features/import-load/guitar-pro) · [GP7-Exporter](https://docs.alphatab.net/docs/guides/exporter) · [Android-Dateiauswahl](https://developer.android.com/training/data-storage/shared/documents-files)

### Was die Audio-Engine konkret leisten muss

| Baustein | Empfohlener Entwurf |
|---|---|
| Musikalischer Sequencer | Score vorab zu Events mit Sample-Frame-Ziel kompilieren. Tempo, Swing, Tuplets, Ties, Grace Notes, Automationen und Wiederholungen gehören hierhin. Seek baut Kanal-/Instrumentzustände und gegebenenfalls ausklingende Stimmen kontrolliert neu auf. |
| Audio-Uhr und Echtzeit | Native Sample-Clock mit Ausgabezeitstempeln als Referenz. UI liest extrapolierte Position, ohne den Transport zu treiben. Audio-Callback bekommt vorbereitete Daten über begrenzte Queues oder Snapshots, ohne Allokationen, I/O, Locks oder JNI pro Sample. Oboe-Buffer und Fallbacks pro Route messen. |
| Synth und Gitarrensampler | General-MIDI/SoundFont für sofortiges Ensemble. Separater artikulationsbewusster Sampler für Clean, High Gain und Bass. Palm-Mute braucht gedämpfte Aufnahmen mit eigener Attack und Ausklang. Dead Notes sind eigene perkussive Samples, Harmonics brauchen passende Obertöne und Pitch. Slides, Hammer/Pull und Bends brauchen kontinuierliche Tonhöhenkurven bzw. Übergänge. Bends auf einer Saite dürfen einen gehaltenen Ton auf einer anderen Saite nicht mitziehen. |
| Gitarrensound | Saubere DI-Samples pro Saite/Region, Velocity-Layer und Round Robins, ausklingende Releases, Saiten-Choke und Transitions. Für Metal zwischen drei Varianten blind vergleichen: GM-Basis, vorverzerrte Artikulationsbank und DI plus Amp/Cab. Verzerrung pro Stimme versus nach Summierung verändert Akkord-/Doppelgriffklang. Erste eigene Bank auf tiefen Tönen und schnellen Palm-Mutes optimieren. |
| Mixer und FX | Track- und Masterbus, Gain/Pan, Mute/Solo, Drum-Chokes, Automation, einfache EQ-/Dynamik-/Raum-FX. Gitarrenkette optional Gate, Overdrive, Amp, Cabinet-IR, EQ. RSE-Presets bei Import als Originaldaten erhalten, eigener Klang als ausdrücklich gekennzeichnetes Mapping. |
| Wellenform und Stems | Audio off-thread decodieren, Peaks mehrstufig cachen, PCM gepuffert liefern. Stems und Synth auf denselben Transport mischen. Für Tempo bei erhaltener Tonhöhe eine Stretch-Stufe einsetzen. Unterschiedliche Sample-Raten, Decoder-Seek, Encoder-Delay und Stretch-Latenz berücksichtigen. Loop-Übergänge auf Knackser und Drift messen. |
| Offline-Export | Derselbe Event-Compiler und Mixgraph für WAV/MIDI und gerenderte Stems. Startpunkt, Länge, Tempo, Tail, Samplerate und Effekt-/Master-Verhalten explizit setzen. Stems bei identischem Zeitursprung in Cubase importieren und gegen den Referenzmix abgleichen. Ein MIDI-Export kann Gitarrenartikulationen nur gemäß Zielinstrument und Mapping übertragen. |
| Live-Eingang und Routings | USB-MIDI als separater Capture-Pfad, USB-Interface/Line-in für Monitoring und eventuell Aufnahme. Audio-Fokus, Unterbrechungen, Route-Wechsel und Wiederanlauf behandeln. Bluetooth beim Üben mit Zuspielung messen, Hardware-Monitoring zusätzlich über Kabel/USB prüfen. iOS später mit AVAudioEngine/Core Audio und optional AUv3 evaluieren. |

[Android zu Oboe und Echtzeit-Callbacks](https://developer.android.com/games/sdk/oboe/low-latency-audio) · [Android Native MIDI](https://developer.android.com/ndk/guides/audio/midi) · [Android Audio-Demux](https://developer.android.com/reference/android/media/MediaExtractor) · [Apple zu AVAudioEngine und AUv3](https://developer.apple.com/documentation/audiotoolbox/incorporating-audio-effects-and-instruments)

### Repos und Bibliotheken, die tatsächlich etwas abnehmen

| Projekt | Einsatz und Grenze |
|---|---|
| **[alphaTab](https://github.com/CoderLine/alphaTab)** | **Erster Spike.** MPL-2.0, Kotlin/Android, Web und .NET. GP3–8-Import, Rendering, Noten-/Beat-Bounds, SoundFont-Synth und GP7-Export. Das offizielle Android-Control ist ein klassisches View. In Compose über AndroidView einbetten oder unteren Renderer anbinden. Eigenes Eingabemodell nötig. Die dokumentierte Backing-Funktion spielt Synth **oder** Backing. Vollständige GP8-Dateitreue, echtes Soundbank-Mapping und gleichzeitige Stems separat prüfen. [Android-Setup](https://www.alphatab.net/docs/getting-started/installation-android) · [Bounds](https://www.alphatab.net/docs/reference/api/boundslookup) · [Backing-Verhalten](https://next.alphatab.net/docs/features/play-sync/backing-track-audio) |
| **[fretwork](https://github.com/bubiche/fretwork)** | Konkretes Web-Repo auf alphaTab mit GP-Import, Note-/Effektbearbeitung, Commands/Undo, Autosave und GP7-Export. Lesenswerte Referenz für einen kleinen vertikalen Editor und mögliche Machbarkeitsbasis für einen Web-Spike. Vor Codeübernahme Lizenz des Repos und der Assets am gewählten Commit prüfen. |
| **[TuxGuitar](https://github.com/helge17/tuxguitar)** | LGPL laut Hauptrepo. Zweiter Import-/Editor-Vergleich mit Android-Code und Java-Kern. Reale Formate, Effektsemantik und UI-Aktionen nachlesen. Bei Wiederverwendung konkrete Module, Lizenzdateien und Distribution prüfen. |
| **[PyGuitarPro](https://github.com/Perlence/PyGuitarPro)** | Python-Werkzeug für GP3/4/5. Gut für Fixture-Erzeugung und Cross-Checks im Tooling, kein GP7/8-Backend. |
| **[Oboe](https://github.com/google/oboe)** / **[miniaudio](https://github.com/mackron/miniaudio)** | Oboe als Android-Ausgabe mit AAudio/OpenSL-ES-Backend je nach System. miniaudio für portables I/O/Decoding/Engine-Bausteine, wenn die Cross-Plattform-Entscheidung es rechtfertigt. Das Routing- und Latenzverhalten mit echten Geräten vergleichen. |
| **[TinySoundFont](https://github.com/schellingb/TinySoundFont)** / **[FluidSynth](https://github.com/FluidSynth/fluidsynth)** | TinySoundFont ist ein kleiner SF2-Synth unter MIT. FluidSynth ist die umfangreichere SoundFont-Alternative mit LGPL-Lizenzanforderungen. Beide mit konkreter Klangbank, Polyphonie, CPU und Rendering testen. Die SoundFont-Lizenz separat prüfen. |
| **[Signalsmith Stretch](https://signalsmith-audio.com/code/stretch/)** | Kandidat für getrenntes Time-Stretch/Pitch-Shift. Auf Schlagzeugtransienten, verzerrte Riffs und 50–90 % Geschwindigkeit hören. Latenzkompensation und wiederholbares Seek-Verhalten prüfen. |
| **[NeuralAmpModelerCore](https://github.com/sdatkinson/NeuralAmpModelerCore)** | Kandidat für Amp-Captures im optionalen Sound- oder Line-in-Pfad. Modell- und IR-Rechte separat prüfen. CPU, Thermik und Blockgröße vor einer Mobilentscheidung messen. |
| **[Basic Pitch](https://github.com/spotify/basic-pitch)** | Optionaler Audio-zu-MIDI-Spike. Pitch-Erkennung alleine liefert weder eindeutige Saiten/Fingersätze noch zuverlässige Palm-Mutes in einem Metal-Mix. Für spätere Vorschläge mit editierbarem Ergebnis. |
| **[VST3 SDK](https://github.com/steinbergmedia/vst3sdk)** | Das SDK-Repo führt MIT. Für mobile Planung entscheidend ist die tatsächliche Verfügbarkeit kompatibler Plug-in-Binaries und Host-Unterstützung. Auf iOS ist AUv3 der plattformspezifische Pfad. Für Cubase hat sauberer Stem-/MIDI-Export früh den besseren Fit. |

Abhängigkeiten mit Version, Commit, Lizenz, Notice-Pflichten und Assets dokumentieren. Fonts, SoundFonts, IRs, Modelle, Presets und Testtabs erhalten jeweils einen eigenen Herkunfts- und Rechte-Eintrag. Arobas-RSE-Sounds, mySongBook-Inhalte und Markenmaterial bleiben separate Rechtefragen. Erst mit eigenen oder entsprechend lizenzierten Testdateien arbeiten.

## 5. Reihenfolge und Abnahme

| Etappe | Ergebnis, das man tatsächlich testen kann |
|---|---|
| **A. Zwei Machbarkeitstests** | Native Compose-Hülle mit alphaTab-View/Bounds gegen einen kleinen web-/alphaTab-basierten Vergleich. Auf dem Xiaomi 15 und einem langsameren Android testen. Erstens selektieren, verändern, teilweise neu rendern, Undo, GP7-Export und Reopen in GP8. Zweitens Oboe/SoundFont plus ein Backing gemeinsam unter einer Uhr loopen. Repo- und Engine-Entscheidungen an den Resultaten festmachen. |
| **B. Spielbarer Editor** | Offline ein Mehrspurprojekt neu schreiben und GP importieren. Gitarren- und Drum-Editing, anpassbare Saitenzahl, Taktwechsel, Basisrhythmik und die zentralen Metal-Artikulationen. Save/Recover, Mixer, Loop, Klick, GP7-/MIDI-/Projekt-Export. Native Datei und Fremdexport klar unterscheiden. |
| **C. Tiefe und Dateitreue** | Vier Stimmen, komplexe Wiederholungen, verschachtelte Tuplets, Audio-Referenz plus Sync, Notensatz und Layout, MusicXML/PDF, Automationen, Effekte, Soundpresets, fortgeschrittene Import-/Exportfälle. GP8-Teilmerkmale pro Feature verifizieren. |
| **D. Qualitätsfunktionen und Restparität** | Mehrere Stems, Pitch/Stretch, Soundbank-Import, ausgereifte Gitarrenbank, DAW-Übergabe, Live-Monitoring, Desktop-nahe Editieroperationen, erweiterte Ausgaben und Tablet/iOS-Entscheidung. Funktionsmatrix mit offenen und lizenzgebundenen Punkten fortführen. |

**Verifikationskorpus.** Eigene kurze GP3/4/5-, GPX- und GP7/8-Dateien sowie mindestens ein langes Arrangement mit 8 Spuren und 200 Takten. Fälle für 7/8-Saiter, Custom-Tuning und Teil-Capo, vier Stimmen, verschachtelte Tuplets, Auftakt, alternative Enden, DC/DS/Coda, zweistellige Bünde, Slides über Taktgrenzen, unabhängige Bends auf mehreren Saiten, gebundene Noten, Drum-Chokes, GP8-Audio, Tempoänderungen und gebrochene ZIP/XML-Inputs. GP8-Original, bearbeiteten Export und Reimport auf Spur-/Beat-/Notenebene vergleichen. Sounds reproduzierbar mit PCM-Referenz und Hörtest prüfen. Wo GP8 Desktop nicht verfügbar ist, bleibt dessen Reopen-Test als offen markiert.

**Messbare Startziele.** Touch-to-Preview und Touch-to-Paint getrennt p50/p95 messen, mit Ausgangsziel unter 50 ms p95 bis visuelles Feedback und unter 70 ms p95 bis hörbare Notenvorschau über kabelgebundenes/USB-Routing auf dem Referenzgerät. 60 fps beim normalen Scrollen als Ziel, Zoom und lange Projekte getrennt erfassen. Für synthetische Loop-Fixtures Start-/End-Sample und Ankerposition prüfen, dann 30 Minuten realen Stresstest mit Underrun-Zähler, Route-Wechsel, Pause/Resume und Thermik ausführen. Diese Werte sind Abnahmeziele und keine gemessene Leistung. Messaufbau und Geräte zusammen mit Ergebnissen dokumentieren.

## 6. Kopierfertiger Prompt für Claude

Das Briefing als Datei beilegen und den folgenden Block senden. Für Claude Code ist dies ein Auftrag zur Architekturplanung im Plan Mode. In Claude Chat kann derselbe Prompt mit dem Briefing als Anhang als Planungsauftrag dienen. Eine bestimmte Modellversion ist nicht vorausgesetzt.

```text
<projekt>
Ich will einen vollwertigen, eigenständigen mobilen Tab- und Partitureditor mit dem Funktionsumfang von Guitar Pro 8 Desktop und besseren Abläufen fürs Schreiben, Üben und Transkribieren. Lies das beigefügte Guitar-Pro-Mobile-Briefing-fuer-Claude.md. Nutze die Produktrecherche als Ausgangspunkt und überprüfe Annahmen, die deine Architekturentscheidung verändern.

Android zuerst, bevorzugt Kotlin und Jetpack Compose. Xiaomi 15 ist das erste Testgerät. iOS und Tablet-Layouts sollen später möglich sein. Kernfunktionen sind offline nutzbar. Mein kritischer Anwendungsfall sind Metal-Riffs auf einer 7-Saiter in Drop A mit hörbaren Palm-Mutes, Dead Notes, Slides, Pinch-Harmonics, unabhängigen Bends und separaten Drum-, Bass- und Gitarrenspuren. Der Austausch mit Guitar Pro Desktop und Cubase zählt.
</projekt>

<auftrag>
Entwirf eine umsetzbare Architektur und einen Plan für die ersten zwei Machbarkeitstests. Desktop-Parität bleibt das Endziel. Ordne jedes Merkmal der Funktionsmatrix einer Etappe zu und markiere lizenzgebundene Integrationen, unsichere Dateitreue und später zu verifizierende Funktionen.

1. Beurteile native Compose/alphaTab und eine Web-/Hybridvariante an denselben Kriterien. Prüfe die tatsächlich veröffentlichte alphaTab-API für Android, Editier-/Selektionsgrenzen, Rerendering, Sounds und Backing. Prüfe zusätzlich TuxGuitar und fretwork auf wiederverwendbare Teile und Lizenzbedingungen. Triff eine Stack-Entscheidung mit Umstiegskriterium, falls der erste Spike scheitert.
2. Entwirf Datenmodell, Transaktionsgrenzen und Zeitmodell. Zeige an Beispielen, wie verschachtelte Tuplets, mehrere Stimmen, gebundene Noten, unabhängige Bends, Wiederholungsdurchläufe und Audio-Sync abgebildet werden. Beschreibe Autosave, Recovery, Projektformat und Umgang mit unbekannten GP-Daten nach Änderungen.
3. Lege die mobile Bedienung konkret fest. Skizziere Hoch-/Querformat und Tablet, Eingabefläche, Quick-Bar, Spurenübersicht und Audio-Ansicht. Gib Schrittfolgen für Riff schreiben, Bend zeichnen, Drumbeat setzen, Taktbereich per Drag duplizieren und einen Stem synchronisieren. Definiere Drag-Zustände, Scroll-/Zoom-Konflikte, Abbruch, Undo und erreichbare Bedienalternativen.
4. Plane das Audio-System vom Score bis zum Ausgabegerät und Offline-Export. Benenne Clock, Repeat-/Tempo-Compiler, Scheduling, Stimmenzuordnung, Mixer, Sample-/Instrumentmodell, Backing-/Stem-Decode und Stretch. Erkläre konkret, wie ein Bend auf einer Saite bei gleichzeitig gehaltenem Ton auf einer anderen hörbar korrekt bleibt und wie sich Palm-Mute von normalem Anschlag unterscheidet. Trenne Vorschau, Playback, Live-Monitoring und Rendering für Cubase.
5. Definiere die GP-Fidelitätsmatrix mit Status für Import, Darstellung, Bearbeitung, Playback und Export. Trenne GP7-kompatiblen Rückexport und GP8-spezifische Daten. Entwirf Fixtures und Tests für Funktions-, Klang- und Roundtrip-Treue. Nenne die Dateien, die ich in GP Desktop zum Gegencheck öffnen muss, und die zu prüfenden Unterschiede. Definiere WAV-/MIDI-Übergabe an Cubase.
6. Liefere zwei testbare Spikes. A: GP8 laden, im Compose-Prototyp eine Note/Artikulation ändern, Undo, GP7-kompatibel exportieren und nachweisbar wieder öffnen. B: Synth, Metronom und einen Audiostem unter einer Clock über Tempoänderung, Seek und Loop synchron abspielen. Gib pro Spike minimalen Umfang, notwendige Module, Fixture, Befehle sofern die Umgebung sie hergibt, Messaufbau, Pass/Fail und Entscheidungsfolge an.
</auftrag>

<arbeitsweise>
Wenn du Repo- und Shellzugriff hast, lies zuerst die tatsächlichen Projektdateien und vorhandenen Anweisungen, inklusive CLAUDE.md, falls vorhanden. Falls kein Repo vorliegt, plane ein Greenfield-Projekt. Verifiziere entscheidende API- und Lizenzbehauptungen anhand offizieller Docs oder Quellcode und pinne konkrete Versionen erst nach Prüfung. Kennzeichne Quellenbehauptung, getestete Beobachtung, technische Annahme und offene Frage getrennt. Unterstelle keinen verfügbaren GP8-Desktop, keine Testgeräte und keine Netz- oder Toolrechte. In dieser Runde liefere den Plan und überprüfbare Entscheidungen. Die zwei Spikes sind die anschließenden Implementierungsaufträge.
</arbeitsweise>

<ausgabe>
1. Kurze Entscheidung mit 3 größten Risiken und Rückfallkriterien
2. Paritätsmatrix und priorisierte Qualitätsverbesserungen
3. Editor- und Audioarchitektur mit Daten-/Zeitmodell
4. Touch-Interaktionen anhand konkreter Nutzerabläufe
5. Spike A und B mit prüfbaren Abnahmekriterien
6. Roadmap bis zur vollen mobilen Parität und Liste offener Fragen
</ausgabe>
```

[Anthropic Prompting Best Practices](https://platform.claude.com/docs/en/build-with-claude/prompt-engineering/claude-prompting-best-practices) · [Claude Code Best Practices](https://code.claude.com/docs/en/best-practices)
