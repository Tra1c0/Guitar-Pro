# Paritätsmatrix

Generiert aus `spec/features.json`. `IMPLEMENTED` bezieht sich auf den ausdrücklich beschriebenen Phase-1-Umfang. Teilweise vorhandene Eingaben sind im Namen und Vertragsumfang eingegrenzt; ein Modell allein gilt nicht als fertige Bearbeitung oder Klangerzeugung.

## GP-Dateitreue

GP3/4/5, GPX und GP7/8 sind in Phase 1 in allen fünf Fähigkeiten noch nicht freigegeben. GP7-Rückexport und GP8-spezifische Treue werden getrennt verifiziert. Native Speicherung einer Eigenschaft ist kein GP-Fidelity-Nachweis.

## Anwendungskern und Austausch

Lesen/Darstellen/Bearbeiten beschreiben den eigenen Anwendungskern, soweit implementiert. Formatzeilen benennen die jeweilige Import-/Exportgrenze. Nur das native Format ist aktuell ausgegeben. Nicht freigegebene Merkmals-/Formatkombinationen bleiben offen; native Bewahrung ist separat durch den vollständigen Projekt-Roundtrip abgedeckt.

Verifikation `AUTOMATED` bezeichnet lokale JVM-/Robolectric-Tests für den beschriebenen Teilumfang. Geräte-, Hör-, GP8- und Cubase-Abnahmen sind separat offen.

