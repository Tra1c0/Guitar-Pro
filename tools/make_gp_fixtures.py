#!/usr/bin/env python3
"""Writes own synthetic Guitar Pro 3/4/5 fixtures and JSON oracles into fixtures/gp/.

Requires PyGuitarPro 0.11 (LGPL-3.0, https://github.com/Perlence/PyGuitarPro). It is a generator-only
dependency and is not bundled. Run: `python3 -I tools/make_gp_fixtures.py` with PyGuitarPro installed.

All music below is authored for RiffWorkbench (CC0-1.0); no third-party tabs are used. The oracle next to
each file is what PyGuitarPro reads back from the written bytes. The Kotlin importer tests in
adapter/formats compare their result against it. Output is deterministic; the gp/ lines of
fixtures/SHA256SUMS are rewritten, other lines are kept.
"""
import hashlib
import json
from fractions import Fraction
from importlib.metadata import version
from pathlib import Path

import guitarpro as gp
from guitarpro import gp3 as gp3_module

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'fixtures' / 'gp'
FILES = [('drop-a-band.gp3', (3, 0, 0)), ('drop-a-band.gp4', (4, 0, 6)),
         ('drop-a-band.gp5', (5, 1, 0)), ('drop-a-band-v500.gp5', (5, 0, 0))]


# PyGuitarPro 0.11 reads and writes GP3/GP4 grace notes as fret, dynamic, duration, transition. The format
# description, PyGuitarPro's own GP5 code and other readers use fret, dynamic, transition, duration. Both
# directions are patched so the fixture bytes follow the documented order (GP5 is not affected).
def _read_grace(self):
    grace = gp.GraceEffect()
    grace.fret = self.readI8()
    grace.velocity = self.unpackVelocity(self.readU8())
    grace.transition = gp.GraceEffectTransition(self.readI8())
    grace.duration = 1 << (7 - self.readU8())
    grace.isDead = grace.fret == -1
    grace.isOnBeat = False
    return grace


def _write_grace(self, grace):
    self.writeI8(grace.fret)
    self.writeU8(self.packVelocity(grace.velocity))
    self.writeI8(grace.transition.value)
    self.writeU8(8 - grace.duration.bit_length())


gp3_module.GP3File.readGrace = _read_grace
gp3_module.GP3File.writeGrace = _write_grace

GUITAR = [64, 59, 55, 50, 45, 40, 33]  # GP string 1 (highest) first: E4 B3 G3 D3 A2 E2 A1 (Drop A)
BASS = [43, 38, 33, 28]
ACOUSTIC = [64, 59, 55, 50, 45, 40]
HH, KICK, SNARE, CRASH = 42, 36, 38, 49


def n(string, fret, **effect):
    return string, fret, effect


def add(measure, voice, value, notes=(), *, dotted=False, tuplet=(1, 1), status=None, text=None, effect=None,
        mix=None, chord=None, octave=None, display=None):
    target = measure.voices[voice]
    beat = gp.Beat(target, duration=gp.Duration(value, dotted, gp.Tuplet(*tuplet)), text=text,
                   status=status or (gp.BeatStatus.normal if notes else gp.BeatStatus.rest))
    if effect is not None:
        beat.effect = effect
    beat.effect.chord = chord
    beat.effect.mixTableChange = mix
    beat.octave = octave or gp.Octave.none
    beat.display = display or gp.BeatDisplay()
    for string, fret, kw in notes:
        kind = kw.pop('type', gp.NoteType.normal)
        velocity = kw.pop('velocity', gp.Velocities.default)
        percent, swap = kw.pop('durationPercent', 1.0), kw.pop('swapAccidentals', False)
        independent = kw.pop('independent', False)
        note = gp.Note(beat, value=fret, string=string, type=kind, velocity=velocity, durationPercent=percent,
                       swapAccidentals=swap, effect=gp.NoteEffect(**kw))
        if independent:  # GP3/GP4 "time-independent duration" bytes, written by PyGuitarPro when set
            note.duration, note.tuplet = 0, 0
        beat.notes.append(note)
    target.beats.append(beat)


