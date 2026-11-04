# Paritätsmatrix

Generiert aus `spec/features.json`. Jede Zeile bewertet fünf Fähigkeiten getrennt. **Lesen:** im Modell und in der nativen Datei vorhanden und aus Importen übernommen, soweit das Format es kennt. **Darstellen:** im Notensatz oder in der Oberfläche sichtbar. **Bearbeiten:** in der App über Editor-Befehle änderbar. **Playback:** in Wiedergabe und Audioexport hörbar. **Export:** in mindestens einem Austauschformat (GP7, MIDI, MusicXML, ASCII) oder Medienexport (SVG, PDF, WAV, Stems) geschrieben; native Erhaltung allein zählt nicht.

`EXPERIMENTAL` heißt vorhanden, aber eingeschränkt (Grenzen in CONTRACTS.md). `NOT_APPLICABLE` heißt, die Fähigkeit ergibt für das Merkmal keinen Sinn. `SCAFFOLDED` ist nur Modell/Vertrag ohne Umsetzung.

## GP-Dateitreue

GP3/4/5, GPX und GP7/8 werden gelesen, GP7 wird geschrieben. Geprüft ist das gegen selbst erzeugte Dateien und Rundreisen innerhalb der App, nicht gegen Guitar Pro Desktop; deshalb stehen diese Zeilen auf `DESKTOP_PENDING`. GP8-spezifische Inhalte (Backing Track, Sync-Punkte, Assets) landen im Verlustbericht. Native Speicherung einer Eigenschaft ist kein GP-Fidelity-Nachweis.

## Prüfstand

`AUTOMATED`: JVM-/Robolectric-Tests decken den beschriebenen Umfang ab. `DEVICE_PENDING`: implementiert und automatisiert geprüft, die Abnahme auf echter Hardware fehlt. `DESKTOP_PENDING`: der Gegencheck in Guitar Pro Desktop beziehungsweise Cubase fehlt. `AUTOMATED_PENDING`: implementiert, aber noch ohne eigenen Test. `CONTRACT_ONLY`: nur Vertrag.