| ID | Funktion | Lesen | Darstellen | Bearbeiten | Playback | Export | Etappe | Vertrag | Prüfung |
|---|---|---|---|---|---|---|---|---|---|
| projects.native | Native Projekte | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | SCAFFOLDED | IMPLEMENTED | PHASE1 | [C-PROJECT](CONTRACTS.md#c-project) | AUTOMATED |
| projects.metadata | Projekttitel und Metadatenmodell | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | PHASE1 | [C-EDITOR](CONTRACTS.md#c-editor) | AUTOMATED |
| tracks.create | Spuren anlegen | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | PHASE1 | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | AUTOMATED |
| tracks.duplicate | Spuren duplizieren | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | B | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | CONTRACT_ONLY |
| tracks.reorder | Spuren umsortieren | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | PHASE1 | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | AUTOMATED |
| tracks.remove | Spuren entfernen | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | PHASE1 | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | AUTOMATED |
| tracks.templates | Instrumentvorlagen | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | PHASE1 | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | AUTOMATED |
| measures.insert | Takte einfügen | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | PHASE1 | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | AUTOMATED |
| measures.delete | Takte löschen | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | B | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | CONTRACT_ONLY |
| clipboard.notes | Noten kopieren und einfügen | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | B | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | CONTRACT_ONLY |
| clipboard.sections | Takte und Abschnitte kopieren | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | B | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | CONTRACT_ONLY |
| arrangement.sections | Abschnittsmarker | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | B | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | CONTRACT_ONLY |
| arrangement.ripple | Ripple mit Vorschau | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | B | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | CONTRACT_ONLY |
| selection.batch | Auswahlbearbeitung | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | B | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | CONTRACT_ONLY |
| history.undo | Undo und Redo | SCAFFOLDED | SCAFFOLDED | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | PHASE1 | [C-EDITOR](CONTRACTS.md#c-editor) | AUTOMATED |
| history.snapshots | Benannte Schnappschüsse | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | PHASE1 | [C-SNAPSHOT](CONTRACTS.md#c-snapshot) | AUTOMATED |
| history.branching | Varianten und verzweigte Historie | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | B | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | CONTRACT_ONLY |
| signature.time | Taktwechsel | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | PHASE1 | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | AUTOMATED |
| signature.key | Tonartwechsel | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | B | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | CONTRACT_ONLY |
| tempo.changes | Tempoänderungen | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | PHASE1 | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | AUTOMATED |
| repeats.basic | Wiederholungen | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | B | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | CONTRACT_ONLY |
| repeats.endings | Alternative Enden | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | B | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | CONTRACT_ONLY |
| repeats.navigation | Da Capo, Dal Segno, Coda und Fine | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | B | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | CONTRACT_ONLY |
| repeats.unfolded | Aufgelöste Wiedergabe mit Durchlauf | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | B | [C-ARRANGEMENT](CONTRACTS.md#c-arrangement) | CONTRACT_ONLY |
| notation.tab | TAB | SCAFFOLDED | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | PHASE1 | [C-NOTATION](CONTRACTS.md#c-notation) | AUTOMATED |
| notation.standard | Klassische Notation | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-NOTATION](CONTRACTS.md#c-notation) | CONTRACT_ONLY |
| notation.slash | Slash-Notation | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-NOTATION](CONTRACTS.md#c-notation) | CONTRACT_ONLY |
| notation.percussion | Schlagzeugnotation | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-NOTATION](CONTRACTS.md#c-notation) | CONTRACT_ONLY |
| notation.jianpu | Jianpu | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-NOTATION](CONTRACTS.md#c-notation) | CONTRACT_ONLY |
| rhythm.duration | Notenwerte | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | PHASE1 | [C-NOTATION](CONTRACTS.md#c-notation) | AUTOMATED |
| rhythm.dots | Punktierungen | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | PHASE1 | [C-NOTATION](CONTRACTS.md#c-notation) | AUTOMATED |
| rhythm.rest | Pausen | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | PHASE1 | [C-NOTATION](CONTRACTS.md#c-notation) | AUTOMATED |
| rhythm.tie | Haltebögen | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-NOTATION](CONTRACTS.md#c-notation) | CONTRACT_ONLY |
| rhythm.slur | Bindebögen | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-NOTATION](CONTRACTS.md#c-notation) | CONTRACT_ONLY |
| rhythm.pickup | Auftakt | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-NOTATION](CONTRACTS.md#c-notation) | CONTRACT_ONLY |
| rhythm.swing | Swing und Triplet Feel | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-NOTATION](CONTRACTS.md#c-notation) | CONTRACT_ONLY |
| rhythm.tuplets | Verschachtelte Tuplets | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-NOTATION](CONTRACTS.md#c-notation) | CONTRACT_ONLY |
| rhythm.ornaments | Verzierungen | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-NOTATION](CONTRACTS.md#c-notation) | CONTRACT_ONLY |
| rhythm.fermata | Fermaten | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-NOTATION](CONTRACTS.md#c-notation) | CONTRACT_ONLY |
| rhythm.grace | Vorschlagsnoten | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-NOTATION](CONTRACTS.md#c-notation) | CONTRACT_ONLY |
| rhythm.voices | Vier unabhängige Stimmen | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | PHASE1 | [C-NOTATION](CONTRACTS.md#c-notation) | AUTOMATED |
| strings.count | 3–10 Saiten | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | PHASE1 | [C-INSTRUMENT](CONTRACTS.md#c-instrument) | AUTOMATED |
| strings.tuning | Freie Stimmungen | IMPLEMENTED | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | PHASE1 | [C-INSTRUMENT](CONTRACTS.md#c-instrument) | AUTOMATED |
| strings.capo | Capo und Teil-Capo | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | PHASE1 | [C-INSTRUMENT](CONTRACTS.md#c-instrument) | AUTOMATED |
| strings.retune_policy | Tonhöhe oder Griffposition erhalten | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | B | [C-INSTRUMENT](CONTRACTS.md#c-instrument) | CONTRACT_ONLY |
| notes.fret | Bund- und Saiteneingabe | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | PHASE1 | [C-INSTRUMENT](CONTRACTS.md#c-instrument) | AUTOMATED |
| notes.multidigit | Zweistellige Bünde mit Commit | SCAFFOLDED | SCAFFOLDED | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | PHASE1 | [C-INSTRUMENT](CONTRACTS.md#c-instrument) | AUTOMATED |
| notes.fingering | Fingerings und alternative Griffe | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | B | [C-INSTRUMENT](CONTRACTS.md#c-instrument) | CONTRACT_ONLY |
| notes.transpose | Transposition | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | B | [C-INSTRUMENT](CONTRACTS.md#c-instrument) | CONTRACT_ONLY |
| tools.chords | Akkordbibliothek und Griffdiagramme | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | B | [C-INSTRUMENT](CONTRACTS.md#c-instrument) | CONTRACT_ONLY |
| tools.scales | Skalenbibliothek | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | B | [C-INSTRUMENT](CONTRACTS.md#c-instrument) | CONTRACT_ONLY |
| tools.fretboard | Einfache Griffbrett-Eingabe | SCAFFOLDED | IMPLEMENTED | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | PHASE1 | [C-INSTRUMENT](CONTRACTS.md#c-instrument) | AUTOMATED |
| tools.keyboard | Virtuelle Tastatur | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | B | [C-INSTRUMENT](CONTRACTS.md#c-instrument) | CONTRACT_ONLY |
| tools.drumkit | Virtuelles Drumkit | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | B | [C-INSTRUMENT](CONTRACTS.md#c-instrument) | CONTRACT_ONLY |
| fx.palm_mute | Palm-Mute | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | PHASE1 | [C-ARTICULATION](CONTRACTS.md#c-articulation) | AUTOMATED |
| fx.dead | Dead Notes | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | PHASE1 | [C-ARTICULATION](CONTRACTS.md#c-articulation) | AUTOMATED |
| fx.ghost | Ghost Notes | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | CONTRACT_ONLY |
| fx.accent | Akzente | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | CONTRACT_ONLY |
| fx.dynamics | Dynamik | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | CONTRACT_ONLY |
| fx.let_ring | Let Ring | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | CONTRACT_ONLY |
| fx.staccato | Staccato | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | CONTRACT_ONLY |
| fx.hammer_pull | Hammer-on und Pull-off | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | CONTRACT_ONLY |
| fx.slide | Slide-Varianten | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | CONTRACT_ONLY |
| fx.vibrato | Vibrato | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | CONTRACT_ONLY |
| fx.bend | Bend, Prebend und Release | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | CONTRACT_ONLY |
| fx.whammy | Whammy-Kurven | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | CONTRACT_ONLY |
| fx.quartertone | Vierteltöne | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | CONTRACT_ONLY |
| fx.harmonic_natural | Natürliche Harmonics | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | CONTRACT_ONLY |
| fx.harmonic_artificial | Künstliche Harmonics | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | CONTRACT_ONLY |
| fx.harmonic_pinch | Pinch-Harmonics | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | CONTRACT_ONLY |
| fx.tapping | Tapping | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | CONTRACT_ONLY |
| fx.tremolo | Tremolo-Picking | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | CONTRACT_ONLY |
| fx.trill | Triller | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | CONTRACT_ONLY |
| fx.pickstroke | Pickstroke | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | CONTRACT_ONLY |
| fx.pick_scrape | Pick Scrape | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | CONTRACT_ONLY |
| fx.slap_pop | Slap und Pop | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | CONTRACT_ONLY |
| fx.strum | Strum | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | CONTRACT_ONLY |
| fx.arpeggio | Arpeggio | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | CONTRACT_ONLY |
| fx.rasgueado | Rasgueado | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-ARTICULATION](CONTRACTS.md#c-articulation) | CONTRACT_ONLY |
| drums.entry | Kick, Snare und Hi-Hat setzen | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | PHASE1 | [C-DRUM](CONTRACTS.md#c-drum) | AUTOMATED |
| drums.stepgrid | Einfaches 1/16-Stepgrid | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | PHASE1 | [C-DRUM](CONTRACTS.md#c-drum) | AUTOMATED |
| drums.articulations | Percussion-Artikulationen | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | B | [C-DRUM](CONTRACTS.md#c-drum) | CONTRACT_ONLY |
| drums.hihat | Offene und geschlossene Hi-Hat | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | B | [C-DRUM](CONTRACTS.md#c-drum) | CONTRACT_ONLY |
| drums.velocity | Velocity und Ghost Notes | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | B | [C-DRUM](CONTRACTS.md#c-drum) | CONTRACT_ONLY |
| drums.choke | Drum-Chokes | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | B | [C-DRUM](CONTRACTS.md#c-drum) | CONTRACT_ONLY |
| drums.voices | Unabhängige Drumstimmen | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | B | [C-DRUM](CONTRACTS.md#c-drum) | CONTRACT_ONLY |
| drums.odd_grid | Ungerade Raster | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | B | [C-DRUM](CONTRACTS.md#c-drum) | CONTRACT_ONLY |
| drums.element_mix | Lautstärke und Pan pro Drum-Element | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | B | [C-DRUM](CONTRACTS.md#c-drum) | CONTRACT_ONLY |
| band.instruments | Bass, Piano und weitere Instrumente | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | PHASE1 | [C-DRUM](CONTRACTS.md#c-drum) | AUTOMATED |
| sound.gm | General MIDI / SoundFont | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-AUDIO](CONTRACTS.md#c-audio) | CONTRACT_ONLY |
| sound.rse_mapping | RSE-Originaldaten und eigenes Klangmapping | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-AUDIO](CONTRACTS.md#c-audio) | CONTRACT_ONLY |
| sound.banks | Eigene SF2-/Preset-Banks | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-SOUNDBANK](CONTRACTS.md#c-soundbank) | CONTRACT_ONLY |
| sound.sampler | Artikulationsbewusster Gitarrensampler | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-AUDIO](CONTRACTS.md#c-audio) | CONTRACT_ONLY |
| sound.layers | Velocity-Layer, Round Robins und Releases | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-AUDIO](CONTRACTS.md#c-audio) | CONTRACT_ONLY |
| sound.transitions | Slides, Legato und Saiten-Choke | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-AUDIO](CONTRACTS.md#c-audio) | CONTRACT_ONLY |
| sound.independent_bends | Unabhängige Bends pro Saite | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-AUDIO](CONTRACTS.md#c-audio) | CONTRACT_ONLY |
| sound.di_amp | DI, Amp und Cabinet-IR | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-AUDIO](CONTRACTS.md#c-audio) | CONTRACT_ONLY |
| sound.fine_tuning | Fine-Tuning | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-AUDIO](CONTRACTS.md#c-audio) | CONTRACT_ONLY |
| mix.channels | Solo, Mute, Volume und Pan | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | PHASE1 | [C-AUDIO](CONTRACTS.md#c-audio) | AUTOMATED |
| mix.buses | Track-, Bus- und Masterrouting | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-AUDIO](CONTRACTS.md#c-audio) | CONTRACT_ONLY |
| mix.eq_fx | EQ und virtuelle Effekte | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-AUDIO](CONTRACTS.md#c-audio) | CONTRACT_ONLY |
| mix.pedalboard | Pedalboard und Amp-Kette | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-AUDIO](CONTRACTS.md#c-audio) | CONTRACT_ONLY |
| mix.automation | Sound- und Mixautomation | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-AUDIO](CONTRACTS.md#c-audio) | CONTRACT_ONLY |
| sound.line_in | Gitarren-Line-in | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-AUDIO](CONTRACTS.md#c-audio) | CONTRACT_ONLY |
| audio.clock | Native Sample-Clock und Ausgabezeitstempel | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-AUDIO](CONTRACTS.md#c-audio) | CONTRACT_ONLY |
| audio.seek | Seek und Instrumentzustand | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-AUDIO](CONTRACTS.md#c-audio) | CONTRACT_ONLY |
| audio.lifecycle | Audio-Fokus, Unterbrechung und Route-Wechsel | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-AUDIO](CONTRACTS.md#c-audio) | CONTRACT_ONLY |
| audio.latency | Latenz, Underruns und Thermik | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-AUDIO](CONTRACTS.md#c-audio) | CONTRACT_ONLY |
| audio.reference | Referenzaufnahme / GP8-Audio | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-PRACTICE](CONTRACTS.md#c-practice) | CONTRACT_ONLY |
| audio.multiple_stems | Mehrere Audio-Stems | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-PRACTICE](CONTRACTS.md#c-practice) | CONTRACT_ONLY |
| audio.waveform | Mehrstufige Wellenform | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-WAVEFORM](CONTRACTS.md#c-waveform) | CONTRACT_ONLY |
| audio.sync | Sync-Anker und Audio-Offset | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-SYNC](CONTRACTS.md#c-sync) | CONTRACT_ONLY |
| audio.sync_modes | Score-Tempo und Audio-Sync | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-PRACTICE](CONTRACTS.md#c-practice) | CONTRACT_ONLY |
| audio.shared_map | Gemeinsame und getrennte Stem-Sync-Maps | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-PRACTICE](CONTRACTS.md#c-practice) | CONTRACT_ONLY |
| audio.stem_mix | Synth und Stem-Mix unter einer Clock | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-PRACTICE](CONTRACTS.md#c-practice) | CONTRACT_ONLY |
| audio.pitch_stretch | Pitch-Shift und Time-Stretch | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-STRETCH](CONTRACTS.md#c-stretch) | CONTRACT_ONLY |
| audio.decode | Decoder-Seek und Sampleraten | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-DECODE](CONTRACTS.md#c-decode) | CONTRACT_ONLY |
| practice.speed | Relative Geschwindigkeit | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-PRACTICE](CONTRACTS.md#c-practice) | CONTRACT_ONLY |
| practice.loop | A/B-Loop | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-PRACTICE](CONTRACTS.md#c-practice) | CONTRACT_ONLY |
| practice.count_in | Einzähler | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-PRACTICE](CONTRACTS.md#c-practice) | CONTRACT_ONLY |
| practice.metronome | Akustisches und visuelles Metronom | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-PRACTICE](CONTRACTS.md#c-practice) | CONTRACT_ONLY |
| practice.trainer | Speed Trainer | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-PRACTICE](CONTRACTS.md#c-practice) | CONTRACT_ONLY |
| practice.tuner | Stimmgerät | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-TUNER](CONTRACTS.md#c-tuner) | CONTRACT_ONLY |
| practice.cursor | Fixierter oder mitlaufender Cursor | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-PRACTICE](CONTRACTS.md#c-practice) | CONTRACT_ONLY |
| practice.visual_instruments | Griffbrett / Keyboard beim Abspielen | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-PRACTICE](CONTRACTS.md#c-practice) | CONTRACT_ONLY |
| practice.transcription | Editierbare Audio-zu-MIDI-Vorschläge | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-TRANSCRIBE](CONTRACTS.md#c-transcribe) | CONTRACT_ONLY |
| layout.page | Seitenansicht | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| layout.continuous | Fortlaufende Ansicht | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| layout.multitrack | Mehrspuransicht | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| layout.stylesheet | Layout und Stylesheets | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| layout.breaks | Takt- und Systemumbrüche | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| layout.text_lyrics | Texte, Lyrics und Formatierung | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| layout.chord_symbols | Akkordsymbole | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| import.gp3 | GP3-Import | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| import.gp4 | GP4-Import | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| import.gp5 | GP5-Import | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| import.gpx | GP6 / GPX / BCFS / BCFZ | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| import.gp7 | GP7-Import | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| import.gp8 | GP8-Import | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| import.musicxml | MusicXML-Import | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| import.midi | MIDI-Import | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| import.ascii | ASCII-Import | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| import.powertab | PowerTab-Import | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| import.tabledit | TablEdit-Import | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| import.originals | Originalbytes separat aufbewahren | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| import.unknown_fields | Unbekannte GPIF-Felder und Referenzprüfung | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| import.loss_report | Importverluste sichtbar machen | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| import.security | ZIP-/XML-Ressourcenlimits und sichere Parser | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| export.native | Vollständiges natives Projekt | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | IMPLEMENTED | PHASE1 | [C-NATIVE](CONTRACTS.md#c-native) | AUTOMATED |
| export.gp7 | GP7-kompatibler Rückexport | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| export.gp8 | GP8-spezifische Dateitreue | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| export.midi | MIDI und Tempo-Map | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| export.musicxml | MusicXML-Export | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| export.ascii | ASCII-Export | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| export.pdf | PDF-Export | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| export.png | PNG-Export | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| export.svg | SVG-Export | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| export.wav | Gerenderter Audioexport | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| export.stems | Synchrone WAV-Stems für Cubase | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| export.loss_report | Exportverluste und Bestätigung | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | C | [C-FORMAT](CONTRACTS.md#c-format) | CONTRACT_ONLY |
| workflow.palette | Befehlspalette | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-WORKFLOW](CONTRACTS.md#c-workflow) | CONTRACT_ONLY |
| workflow.repeat | Wiederholbare Aktionen | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-WORKFLOW](CONTRACTS.md#c-workflow) | CONTRACT_ONLY |
| workflow.shortcuts | Externe Tastatur und Tastenkürzel | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-WORKFLOW](CONTRACTS.md#c-workflow) | CONTRACT_ONLY |
| workflow.midi_input | USB-MIDI-Eingabe | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-MIDI](CONTRACTS.md#c-midi) | CONTRACT_ONLY |
| workflow.autosave | Autosave nach Transaktionen | SCAFFOLDED | SCAFFOLDED | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | PHASE1 | [C-PROJECT](CONTRACTS.md#c-project) | AUTOMATED |
| workflow.recovery | Wiederherstellung | IMPLEMENTED | SCAFFOLDED | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | PHASE1 | [C-PROJECT](CONTRACTS.md#c-project) | AUTOMATED |
| workflow.settings | Persistente Einstellungen | IMPLEMENTED | IMPLEMENTED | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | PHASE1 | [C-SETTINGS](CONTRACTS.md#c-settings) | AUTOMATED |
| workflow.saf | Android-Dateiauswahl | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | IMPLEMENTED | PHASE1 | [C-DOCUMENT](CONTRACTS.md#c-document) | DEVICE_PENDING |
| workflow.accessibility | Beschriftete Aktionen und Fokusnavigation | SCAFFOLDED | SCAFFOLDED | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | PHASE1 | [C-WORKFLOW](CONTRACTS.md#c-workflow) | AUTOMATED |
| workflow.gestures | Drag-Vorschau, Abbruch und Undo-Grenzen | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-GESTURE](CONTRACTS.md#c-gesture) | CONTRACT_ONLY |
| workflow.zoom | Fokusbezogener Zoom und Scrollen | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-WORKFLOW](CONTRACTS.md#c-workflow) | CONTRACT_ONLY |
| workflow.quickbar | Quick-Bar, Favoriten und Effektsuche | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-WORKFLOW](CONTRACTS.md#c-workflow) | CONTRACT_ONLY |
| workflow.tablet | Tablet- und Querformatstruktur | SCAFFOLDED | IMPLEMENTED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | PHASE1 | [C-WORKFLOW](CONTRACTS.md#c-workflow) | AUTOMATED |
| integration.fretlight | Fretlight | LICENSE_BOUND | LICENSE_BOUND | LICENSE_BOUND | LICENSE_BOUND | LICENSE_BOUND | D | [C-INTEGRATION](CONTRACTS.md#c-integration) | CONTRACT_ONLY |
| integration.mysongbook | mySongBook | LICENSE_BOUND | LICENSE_BOUND | LICENSE_BOUND | LICENSE_BOUND | LICENSE_BOUND | D | [C-INTEGRATION](CONTRACTS.md#c-integration) | CONTRACT_ONLY |
| integration.vst | VST-Hosting evaluieren | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-INTEGRATION](CONTRACTS.md#c-integration) | CONTRACT_ONLY |
| integration.auv3 | AUv3 auf iOS evaluieren | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-INTEGRATION](CONTRACTS.md#c-integration) | CONTRACT_ONLY |
| integration.usb_audio | USB-Audio und Live-Monitoring | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | SCAFFOLDED | D | [C-INTEGRATION](CONTRACTS.md#c-integration) | CONTRACT_ONLY |