def chord(name, frets, first_fret, new_format, barres=(), fingerings=()):
    return gp.Chord(7, name=name, firstFret=first_fret, strings=list(frets), newFormat=new_format, sharp=True,
                    root=gp.PitchClass(4), type=gp.ChordType.power, extension=gp.ChordExtension.none,
                    bass=gp.PitchClass(4), tonality=gp.ChordAlteration.perfect, add=False,
                    fifth=gp.ChordAlteration.perfect, ninth=gp.ChordAlteration.perfect,
                    eleventh=gp.ChordAlteration.perfect, barres=list(barres), omissions=[True] * 7,
                    fingerings=[gp.Fingering(f) for f in fingerings], show=True)


def tempo(bpm, duration=0):
    return gp.MixTableChange(tempo=gp.MixTableItem(bpm, duration), hideTempo=False)


def track(song, number, name, tuning, channel, program, volume, balance, color, *, percussion=False, capo=0,
          reverb=0):
    t = gp.Track(song, number, name=name, strings=[gp.GuitarString(i + 1, v) for i, v in enumerate(tuning)],
                 measures=[], isPercussionTrack=percussion, offset=capo, fretCount=24, color=gp.Color(*color))
    effect_channel = channel if percussion else channel + 1
    t.channel = gp.MidiChannel(channel=channel, effectChannel=effect_channel, instrument=program, volume=volume,
                               balance=balance, reverb=reverb)
    t.measures = [gp.Measure(t, header) for header in song.measureHeaders]
    song.tracks.append(t)
    return t