| ID | Funktion | Lesen | Darstellen | Bearbeiten | Playback | Export | Etappe | Vertrag | Prüfung |
|---|---|---|---|---|---|---|---|---|---|
| projects.native | Native Projekte | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | PHASE1 | [C-PROJECT](CONTRACTS.md#c-project) | AUTOMATED |
| projects.metadata | Projekttitel und Metadatenmodell | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | NOT_APPLICABLE | IMPLEMENTED | PHASE1 | [C-EDITOR](CONTRACTS.md#c-editor) | AUTOMATED |
| tracks.create | Spuren anlegen | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | PHASE1 | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | AUTOMATED |
| tracks.duplicate | Spuren duplizieren | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | B | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | AUTOMATED |
| tracks.reorder | Spuren umsortieren | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | PHASE1 | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | AUTOMATED |
| tracks.remove | Spuren entfernen | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | PHASE1 | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | AUTOMATED |
| tracks.templates | Instrumentvorlagen | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | PHASE1 | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | AUTOMATED |
| measures.insert | Takte einfügen | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | PHASE1 | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | AUTOMATED |
| measures.delete | Takte löschen | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | B | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | AUTOMATED |
| clipboard.notes | Noten kopieren und einfügen | NOT_APPLICABLE | IMPLEMENTED | EXPERIMENTAL | NOT_APPLICABLE | NOT_APPLICABLE | B | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | AUTOMATED |
| clipboard.sections | Takte und Abschnitte kopieren | NOT_APPLICABLE | IMPLEMENTED | IMPLEMENTED | NOT_APPLICABLE | NOT_APPLICABLE | B | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | AUTOMATED |
| arrangement.sections | Abschnittsmarker | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | NOT_APPLICABLE | IMPLEMENTED | B | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | AUTOMATED |
| arrangement.ripple | Ripple mit Vorschau | NOT_APPLICABLE | EXPERIMENTAL | EXPERIMENTAL | NOT_APPLICABLE | NOT_APPLICABLE | B | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | AUTOMATED |
| selection.batch | Auswahlbearbeitung | NOT_APPLICABLE | IMPLEMENTED | EXPERIMENTAL | NOT_APPLICABLE | NOT_APPLICABLE | B | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | AUTOMATED |
| history.undo | Undo und Redo | NOT_APPLICABLE | IMPLEMENTED | IMPLEMENTED | NOT_APPLICABLE | NOT_APPLICABLE | PHASE1 | [C-EDITOR](CONTRACTS.md#c-editor) | AUTOMATED |
| history.snapshots | Benannte Schnappschüsse | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | NOT_APPLICABLE | NOT_APPLICABLE | PHASE1 | [C-SNAPSHOT](CONTRACTS.md#c-snapshot) | AUTOMATED |
| history.branching | Varianten und verzweigte Historie | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | B | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | CONTRACT_ONLY |
| signature.time | Taktwechsel | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | PHASE1 | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | AUTOMATED |
| signature.key | Tonartwechsel | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | NOT_APPLICABLE | IMPLEMENTED | B | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | AUTOMATED |
| tempo.changes | Tempoänderungen | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | PHASE1 | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | AUTOMATED |
| repeats.basic | Wiederholungen | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | B | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | AUTOMATED |
| repeats.endings | Alternative Enden | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | B | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | AUTOMATED |
| repeats.navigation | Da Capo, Dal Segno, Coda und Fine | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | B | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | AUTOMATED |
| repeats.unfolded | Aufgelöste Wiedergabe mit Durchlauf | NOT_APPLICABLE | IMPLEMENTED | NOT_APPLICABLE | IMPLEMENTED | IMPLEMENTED | B | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | AUTOMATED |
| notation.tab | TAB | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | PHASE1 | [C-NOTATION](CONTRACTS.md#c-notation) | AUTOMATED |
| notation.standard | Klassische Notation | IMPLEMENTED | IMPLEMENTED | EXPERIMENTAL | IMPLEMENTED | IMPLEMENTED | C | [C-NOTATION](CONTRACTS.md#c-notation) | AUTOMATED |
| notation.slash | Slash-Notation | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-NOTATION](CONTRACTS.md#c-notation) | CONTRACT_ONLY |
| notation.percussion | Schlagzeugnotation | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | C | [C-NOTATION](CONTRACTS.md#c-notation) | AUTOMATED |
| notation.jianpu | Jianpu | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-NOTATION](CONTRACTS.md#c-notation) | CONTRACT_ONLY |
| rhythm.duration | Notenwerte | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | PHASE1 | [C-NOTATION](CONTRACTS.md#c-notation) | AUTOMATED |
| rhythm.dots | Punktierungen | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | PHASE1 | [C-NOTATION](CONTRACTS.md#c-notation) | AUTOMATED |
| rhythm.rest | Pausen | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | PHASE1 | [C-NOTATION](CONTRACTS.md#c-notation) | AUTOMATED |
| rhythm.tie | Haltebögen | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | C | [C-NOTATION](CONTRACTS.md#c-notation) | AUTOMATED |
| rhythm.slur | Bindebögen | IMPLEMENTED | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | IMPLEMENTED | C | [C-NOTATION](CONTRACTS.md#c-notation) | AUTOMATED |
| rhythm.pickup | Auftakt | IMPLEMENTED | IMPLEMENTED | SCAFFOLDED | IMPLEMENTED | IMPLEMENTED | C | [C-NOTATION](CONTRACTS.md#c-notation) | AUTOMATED |
| rhythm.swing | Swing und Triplet Feel | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | C | [C-NOTATION](CONTRACTS.md#c-notation) | AUTOMATED |
| rhythm.tuplets | Verschachtelte Tuplets | IMPLEMENTED | IMPLEMENTED | EXPERIMENTAL | IMPLEMENTED | IMPLEMENTED | C | [C-NOTATION](CONTRACTS.md#c-notation) | AUTOMATED |
| rhythm.ornaments | Verzierungen | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-NOTATION](CONTRACTS.md#c-notation) | CONTRACT_ONLY |
| rhythm.fermata | Fermaten | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | C | [C-NOTATION](CONTRACTS.md#c-notation) | AUTOMATED |
| rhythm.grace | Vorschlagsnoten | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | C | [C-NOTATION](CONTRACTS.md#c-notation) | AUTOMATED |
| rhythm.voices | Vier unabhängige Stimmen | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | PHASE1 | [C-NOTATION](CONTRACTS.md#c-notation) | AUTOMATED |
| strings.count | 3–10 Saiten | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | PHASE1 | [C-INSTRUMENT](CONTRACTS.md#c-instrument) | AUTOMATED |
| strings.tuning | Freie Stimmungen | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | PHASE1 | [C-INSTRUMENT](CONTRACTS.md#c-instrument) | AUTOMATED |
| strings.capo | Capo und Teil-Capo | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | PHASE1 | [C-INSTRUMENT](CONTRACTS.md#c-instrument) | AUTOMATED |
| strings.retune_policy | Tonhöhe oder Griffposition erhalten | NOT_APPLICABLE | NOT_APPLICABLE | IMPLEMENTED | NOT_APPLICABLE | NOT_APPLICABLE | B | [C-INSTRUMENT](CONTRACTS.md#c-instrument) | AUTOMATED |
| notes.fret | Bund- und Saiteneingabe | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | PHASE1 | [C-INSTRUMENT](CONTRACTS.md#c-instrument) | AUTOMATED |
| notes.multidigit | Zweistellige Bünde mit Commit | NOT_APPLICABLE | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | NOT_APPLICABLE | PHASE1 | [C-INSTRUMENT](CONTRACTS.md#c-instrument) | AUTOMATED |
| notes.fingering | Fingerings und alternative Griffe | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | NOT_APPLICABLE | IMPLEMENTED | B | [C-INSTRUMENT](CONTRACTS.md#c-instrument) | AUTOMATED |
| notes.transpose | Transposition | NOT_APPLICABLE | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | NOT_APPLICABLE | B | [C-INSTRUMENT](CONTRACTS.md#c-instrument) | AUTOMATED |
| tools.chords | Akkordbibliothek und Griffdiagramme | IMPLEMENTED | EXPERIMENTAL | IMPLEMENTED | IMPLEMENTED | NOT_APPLICABLE | B | [C-INSTRUMENT](CONTRACTS.md#c-instrument) | AUTOMATED |
| tools.scales | Skalenbibliothek | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | NOT_APPLICABLE | NOT_APPLICABLE | B | [C-INSTRUMENT](CONTRACTS.md#c-instrument) | AUTOMATED |
| tools.fretboard | Einfache Griffbrett-Eingabe | NOT_APPLICABLE | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | NOT_APPLICABLE | PHASE1 | [C-INSTRUMENT](CONTRACTS.md#c-instrument) | AUTOMATED |
| tools.keyboard | Virtuelle Tastatur | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | B | [C-INSTRUMENT](CONTRACTS.md#c-instrument) | CONTRACT_ONLY |
| tools.drumkit | Virtuelles Drumkit | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | B | [C-INSTRUMENT](CONTRACTS.md#c-instrument) | CONTRACT_ONLY |
| fx.palm_mute | Palm-Mute | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | PHASE1 | [C-ARTICULATION](CONTRACTS.md#c-articulation) | AUTOMATED |
| fx.dead | Dead Notes | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | PHASE1 | [C-ARTICULATION](CONTRACTS.md#c-articulation) | AUTOMATED |
| fx.ghost | Ghost Notes | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | AUTOMATED |
| fx.accent | Akzente | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | AUTOMATED |
| fx.dynamics | Dynamik | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | AUTOMATED |
| fx.let_ring | Let Ring | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | AUTOMATED |
| fx.staccato | Staccato | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | AUTOMATED |
| fx.hammer_pull | Hammer-on und Pull-off | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | AUTOMATED |
| fx.slide | Slide-Varianten | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | AUTOMATED |
| fx.vibrato | Vibrato | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | AUTOMATED |
| fx.bend | Bend, Prebend und Release | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | AUTOMATED |
| fx.whammy | Whammy-Kurven | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | AUTOMATED |
| fx.quartertone | Vierteltöne | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | AUTOMATED |
| fx.harmonic_natural | Natürliche Harmonics | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | AUTOMATED |
| fx.harmonic_artificial | Künstliche Harmonics | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | AUTOMATED |
| fx.harmonic_pinch | Pinch-Harmonics | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | AUTOMATED |
| fx.tapping | Tapping | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | AUTOMATED |
| fx.tremolo | Tremolo-Picking | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | AUTOMATED |
| fx.trill | Triller | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | AUTOMATED |
| fx.pickstroke | Pickstroke | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | SCAFFOLDED | IMPLEMENTED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | AUTOMATED |
| fx.pick_scrape | Pick Scrape | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | AUTOMATED |
| fx.slap_pop | Slap und Pop | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | AUTOMATED |
| fx.strum | Strum | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | AUTOMATED |
| fx.arpeggio | Arpeggio | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | AUTOMATED |
| fx.rasgueado | Rasgueado | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | AUTOMATED |
| drums.entry | Kick, Snare und Hi-Hat setzen | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | PHASE1 | [C-DRUM](CONTRACTS.md#c-drum) | AUTOMATED |
| drums.stepgrid | Einfaches 1/16-Stepgrid | NOT_APPLICABLE | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | NOT_APPLICABLE | PHASE1 | [C-DRUM](CONTRACTS.md#c-drum) | AUTOMATED |
| drums.articulations | Percussion-Artikulationen | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | B | [C-DRUM](CONTRACTS.md#c-drum) | AUTOMATED |
| drums.hihat | Offene und geschlossene Hi-Hat | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | B | [C-DRUM](CONTRACTS.md#c-drum) | AUTOMATED |
| drums.velocity | Velocity und Ghost Notes | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | B | [C-DRUM](CONTRACTS.md#c-drum) | AUTOMATED |
| drums.choke | Drum-Chokes | IMPLEMENTED | NOT_APPLICABLE | NOT_APPLICABLE | IMPLEMENTED | NOT_APPLICABLE | B | [C-DRUM](CONTRACTS.md#c-drum) | AUTOMATED |
| drums.voices | Unabhängige Drumstimmen | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | B | [C-DRUM](CONTRACTS.md#c-drum) | AUTOMATED |
| drums.odd_grid | Ungerade Raster | NOT_APPLICABLE | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | NOT_APPLICABLE | B | [C-DRUM](CONTRACTS.md#c-drum) | AUTOMATED |
| drums.element_mix | Lautstärke und Pan pro Drum-Element | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | EXPERIMENTAL | SCAFFOLDED | B | [C-DRUM](CONTRACTS.md#c-drum) | CONTRACT_ONLY |
| band.instruments | Bass, Piano und weitere Instrumente | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | PHASE1 | [C-DRUM](CONTRACTS.md#c-drum) | AUTOMATED |
| sound.gm | General MIDI / SoundFont | IMPLEMENTED | NOT_APPLICABLE | IMPLEMENTED | EXPERIMENTAL | IMPLEMENTED | D | [C-AUDIO](CONTRACTS.md#c-audio) | AUTOMATED |
| sound.rse_mapping | RSE-Originaldaten und eigenes Klangmapping | EXPERIMENTAL | NOT_APPLICABLE | SCAFFOLDED | EXPERIMENTAL | NOT_APPLICABLE | D | [C-AUDIO](CONTRACTS.md#c-audio) | CONTRACT_ONLY |
| sound.banks | Eigene SF2-/Preset-Banks | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-SOUNDBANK](CONTRACTS.md#c-soundbank) | CONTRACT_ONLY |
| sound.sampler | Artikulationsbewusster Gitarrensampler | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-AUDIO](CONTRACTS.md#c-audio) | CONTRACT_ONLY |
| sound.layers | Velocity-Layer, Round Robins und Releases | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | EXPERIMENTAL | NOT_APPLICABLE | D | [C-AUDIO](CONTRACTS.md#c-audio) | AUTOMATED |
| sound.transitions | Slides, Legato und Saiten-Choke | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | D | [C-AUDIO](CONTRACTS.md#c-audio) | AUTOMATED |
| sound.independent_bends | Unabhängige Bends pro Saite | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | D | [C-AUDIO](CONTRACTS.md#c-audio) | AUTOMATED |
| sound.di_amp | DI, Amp und Cabinet-IR | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | EXPERIMENTAL | IMPLEMENTED | D | [C-AUDIO](CONTRACTS.md#c-audio) | AUTOMATED |
| sound.fine_tuning | Fine-Tuning | IMPLEMENTED | NOT_APPLICABLE | SCAFFOLDED | IMPLEMENTED | NOT_APPLICABLE | D | [C-AUDIO](CONTRACTS.md#c-audio) | AUTOMATED_PENDING |
| mix.channels | Solo, Mute, Volume und Pan | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | PHASE1 | [C-AUDIO](CONTRACTS.md#c-audio) | AUTOMATED |
| mix.buses | Track-, Bus- und Masterrouting | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-AUDIO](CONTRACTS.md#c-audio) | CONTRACT_ONLY |
| mix.eq_fx | EQ und virtuelle Effekte | IMPLEMENTED | IMPLEMENTED | EXPERIMENTAL | IMPLEMENTED | IMPLEMENTED | D | [C-AUDIO](CONTRACTS.md#c-audio) | AUTOMATED |
| mix.pedalboard | Pedalboard und Amp-Kette | IMPLEMENTED | IMPLEMENTED | EXPERIMENTAL | IMPLEMENTED | IMPLEMENTED | D | [C-AUDIO](CONTRACTS.md#c-audio) | AUTOMATED |
| mix.automation | Sound- und Mixautomation | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-AUDIO](CONTRACTS.md#c-audio) | CONTRACT_ONLY |
| sound.line_in | Gitarren-Line-in | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-AUDIO](CONTRACTS.md#c-audio) | CONTRACT_ONLY |
| audio.clock | Native Sample-Clock und Ausgabezeitstempel | NOT_APPLICABLE | IMPLEMENTED | NOT_APPLICABLE | IMPLEMENTED | NOT_APPLICABLE | D | [C-AUDIO](CONTRACTS.md#c-audio) | DEVICE_PENDING |
| audio.seek | Seek und Instrumentzustand | NOT_APPLICABLE | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | NOT_APPLICABLE | D | [C-AUDIO](CONTRACTS.md#c-audio) | DEVICE_PENDING |
| audio.lifecycle | Audio-Fokus, Unterbrechung und Route-Wechsel | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | IMPLEMENTED | NOT_APPLICABLE | D | [C-AUDIO](CONTRACTS.md#c-audio) | DEVICE_PENDING |
| audio.latency | Latenz, Underruns und Thermik | NOT_APPLICABLE | EXPERIMENTAL | NOT_APPLICABLE | EXPERIMENTAL | NOT_APPLICABLE | D | [C-AUDIO](CONTRACTS.md#c-audio) | DEVICE_PENDING |
| audio.reference | Referenzaufnahme / GP8-Audio | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | C | [C-PRACTICE](CONTRACTS.md#c-practice) | AUTOMATED |
| audio.multiple_stems | Mehrere Audio-Stems | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | C | [C-PRACTICE](CONTRACTS.md#c-practice) | AUTOMATED |
| audio.waveform | Mehrstufige Wellenform | NOT_APPLICABLE | IMPLEMENTED | IMPLEMENTED | NOT_APPLICABLE | NOT_APPLICABLE | C | [C-WAVEFORM](CONTRACTS.md#c-waveform) | AUTOMATED |
| audio.sync | Sync-Anker und Audio-Offset | IMPLEMENTED | IMPLEMENTED | EXPERIMENTAL | IMPLEMENTED | NOT_APPLICABLE | C | [C-SYNC](CONTRACTS.md#c-sync) | AUTOMATED |
| audio.sync_modes | Score-Tempo und Audio-Sync | IMPLEMENTED | NOT_APPLICABLE | SCAFFOLDED | EXPERIMENTAL | NOT_APPLICABLE | C | [C-PRACTICE](CONTRACTS.md#c-practice) | AUTOMATED |
| audio.shared_map | Gemeinsame und getrennte Stem-Sync-Maps | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-PRACTICE](CONTRACTS.md#c-practice) | CONTRACT_ONLY |
| audio.stem_mix | Synth und Stem-Mix unter einer Clock | NOT_APPLICABLE | NOT_APPLICABLE | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | C | [C-PRACTICE](CONTRACTS.md#c-practice) | AUTOMATED |
| audio.pitch_stretch | Pitch-Shift und Time-Stretch | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-STRETCH](CONTRACTS.md#c-stretch) | CONTRACT_ONLY |
| audio.decode | Decoder-Seek und Sampleraten | IMPLEMENTED | NOT_APPLICABLE | NOT_APPLICABLE | IMPLEMENTED | NOT_APPLICABLE | C | [C-DECODE](CONTRACTS.md#c-decode) | DEVICE_PENDING |
| practice.speed | Relative Geschwindigkeit | NOT_APPLICABLE | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | NOT_APPLICABLE | C | [C-PRACTICE](CONTRACTS.md#c-practice) | AUTOMATED |
| practice.loop | A/B-Loop | NOT_APPLICABLE | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | NOT_APPLICABLE | C | [C-PRACTICE](CONTRACTS.md#c-practice) | AUTOMATED |
| practice.count_in | Einzähler | NOT_APPLICABLE | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | NOT_APPLICABLE | C | [C-PRACTICE](CONTRACTS.md#c-practice) | AUTOMATED |
| practice.metronome | Akustisches und visuelles Metronom | NOT_APPLICABLE | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | NOT_APPLICABLE | C | [C-PRACTICE](CONTRACTS.md#c-practice) | AUTOMATED |
| practice.trainer | Speed Trainer | NOT_APPLICABLE | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | NOT_APPLICABLE | C | [C-PRACTICE](CONTRACTS.md#c-practice) | AUTOMATED |
| practice.tuner | Stimmgerät | IMPLEMENTED | IMPLEMENTED | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | C | [C-TUNER](CONTRACTS.md#c-tuner) | DEVICE_PENDING |
| practice.cursor | Fixierter oder mitlaufender Cursor | NOT_APPLICABLE | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | NOT_APPLICABLE | C | [C-PRACTICE](CONTRACTS.md#c-practice) | AUTOMATED |
| practice.visual_instruments | Griffbrett / Keyboard beim Abspielen | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-PRACTICE](CONTRACTS.md#c-practice) | CONTRACT_ONLY |
| practice.transcription | Editierbare Audio-zu-MIDI-Vorschläge | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-TRANSCRIBE](CONTRACTS.md#c-transcribe) | CONTRACT_ONLY |
| layout.page | Seitenansicht | NOT_APPLICABLE | IMPLEMENTED | IMPLEMENTED | NOT_APPLICABLE | IMPLEMENTED | C | [C-FORMAT](CONTRACTS.md#c-format) | AUTOMATED |
| layout.continuous | Fortlaufende Ansicht | NOT_APPLICABLE | IMPLEMENTED | IMPLEMENTED | NOT_APPLICABLE | IMPLEMENTED | C | [C-FORMAT](CONTRACTS.md#c-format) | AUTOMATED |
| layout.multitrack | Mehrspuransicht | NOT_APPLICABLE | EXPERIMENTAL | SCAFFOLDED | NOT_APPLICABLE | IMPLEMENTED | C | [C-FORMAT](CONTRACTS.md#c-format) | AUTOMATED |
| layout.stylesheet | Layout und Stylesheets | IMPLEMENTED | EXPERIMENTAL | EXPERIMENTAL | NOT_APPLICABLE | EXPERIMENTAL | C | [C-FORMAT](CONTRACTS.md#c-format) | AUTOMATED |
| layout.breaks | Takt- und Systemumbrüche | IMPLEMENTED | IMPLEMENTED | SCAFFOLDED | NOT_APPLICABLE | EXPERIMENTAL | C | [C-FORMAT](CONTRACTS.md#c-format) | AUTOMATED |
| layout.text_lyrics | Texte, Lyrics und Formatierung | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | NOT_APPLICABLE | IMPLEMENTED | C | [C-FORMAT](CONTRACTS.md#c-format) | AUTOMATED |
| layout.chord_symbols | Akkordsymbole | IMPLEMENTED | SCAFFOLDED | EXPERIMENTAL | NOT_APPLICABLE | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | AUTOMATED |
| import.gp3 | GP3-Import | IMPLEMENTED | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | C | [C-FORMAT](CONTRACTS.md#c-format) | DESKTOP_PENDING |
| import.gp4 | GP4-Import | IMPLEMENTED | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | C | [C-FORMAT](CONTRACTS.md#c-format) | DESKTOP_PENDING |
| import.gp5 | GP5-Import | IMPLEMENTED | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | C | [C-FORMAT](CONTRACTS.md#c-format) | DESKTOP_PENDING |
| import.gpx | GP6 / GPX / BCFS / BCFZ | EXPERIMENTAL | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | C | [C-FORMAT](CONTRACTS.md#c-format) | DESKTOP_PENDING |
| import.gp7 | GP7-Import | IMPLEMENTED | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | C | [C-FORMAT](CONTRACTS.md#c-format) | DESKTOP_PENDING |
| import.gp8 | GP8-Import | EXPERIMENTAL | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | C | [C-FORMAT](CONTRACTS.md#c-format) | DESKTOP_PENDING |
| import.musicxml | MusicXML-Import | IMPLEMENTED | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | C | [C-FORMAT](CONTRACTS.md#c-format) | AUTOMATED |
| import.midi | MIDI-Import | IMPLEMENTED | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | C | [C-FORMAT](CONTRACTS.md#c-format) | AUTOMATED |
| import.ascii | ASCII-Import | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| import.powertab | PowerTab-Import | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| import.tabledit | TablEdit-Import | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| import.originals | Originalbytes separat aufbewahren | IMPLEMENTED | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | IMPLEMENTED | C | [C-FORMAT](CONTRACTS.md#c-format) | AUTOMATED |
| import.unknown_fields | Unbekannte GPIF-Felder und Referenzprüfung | EXPERIMENTAL | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | C | [C-FORMAT](CONTRACTS.md#c-format) | AUTOMATED |
| import.loss_report | Importverluste sichtbar machen | IMPLEMENTED | IMPLEMENTED | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | C | [C-FORMAT](CONTRACTS.md#c-format) | AUTOMATED |
| import.security | ZIP-/XML-Ressourcenlimits und sichere Parser | IMPLEMENTED | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | C | [C-FORMAT](CONTRACTS.md#c-format) | AUTOMATED |
| export.native | Vollständiges natives Projekt | IMPLEMENTED | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | IMPLEMENTED | PHASE1 | [C-NATIVE](CONTRACTS.md#c-native) | AUTOMATED |
| export.gp7 | GP7-kompatibler Rückexport | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | IMPLEMENTED | C | [C-FORMAT](CONTRACTS.md#c-format) | DESKTOP_PENDING |
| export.gp8 | GP8-spezifische Dateitreue | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| export.midi | MIDI und Tempo-Map | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | IMPLEMENTED | C | [C-FORMAT](CONTRACTS.md#c-format) | DESKTOP_PENDING |
| export.musicxml | MusicXML-Export | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | IMPLEMENTED | C | [C-FORMAT](CONTRACTS.md#c-format) | DESKTOP_PENDING |
| export.ascii | ASCII-Export | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | IMPLEMENTED | C | [C-FORMAT](CONTRACTS.md#c-format) | AUTOMATED |
| export.pdf | PDF-Export | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | IMPLEMENTED | C | [C-FORMAT](CONTRACTS.md#c-format) | DEVICE_PENDING |
| export.png | PNG-Export | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| export.svg | SVG-Export | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | IMPLEMENTED | C | [C-FORMAT](CONTRACTS.md#c-format) | AUTOMATED |
| export.wav | Gerenderter Audioexport | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | IMPLEMENTED | C | [C-FORMAT](CONTRACTS.md#c-format) | AUTOMATED |
| export.stems | Synchrone WAV-Stems für Cubase | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | IMPLEMENTED | C | [C-FORMAT](CONTRACTS.md#c-format) | DESKTOP_PENDING |
| export.loss_report | Exportverluste und Bestätigung | NOT_APPLICABLE | IMPLEMENTED | NOT_APPLICABLE | NOT_APPLICABLE | EXPERIMENTAL | C | [C-FORMAT](CONTRACTS.md#c-format) | AUTOMATED |
| workflow.palette | Befehlspalette | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-WORKFLOW](CONTRACTS.md#c-workflow) | CONTRACT_ONLY |
| workflow.repeat | Wiederholbare Aktionen | NOT_APPLICABLE | NOT_APPLICABLE | IMPLEMENTED | NOT_APPLICABLE | NOT_APPLICABLE | D | [C-WORKFLOW](CONTRACTS.md#c-workflow) | AUTOMATED |
| workflow.shortcuts | Externe Tastatur und Tastenkürzel | NOT_APPLICABLE | NOT_APPLICABLE | IMPLEMENTED | IMPLEMENTED | NOT_APPLICABLE | D | [C-WORKFLOW](CONTRACTS.md#c-workflow) | AUTOMATED |
| workflow.midi_input | USB-MIDI-Eingabe | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-MIDI](CONTRACTS.md#c-midi) | CONTRACT_ONLY |
| workflow.autosave | Autosave nach Transaktionen | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | NOT_APPLICABLE | NOT_APPLICABLE | PHASE1 | [C-PROJECT](CONTRACTS.md#c-project) | AUTOMATED |
| workflow.recovery | Wiederherstellung | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | NOT_APPLICABLE | NOT_APPLICABLE | PHASE1 | [C-PROJECT](CONTRACTS.md#c-project) | AUTOMATED |
| workflow.settings | Persistente Einstellungen | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | NOT_APPLICABLE | NOT_APPLICABLE | PHASE1 | [C-SETTINGS](CONTRACTS.md#c-settings) | AUTOMATED |
| workflow.saf | Android-Dateiauswahl | IMPLEMENTED | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | IMPLEMENTED | PHASE1 | [C-DOCUMENT](CONTRACTS.md#c-document) | DEVICE_PENDING |
| workflow.accessibility | Beschriftete Aktionen und Fokusnavigation | NOT_APPLICABLE | IMPLEMENTED | IMPLEMENTED | NOT_APPLICABLE | NOT_APPLICABLE | PHASE1 | [C-WORKFLOW](CONTRACTS.md#c-workflow) | AUTOMATED |
| workflow.gestures | Drag-Vorschau, Abbruch und Undo-Grenzen | NOT_APPLICABLE | IMPLEMENTED | EXPERIMENTAL | NOT_APPLICABLE | NOT_APPLICABLE | D | [C-GESTURE](CONTRACTS.md#c-gesture) | AUTOMATED |
| workflow.zoom | Fokusbezogener Zoom und Scrollen | NOT_APPLICABLE | IMPLEMENTED | IMPLEMENTED | NOT_APPLICABLE | NOT_APPLICABLE | D | [C-WORKFLOW](CONTRACTS.md#c-workflow) | AUTOMATED |
| workflow.quickbar | Quick-Bar, Favoriten und Effektsuche | NOT_APPLICABLE | IMPLEMENTED | EXPERIMENTAL | IMPLEMENTED | NOT_APPLICABLE | D | [C-WORKFLOW](CONTRACTS.md#c-workflow) | AUTOMATED |
| workflow.tablet | Tablet- und Querformatstruktur | NOT_APPLICABLE | IMPLEMENTED | IMPLEMENTED | NOT_APPLICABLE | NOT_APPLICABLE | PHASE1 | [C-WORKFLOW](CONTRACTS.md#c-workflow) | AUTOMATED |
| integration.fretlight | Fretlight | LICENSE_BOUND | LICENSE_BOUND | LICENSE_BOUND | LICENSE_BOUND | LICENSE_BOUND | D | [C-INTEGRATION](CONTRACTS.md#c-integration) | CONTRACT_ONLY |
| integration.mysongbook | mySongBook | LICENSE_BOUND | LICENSE_BOUND | LICENSE_BOUND | LICENSE_BOUND | LICENSE_BOUND | D | [C-INTEGRATION](CONTRACTS.md#c-integration) | CONTRACT_ONLY |
| integration.vst | VST-Hosting evaluieren | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-INTEGRATION](CONTRACTS.md#c-integration) | CONTRACT_ONLY |
| integration.auv3 | AUv3 auf iOS evaluieren | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-INTEGRATION](CONTRACTS.md#c-integration) | CONTRACT_ONLY |
| integration.usb_audio | USB-Audio und Live-Monitoring | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-INTEGRATION](CONTRACTS.md#c-integration) | CONTRACT_ONLY |