def build(major):
    v4, v5 = major >= 4, major >= 5
    song = gp.Song(tracks=[], measureHeaders=[])
    song.title = f'RiffWorkbench GP{major} Übungsdatei'
    song.subtitle = 'Synthetische Testdaten'
    song.artist = 'RiffWorkbench'
    song.album = 'Eigene Fixtures'
    song.words = song.music = 'RiffWorkbench-Team'
    song.copyright = 'CC0-1.0'
    song.tab = 'tools/make_gp_fixtures.py'
    song.instructions = 'Drop A, Bass, Drums, Akustik mit Kapo'
    song.notice = ['Eigene Inhalte, keine Fremd-Tabs.', 'Gegenprüfung mit PyGuitarPro 0.11.']
    song.tempo = 150
    song.key = gp.KeySignature.EMinor if v4 else gp.KeySignature.CMajor
    if v4:
        lines = [gp.LyricLine(1, 'Riff ein-mal Rif-fe'), gp.LyricLine(4, 'sie-ben')]
        song.lyrics = gp.Lyrics(trackChoice=1, lines=lines + [gp.LyricLine() for _ in range(3)])

    signatures = [(4, 4), (4, 4), (4, 4), (7, 8), (7, 8), (4, 4)]
    for number, (num, den) in enumerate(signatures, 1):
        header = gp.MeasureHeader(number=number, timeSignature=gp.TimeSignature(num, gp.Duration(den)))
        song.addMeasureHeader(header)
    h = song.measureHeaders
    h[0].isRepeatOpen = True
    h[0].marker = gp.Marker('Intro', gp.Color(255, 0, 0))
    h[1].repeatAlternative, h[1].repeatClose = 0b01, 1
    h[2].repeatAlternative = 0b10
    h[3].marker = gp.Marker('Riff 7/8', gp.Color(0, 128, 255))
    if v4:
        h[0].keySignature = gp.KeySignature.EMinor
        for header in h[1:3]:
            header.keySignature = gp.KeySignature.EMinor
        for header in h[3:]:
            header.keySignature = gp.KeySignature.DMinor
        h[3].hasDoubleBar = True
    if major == 4:
        h[0].tripletFeel = gp.TripletFeel.eighth  # GP3/GP4 store one song-wide flag
    if v5:
        h[5].tripletFeel = gp.TripletFeel.sixteenth
        h[3].direction = gp.DirectionSign('Segno')
        h[4].direction = gp.DirectionSign('Fine')
        h[5].fromDirection = gp.DirectionSign('Da Segno al Fine')

    guitar = track(song, 1, 'Gitarre Drop A', GUITAR, 0, 30, 104, 64, (255, 0, 0))
    bass = track(song, 2, 'Bass', BASS, 2, 33, 96, 32, (0, 160, 0))
    drums = track(song, 3, 'Drums', [0] * 6, 9, 0, 112, 80, (0, 0, 255), percussion=True)
    acoustic = track(song, 4, 'Akustik Kapo 2', ACOUSTIC, 4, 25, 80, 64, (200, 120, 0), capo=2, reverb=40)
    if v5:
        acoustic.isMute = True
        acoustic.useRSE = True

    # Guitar: chugs, dead note, slide, hammer-on, harmonics, bend/release, let ring, stroke, grace, tie, tuplets.
    m = guitar.measures
    pm = {'palmMute': True} if v4 else {}
    add(m[0], 0, 8, [n(7, 0, **pm)], text='PM ab hier', display=gp.BeatDisplay(breakBeam=v5))
    add(m[0], 0, 8, [n(7, 0, **pm)], display=gp.BeatDisplay(breakSecondary=2 if v5 else 0))
    add(m[0], 0, 8, [n(7, 0, type=gp.NoteType.dead)])
    add(m[0], 0, 8, [n(7, 3, slides=[gp.SlideType.shiftSlideTo])])
    add(m[0], 0, 8, [n(7, 5)])
    add(m[0], 0, 8, [n(6, 5, hammer=True)])
    add(m[0], 0, 8, [n(6, 7)])
    add(m[0], 0, 8, [n(5, 2)], effect=gp.BeatEffect(pickStroke=gp.BeatStrokeDirection.down) if v4 else None)
    if v5:
        add(m[0], 1, 2, [n(1, 0)])
        add(m[0], 1, 2, [n(2, 1, letRing=True, slides=[gp.SlideType.intoFromBelow, gp.SlideType.outDownwards])])
    bend = gp.BendEffect(type=gp.BendType.bendRelease, value=100,
                         points=[gp.BendPoint(0, 0), gp.BendPoint(4, 4), gp.BendPoint(8, 4), gp.BendPoint(12, 0)])
    e5 = chord('E5', [-1, -1, -1, 2, 2, 0, -1], 1, v4, fingerings=[-2, -2, -2, 3, 2, -1, -2])
    add(m[1], 0, 4, [n(3, 12, harmonic=gp.NaturalHarmonic())])
    add(m[1], 0, 4, [n(2, 7, bend=bend)])
    add(m[1], 0, 4, [n(6, 0, letRing=True), n(5, 2, letRing=True), n(4, 2, letRing=True)], chord=e5,
        effect=gp.BeatEffect(stroke=gp.BeatStroke(gp.BeatStrokeDirection.down, gp.Duration.sixteenth)))
    add(m[1], 0, 4, [n(1, 12, vibrato=True, grace=gp.GraceEffect(duration=16, fret=10,
                                                                 velocity=gp.Velocities.mezzoForte))])
    if v5:
        add(m[1], 1, 4, [], status=gp.BeatStatus.empty)
    dip = gp.BendEffect(type=gp.BendType.dip, value=50,
                        points=[gp.BendPoint(0, 0), gp.BendPoint(6, -2), gp.BendPoint(12, 0)])
    tremolo = {'tremoloPicking': gp.TremoloPickingEffect(gp.Duration(16))} if v4 else {}
    add(m[2], 0, 2, [n(7, 0, **tremolo)], effect=gp.BeatEffect(tremoloBar=dip))
    add(m[2], 0, 4, [n(5, 2, harmonic=gp.PinchHarmonic() if v4 else gp.ArtificialHarmonic())])
    add(m[2], 0, 4)
    if v5:
        m[2].lineBreak = gp.LineBreak.break_
    add(m[3], 0, 4, [n(7, 0)], dotted=True, mix=tempo(120))
    add(m[3], 0, 8, [n(7, 1)])
    add(m[3], 0, 4, [n(6, 2)])
    add(m[3], 0, 8, [n(6, 3)])
    add(m[4], 0, 8, [n(6, 3, type=gp.NoteType.tie)])
    for fret in (0, 2, 3):
        add(m[4], 0, 8, [n(5, fret)], tuplet=(3, 2))
    add(m[4], 0, 4, [n(4, 0, **({'trill': gp.TrillEffect(2, gp.Duration(16))} if v4 else {}))])
    add(m[4], 0, 4)
    fingering = {'leftHandFinger': gp.Fingering.index, 'rightHandFinger': gp.Fingering.middle} if v4 else {}
    add(m[5], 0, 16, [n(3, 2, staccato=v4, independent=not v5, **fingering)])
    add(m[5], 0, 16, [n(3, 2, accentuatedNote=v4, durationPercent=0.75 if v5 else 1.0)])
    add(m[5], 0, 16, [n(3, 2, heavyAccentuatedNote=v5, accentuatedNote=major == 4, swapAccidentals=v5)])
    add(m[5], 0, 16, [n(3, 2, ghostNote=True, velocity=gp.Velocities.piano)])
    barre = chord('D/A', [2, 3, 2, -1, -1, -1, -1], 2, True, barres=[gp.Barre(2, 1, 3)])
    add(m[5], 0, 4, [n(3, 2), n(2, 3), n(1, 2)], mix=tempo(100, 2) if v5 else None, chord=barre,
        effect=gp.BeatEffect(stroke=gp.BeatStroke(gp.BeatStrokeDirection.up, gp.Duration.thirtySecond)))
    add(m[5], 0, 2)

    # Bass: slap/pop, dead note, tie inside a bar, pull-off, let ring, fade-in, wide vibrato, mix-table volume.
    m = bass.measures
    for fret, extra in ((5, {}), (5, {}), (5, {'type': gp.NoteType.dead})):
        add(m[0], 0, 4, [n(4, fret, **extra)])
    add(m[0], 0, 4, [n(4, 3)], effect=gp.BeatEffect(slapEffect=gp.SlapEffect.slapping))
    add(m[1], 0, 2, [n(3, 0)])
    add(m[1], 0, 2, [n(3, 0, type=gp.NoteType.tie)])
    add(m[2], 0, 1, [n(4, 0, letRing=True, **({'harmonic': gp.ArtificialHarmonic()} if v5 else {}))])
    # Dead grace note with a slide transition: GP5 has a flag (and on-beat), GP3/GP4 store fret -1.
    grace = gp.GraceEffect(duration=32, fret=2 if v5 else -1, isDead=True, isOnBeat=v5,
                           transition=gp.GraceEffectTransition.slide)
    add(m[3], 0, 4, [n(3, 0, grace=grace)], dotted=True)
    add(m[3], 0, 8, [n(3, 0)])
    add(m[3], 0, 4, [n(3, 5, hammer=True)])
    add(m[3], 0, 8, [n(3, 3)])
    for i in range(7):
        effect = gp.BeatEffect(slapEffect=gp.SlapEffect.popping) if i == 6 else None
        wah = gp.MixTableChange(wah=gp.WahEffect(50, display=True)) if v5 and i == 0 else None
        slide = {'slides': [gp.SlideType.legatoSlideTo]} if i == 2 else {}
        add(m[4], 0, 8, [n(3, 0, **slide) if i < 4 else n(2, 2)], effect=effect, mix=wah)
    add(m[5], 0, 4, [n(4, 0)], effect=gp.BeatEffect(vibrato=True, fadeIn=True),
        mix=gp.MixTableChange(instrument=gp.MixTableItem(34), volume=gp.MixTableItem(10, 0)))
    add(m[5], 0, 2, dotted=True)

    # Drums: HH/kick/snare, accent and ghost dynamics, 7/8 grid, quintuplet; GP5 puts the kick in voice 2.
    m = drums.measures

    def groove(measure, count, value, kicks, snares, ghost=None):
        for i in range(count):
            notes = [n(1, HH)]
            if i in snares:
                notes.append(n(3, SNARE, accentuatedNote=v4 and i == snares[0]))
            if i == ghost:
                notes.append(n(3, SNARE, ghostNote=True, velocity=gp.Velocities.pianissimo))
            if i in kicks and not v5:
                notes.append(n(5, KICK))
            add(measure, 0, value, notes)
        if v5:
            for i in range(count):
                add(measure, 1, value, [n(5, KICK)] if i in kicks else [])

    groove(m[0], 8, 8, (0, 4), (2, 6), ghost=7)
    if major == 4:
        add(m[0], 0, 8, [n(3, SNARE)])  # overfull bar: the importer must drop this beat and report it
    groove(m[1], 4, 4, (0, 2), (1, 3))
    groove(m[2], 4, 4, (0, 2), (1, 3))
    groove(m[3], 7, 8, (0, 3), (2, 5))
    groove(m[4], 7, 8, (0, 3), (2, 5))
    for _ in range(5):
        add(m[5], 0, 16, [n(3, SNARE)], tuplet=(5, 4))
    add(m[5], 0, 4, [n(2, CRASH), n(5, KICK)])
    add(m[5], 0, 2)

    # Acoustic with capo 2: up-stroke chord, more harmonics, rests, empty beats and (GP3) an incomplete bar.
    m = acoustic.measures
    add(m[0], 0, 1, [n(3, 0), n(2, 1), n(1, 0, **({'harmonic': gp.SemiHarmonic()} if v5 else {}))],
        effect=gp.BeatEffect(stroke=gp.BeatStroke(gp.BeatStrokeDirection.up, gp.Duration.sixteenth), hasRasgueado=v4))
    add(m[1], 0, 1)
    add(m[2], 0, 1)
    add(m[3], 0, 4, status=gp.BeatStatus.empty)
    add(m[4], 0, 4, status=gp.BeatStatus.empty)
    add(m[5], 0, 2 if major == 3 else 1, [n(2, 3, harmonic=gp.TappedHarmonic(15) if v5 else gp.ArtificialHarmonic())],
        octave=gp.Octave.ottava if v5 else None)
    return song


def prune(values):
    """Drops absent/default entries (None, False, empty, 'none', fingering -1, duration percent 1.0)."""
    def default(key, value):
        return (value is None or value is False or value == 'none' or (isinstance(value, (list, dict)) and not value)
                or (key in ('leftFinger', 'rightFinger') and value == -1)
                or (key == 'durationPercent' and value == 1.0))
    return {k: v for k, v in values.items() if not default(k, v)}


def frac(value):
    value = Fraction(value)
    return f'{value.numerator}/{value.denominator}'


def bend_json(bend):
    if bend is None:
        return None
    return {'type': bend.type.value, 'value': bend.value,
            'points': [[p.position, p.value, bool(p.vibrato)] for p in bend.points]}


def note_json(note):
    e = note.effect
    harmonic = None
    if e.harmonic is not None:
        harmonic = {'type': e.harmonic.type}
        if isinstance(e.harmonic, gp.TappedHarmonic):
            harmonic['fret'] = e.harmonic.fret
        if isinstance(e.harmonic, gp.ArtificialHarmonic) and e.harmonic.pitch is not None:
            harmonic['pitch'] = e.harmonic.pitch.value
            harmonic['octave'] = getattr(e.harmonic.octave, 'value', e.harmonic.octave)
    grace = None
    if e.grace is not None:
        g = e.grace
        grace = {'fret': g.fret, 'velocity': g.velocity, 'duration': g.duration,
                 'durationCode': 8 - g.duration.bit_length(), 'transition': g.transition.value,
                 'dead': bool(g.isDead), 'onBeat': bool(g.isOnBeat)}
    return prune({
        'string': note.string, 'value': note.value, 'type': note.type.name, 'velocity': note.velocity,
        'durationPercent': note.durationPercent, 'swapAccidentals': bool(note.swapAccidentals),
        'accent': bool(e.accentuatedNote), 'heavyAccent': bool(e.heavyAccentuatedNote), 'ghost': bool(e.ghostNote),
        'hammer': bool(e.hammer), 'letRing': bool(e.letRing), 'palmMute': bool(e.palmMute),
        'staccato': bool(e.staccato), 'vibrato': bool(e.vibrato), 'slides': [s.name for s in e.slides],
        'harmonic': harmonic, 'bend': bend_json(e.bend), 'grace': grace,
        'trill': None if e.trill is None else {'fret': e.trill.fret, 'duration': e.trill.duration.value},
        'tremoloPicking': None if e.tremoloPicking is None else e.tremoloPicking.duration.value,
        'leftFinger': e.leftHandFinger.value, 'rightFinger': e.rightHandFinger.value,
    })


def mix_json(mix):
    if mix is None:
        return None
    items = {}
    for name in ('instrument', 'volume', 'balance', 'chorus', 'reverb', 'phaser', 'tremolo', 'tempo'):
        item = getattr(mix, name)
        items[name] = None if item is None else [item.value, item.duration]
    items['wah'] = None if mix.wah is None else mix.wah.value
    items['hideTempo'] = bool(mix.hideTempo)
    return items


def chord_json(chord):
    if chord is None:
        return None
    return {'name': chord.name, 'firstFret': chord.firstFret, 'strings': list(chord.strings),
            'fingerings': [f.value for f in chord.fingerings], 'barres': len(chord.barres),
            'newFormat': bool(chord.newFormat)}


def beat_json(beat, measure_start):
    e = beat.effect
    return prune({
        'offset': frac(Fraction(beat.start - measure_start) / (4 * gp.Duration.quarterTime)),
        'status': beat.status.name, 'duration': beat.duration.value, 'dotted': bool(beat.duration.isDotted),
        'tuplet': [beat.duration.tuplet.enters, beat.duration.tuplet.times], 'text': beat.text,
        'chord': chord_json(e.chord), 'mix': mix_json(e.mixTableChange),
        'stroke': [e.stroke.direction.name, e.stroke.value] if e.stroke.direction.value else None,
        'pickStroke': e.pickStroke.name, 'rasgueado': bool(e.hasRasgueado), 'wideVibrato': bool(e.vibrato),
        'fadeIn': bool(e.fadeIn),
        'slap': e.slapEffect.name, 'tremoloBar': bend_json(e.tremoloBar), 'octave': beat.octave.name,
        'notes': [note_json(note) for note in sorted(beat.notes, key=lambda x: x.string)],
    })


def oracle(path):
    song = gp.parse(str(path))
    headers = []
    for header in song.measureHeaders:
        headers.append({
            'number': header.number, 'start': header.start, 'numerator': header.timeSignature.numerator,
            'denominator': header.timeSignature.denominator.value, 'repeatOpen': bool(header.isRepeatOpen),
            'repeatClose': header.repeatClose, 'alternative': header.repeatAlternative,
            'marker': None if header.marker is None else {
                'title': header.marker.title, 'color': [header.marker.color.r, header.marker.color.g,
                                                        header.marker.color.b]},
            'key': list(header.keySignature.value), 'tripletFeel': header.tripletFeel.value,
            'doubleBar': bool(header.hasDoubleBar),
            'direction': None if header.direction is None else header.direction.name,
            'fromDirection': None if header.fromDirection is None else header.fromDirection.name,
        })
    tracks = []
    for t in song.tracks:
        c = t.channel
        tracks.append({
            'number': t.number, 'name': t.name, 'percussion': bool(t.isPercussionTrack),
            'strings': [[s.number, s.value] for s in t.strings], 'fretCount': t.fretCount, 'capo': t.offset,
            'solo': bool(t.isSolo), 'mute': bool(t.isMute),
            'channel': {'channel': c.channel, 'effectChannel': c.effectChannel, 'instrument': c.instrument,
                        'volume': c.volume, 'balance': c.balance, 'chorus': c.chorus, 'reverb': c.reverb,
                        'phaser': c.phaser, 'tremolo': c.tremolo, 'bank': c.bank},
            'measures': [{'lineBreak': measure.lineBreak.value,
                          'voices': [[beat_json(b, measure.start) for b in voice.beats] for voice in measure.voices]}
                         for measure in t.measures],
        })
    lyrics = getattr(song, 'lyrics', None)
    return {
        'file': path.name, 'version': song.version, 'generator': f'PyGuitarPro {version("PyGuitarPro")}',
        'title': song.title, 'subtitle': song.subtitle, 'artist': song.artist, 'album': song.album,
        'words': song.words, 'music': song.music, 'copyright': song.copyright, 'tab': song.tab,
        'instructions': song.instructions, 'notice': list(song.notice), 'tempo': song.tempo,
        'key': list(song.key.value),
        'lyrics': None if lyrics is None else {'track': lyrics.trackChoice,
                                               'lines': [[line.startingMeasure, line.lyrics] for line in lyrics.lines]},
        'measures': headers, 'tracks': tracks,
    }


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    sums = {}
    for name, version_tuple in FILES:
        path = OUT / name
        gp.write(build(version_tuple[0]), str(path), version=version_tuple)
        oracle_path = OUT / f'{name}.json'
        oracle_path.write_text(json.dumps(oracle(path), indent=1, sort_keys=True, ensure_ascii=False) + '\n',
                               encoding='utf-8')
        for p in (path, oracle_path):
            sums[f'gp/{p.name}'] = hashlib.sha256(p.read_bytes()).hexdigest()
    checksum_file = ROOT / 'fixtures' / 'SHA256SUMS'
    lines = [line for line in checksum_file.read_text().splitlines()
             if line and not line.split('  ', 1)[1].startswith('gp/')]
    lines += [f'{digest}  {name}' for name, digest in sums.items()]
    checksum_file.write_text('\n'.join(sorted(lines, key=lambda line: line.split('  ', 1)[1])) + '\n')
    print(f'Wrote {len(sums)} files to {OUT}')


if __name__ == '__main__':
    main()
