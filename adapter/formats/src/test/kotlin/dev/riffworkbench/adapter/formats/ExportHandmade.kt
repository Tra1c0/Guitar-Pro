package dev.riffworkbench.adapter.formats

import dev.riffworkbench.model.*

/** Small programmatic projects exercising one exporter feature each (become JUnit repros). */
internal object ExportHandmade {
    class B(private val prefix: String) {
        private var n = 0
        fun id() = EntityId("$prefix${n++}")
        fun f(a: Long, b: Long) = Fraction.of(a, b)
        fun note(inst: InstrumentDefinition, s: Int, fr: Int, vararg a: Articulation, links: List<NoteLink> = emptyList(), id: EntityId = id()) =
            Note(id, Pitch(inst.pitch(s, fr)), s, fr, articulations = a.toList(), links = links)
        fun drum(midi: Int, vararg a: Articulation) = Note(id(), Pitch(midi), articulations = a.toList())
        fun pitched(midi: Int, cents: Int = 0, vararg a: Articulation) = Note(id(), Pitch(midi, cents), articulations = a.toList())
        fun beat(o: Fraction, d: WrittenDuration, vararg notes: Note, fermata: Fermata? = null, strum: Strum? = null) = Beat(id(), o, d, notes.toList(), fermata = fermata, strum = strum)
        fun dur(den: Int, dots: Int = 0, vararg t: TupletRatio) = WrittenDuration(den, dots, t.toList())
        fun staff(measures: List<Measure>, bars: List<List<List<Beat>>>, notation: Set<NotationKind> = setOf(NotationKind.TAB)) =
            Staff(id(), notation, measures.mapIndexed { i, m -> Bar(id(), m.id, List(4) { v -> Voice(id(), v, bars.getOrNull(i)?.getOrNull(v) ?: emptyList()) }) })
        fun track(name: String, inst: InstrumentDefinition, measures: List<Measure>, bars: List<List<List<Beat>>>, mix: ChannelMix = ChannelMix()) =
            Track(id(), name, inst, listOf(staff(measures, bars, if (inst.kind == InstrumentKind.PERCUSSION) setOf(NotationKind.PERCUSSION) else if (inst.tuning.isEmpty()) setOf(NotationKind.STANDARD) else setOf(NotationKind.TAB))), mix = mix)
        fun project(title: String, measures: List<Measure>, tracks: List<Track>, tempo: List<TempoChange> = listOf(TempoChange(ScorePosition(measures[0].id), 120.0)), sections: List<Section> = emptyList(), text: List<ScoreText> = emptyList()) =
            Project(id = id(), metadata = ProjectMetadata(title), measures = measures, tracks = tracks, tempo = tempo, sections = sections, text = text)
    }

    val gtr6 = InstrumentDefinition(tuning = listOf(40, 45, 50, 55, 59, 64))
    private fun quarters(b: B, inst: InstrumentDefinition, vararg sf: Pair<Int, Int>) = sf.mapIndexed { i, (s, fr) -> b.beat(b.f(i.toLong(), 4), b.dur(4), b.note(inst, s, fr)) }

    fun bends(): Project {
        val b = B("bd"); val g = gtr6
        val m = List(3) { Measure(b.id()) }
        val full = Articulation.Bend(listOf(PitchCurvePoint(Fraction.ZERO, 0), PitchCurvePoint(b.f(1, 2), 200), PitchCurvePoint(Fraction.ONE, 200)))
        val rel = Articulation.Bend(listOf(PitchCurvePoint(Fraction.ZERO, 0), PitchCurvePoint(b.f(1, 3), 100), PitchCurvePoint(b.f(2, 3), 100), PitchCurvePoint(Fraction.ONE, 0)))
        val pre = Articulation.Bend(listOf(PitchCurvePoint(Fraction.ZERO, 200), PitchCurvePoint(Fraction.ONE, 0)))
        val wh = Articulation.Whammy(listOf(PitchCurvePoint(Fraction.ZERO, 0), PitchCurvePoint(Fraction.ONE, -400)))
        val bar1 = listOf(b.beat(b.f(0, 1), b.dur(4), b.note(g, 3, 7, full)), b.beat(b.f(1, 4), b.dur(4), b.note(g, 4, 8, rel)),
            b.beat(b.f(2, 4), b.dur(4), b.note(g, 2, 9, pre)), b.beat(b.f(3, 4), b.dur(4), b.note(g, 0, 5, wh)))
        val bar2 = listOf(b.beat(b.f(0, 1), b.dur(2), b.note(g, 3, 5, Articulation.Slide(SlideKind.SHIFT))), b.beat(b.f(1, 2), b.dur(2), b.note(g, 3, 7, Articulation.Vibrato())))
        // chord with one bent note (channel-wide pitch bend conflict -> declared loss)
        val bar3 = listOf(b.beat(b.f(0, 1), b.dur(1), b.note(g, 3, 7, full), b.note(g, 4, 8)))
        return b.project("Bends", m, listOf(b.track("Lead", g, m, listOf(listOf(bar1), listOf(bar2), listOf(bar3)))))
    }

    fun tempo(): Project {
        val b = B("tp"); val g = gtr6
        val m = listOf(Measure(b.id(), TimeSignature(4, 4), pickupLength = Fraction.of(1, 4)), Measure(b.id(), TimeSignature(3, 4)), Measure(b.id(), TimeSignature(7, 8), feel = RhythmicFeel.SWING_EIGHTH),
            Measure(b.id(), TimeSignature(6, 8)), Measure(b.id(), TimeSignature(4, 4)))
        val bars = listOf(
            listOf(listOf(b.beat(Fraction.ZERO, b.dur(4), b.note(g, 0, 3)))),
            listOf(quarters(b, g, 1 to 2, 1 to 3, 1 to 5)),
            listOf((0 until 7).map { b.beat(b.f(it.toLong(), 8), b.dur(8), b.note(g, 2, it)) }),
            listOf(listOf(b.beat(Fraction.ZERO, b.dur(4, 1), b.note(g, 3, 2)), b.beat(b.f(3, 8), b.dur(4, 1), b.note(g, 3, 4), fermata = Fermata()))),
            listOf(quarters(b, g, 4 to 0, 4 to 1, 4 to 3, 4 to 5)))
        val tempo = listOf(TempoChange(ScorePosition(m[0].id), 100.0), TempoChange(ScorePosition(m[1].id), 140.0), TempoChange(ScorePosition(m[1].id, Fraction.of(1, 4)), 90.0),
            TempoChange(ScorePosition(m[3].id), 73.3), TempoChange(ScorePosition(m[4].id, Fraction.of(1, 2)), 200.0))
        return b.project("Tempo", m, listOf(b.track("Gtr", g, m, bars)), tempo)
    }

    fun drums(): Project {
        val b = B("dr"); val m = List(2) { Measure(b.id()) }
        val kit = InstrumentDefinition(InstrumentKind.PERCUSSION, emptyList(), emptyList(), midiProgram = 0)
        val bar = (0 until 8).map { k -> b.beat(b.f(k.toLong(), 8), b.dur(8), *listOfNotNull(b.drum(42), if (k % 4 == 0) b.drum(36) else null, if (k % 4 == 2) b.drum(38, Articulation.Percussion(PercussionTechnique.RIMSHOT)) else null, if (k == 0) b.drum(49) else null).toTypedArray()) }
        val bar2 = listOf(b.beat(Fraction.ZERO, b.dur(4), b.drum(46)), b.beat(b.f(1, 4), b.dur(4), b.drum(51)), b.beat(b.f(2, 4), b.dur(4), b.drum(45)), b.beat(b.f(3, 4), b.dur(4), b.drum(57)))
        return b.project("Drums", m, listOf(b.track("Drums", kit, m, listOf(listOf(bar), listOf(bar2))), b.track("Gtr", gtr6, m, listOf(listOf(quarters(b, gtr6, 0 to 0, 0 to 3, 0 to 5, 0 to 3))))))
    }

    fun tuplets(): Project {
        val b = B("tu"); val g = gtr6; val m = List(3) { Measure(b.id()) }
        val t3 = TupletRatio(3, 2); val t5 = TupletRatio(5, 4)
        val bar1 = (0 until 3).map { b.beat(b.f(it.toLong(), 12), b.dur(8, 0, t3), b.note(g, 1, it + 1)) } + (0 until 5).map { b.beat(b.f(1, 4) + b.f(it.toLong(), 20), b.dur(16, 0, t5), b.note(g, 2, it)) } +
            listOf(b.beat(b.f(1, 2), b.dur(4, 1), b.note(g, 3, 2)), b.beat(b.f(7, 8), b.dur(8), b.note(g, 3, 4)))
        // nested: 3:2 eighths where the middle one is split into a 5:4 of 32nds -> total = 1/4
        val outer = b.f(1, 12)
        val bar2 = mutableListOf<Beat>()
        bar2 += b.beat(Fraction.ZERO, b.dur(8, 0, t3), b.note(g, 1, 1))
        for (k in 0 until 5) bar2 += b.beat(outer + Fraction.of(k.toLong(), 60), b.dur(32, 0, t3, t5).let { WrittenDuration(32, 0, listOf(t3, t5)) }, b.note(g, 1, 2 + k))
        bar2 += b.beat(b.f(2, 12), b.dur(8, 0, t3), b.note(g, 1, 3))
        bar2 += b.beat(b.f(1, 4), b.dur(4), b.note(g, 2, 2)); bar2 += b.beat(b.f(1, 2), b.dur(2), b.note(g, 2, 4))
        // 6:4 sextuplet sixteenth run, 7:4
        val bar3 = (0 until 6).map { b.beat(b.f(it.toLong(), 24), b.dur(16, 0, TupletRatio(6, 4)), b.note(g, 4, it)) } + (0 until 7).map { b.beat(b.f(1, 4) + b.f(it.toLong(), 28), b.dur(16, 0, TupletRatio(7, 4)), b.note(g, 5, it)) } +
            listOf(b.beat(b.f(1, 2), b.dur(2), b.note(g, 0, 0)))
        return b.project("Tuplets", m, listOf(b.track("Gtr", g, m, listOf(listOf(bar1), listOf(bar2), listOf(bar3)))))
    }

    fun ties(): Project {
        val b = B("ti"); val g = gtr6; val m = List(3) { Measure(b.id()) }
        val t1 = b.id(); val t2 = b.id(); val t3 = b.id(); val t4 = b.id()
        val bar1 = listOf(b.beat(Fraction.ZERO, b.dur(2), b.note(g, 2, 2)), b.beat(b.f(1, 2), b.dur(2), b.note(g, 3, 2, links = listOf(NoteLink(LinkKind.TIE, t1))), b.note(g, 4, 3, links = listOf(NoteLink(LinkKind.TIE, t2)))))
        val bar2 = listOf(b.beat(Fraction.ZERO, b.dur(1), b.note(g, 3, 2, id = t1, links = listOf(NoteLink(LinkKind.TIE, t3))), b.note(g, 4, 3, id = t2)))
        val bar3 = listOf(b.beat(Fraction.ZERO, b.dur(4), b.note(g, 3, 2, id = t3)), b.beat(b.f(1, 4), b.dur(4), b.note(g, 1, 5, Articulation.Mark(NoteMark.HAMMER_ON))), b.beat(b.f(1, 2), b.dur(4), b.note(g, 1, 7, links = listOf(NoteLink(LinkKind.PULL_OFF, t4)))), b.beat(b.f(3, 4), b.dur(4), b.note(g, 1, 5, id = t4)))
        return b.project("Ties", m, listOf(b.track("Gtr", g, m, listOf(listOf(bar1), listOf(bar2), listOf(bar3)))))
    }

    fun capo(): Project {
        val b = B("ca"); val m = List(1) { Measure(b.id()) }
        val c3 = InstrumentDefinition(tuning = listOf(40, 45, 50, 55, 59, 64), capoByString = List(6) { 3 })
        val partial = InstrumentDefinition(tuning = listOf(40, 45, 50, 55, 59, 64), capoByString = listOf(0, 2, 2, 2, 0, 0))
        val transp = InstrumentDefinition(tuning = listOf(40, 45, 50, 55, 59, 64), transposition = -12)
        val bass5 = InstrumentDefinition(InstrumentKind.BASS, listOf(23, 28, 33, 38, 43), midiProgram = 33)
        val seven = InstrumentDefinition()
        return b.project("Capo", m, listOf(
            b.track("Capo3", c3, m, listOf(listOf(quarters(b, c3, 0 to 0, 2 to 2, 5 to 5, 1 to 12)))),
            b.track("Partial", partial, m, listOf(listOf(quarters(b, partial, 0 to 0, 1 to 0, 2 to 3, 5 to 1)))),
            b.track("Transp", transp, m, listOf(listOf(quarters(b, transp, 0 to 0, 1 to 2, 2 to 3, 5 to 1)))),
            b.track("Bass5", bass5, m, listOf(listOf(quarters(b, bass5, 0 to 0, 0 to 5, 4 to 7, 2 to 12)))),
            b.track("Seven", seven, m, listOf(listOf(quarters(b, seven, 0 to 0, 6 to 24, 3 to 17, 0 to 10))))))
    }

    fun voices(): Project {
        val b = B("vo"); val g = gtr6; val m = List(2) { Measure(b.id()) }
        val v0 = listOf(b.beat(Fraction.ZERO, b.dur(2), b.note(g, 4, 3)), b.beat(b.f(3, 4), b.dur(4), b.note(g, 4, 5)))
        val v1 = listOf(b.beat(b.f(1, 8), b.dur(8), b.note(g, 0, 3)), b.beat(b.f(1, 2), b.dur(4), b.note(g, 1, 2)))
        val v2 = listOf(b.beat(b.f(3, 16), b.dur(16), b.note(g, 2, 1)))
        val bar2v0 = listOf(b.beat(b.f(1, 4), b.dur(4), b.note(g, 5, 0)))
        return b.project("Voices", m, listOf(b.track("Gtr", g, m, listOf(listOf(v0, v1, v2), listOf(bar2v0)))))
    }

    fun repeats(): Project {
        val b = B("rp"); val g = gtr6
        val m0 = Measure(b.id(), repeat = RepeatMark(starts = true)); val m1 = Measure(b.id(), repeat = RepeatMark(endCount = 3, alternateEndings = setOf(1, 2)))
        val m2 = Measure(b.id(), repeat = RepeatMark(alternateEndings = setOf(3)), navigation = listOf(NavigationMark(NavigationKind.SEGNO)))
        val m3 = Measure(b.id(), navigation = listOf(NavigationMark(NavigationKind.TO_CODA)))
        val m4 = Measure(b.id(), navigation = listOf(NavigationMark(NavigationKind.DAL_SEGNO)))
        val m5 = Measure(b.id(), navigation = listOf(NavigationMark(NavigationKind.CODA)))
        val m = listOf(m0, m1, m2, m3, m4, m5)
        val bars = m.indices.map { i -> listOf(quarters(b, g, 0 to i, 1 to i, 2 to i, 3 to i)) }
        return b.project("Repeats", m, listOf(b.track("Gtr", g, m, bars)), sections = listOf(Section(b.id(), m0.id, "Intro"), Section(b.id(), m3.id, "Verse")))
    }

    fun highFrets(): Project {
        val b = B("hf"); val g = gtr6; val m = List(2) { Measure(b.id()) }
        val chord = (0 until 6).map { s -> b.note(g, s, if (s % 2 == 0) 12 + s else 3) }
        val bar1 = listOf(b.beat(Fraction.ZERO, b.dur(16), *chord.toTypedArray())) + (1 until 16).map { k -> b.beat(b.f(k.toLong(), 16), b.dur(16), b.note(g, k % 6, if (k % 3 == 0) 22 else k)) }
        val bar2 = listOf(b.beat(Fraction.ZERO, b.dur(32), b.note(g, 0, 24)), b.beat(b.f(1, 32), b.dur(32), b.note(g, 0, 10)), b.beat(b.f(1, 16), b.dur(8, 1), b.note(g, 5, 0)), b.beat(b.f(1, 4), b.dur(4, 2), b.note(g, 1, 19)), b.beat(b.f(11, 16), b.dur(16), b.note(g, 2, 2)), b.beat(b.f(3, 4), b.dur(4), b.note(g, 3, 12, Articulation.Harmonic(HarmonicKind.NATURAL))))
        return b.project("High frets", m, listOf(b.track("Gtr", g, m, listOf(listOf(bar1), listOf(bar2)))))
    }

    fun names(): Project {
        val b = B("nm"); val g = gtr6; val m = List(1) { Measure(b.id()) }
        val p = b.project("Tïtle <&> \"q\" 'a' \u0001ctl 🎸 rtl א", m, listOf(b.track("Name <&\"> é中", g, m, listOf(listOf(quarters(b, g, 0 to 0)))), b.track("CR\r\nLF\ttab", g, m, listOf(listOf(quarters(b, g, 1 to 1))))),
            text = listOf(ScoreText(b.id(), ScorePosition(m[0].id, Fraction.of(1, 4)), "text ]]> <x/> &amp;")))
        return p.copy(metadata = p.metadata.copy(artist = "Art]]>ist", composer = "Cömposer", copyright = "© 2026", notes = "multi\nline"))
    }

    fun nonchars(): Project {
        val b = B("nc"); val g = gtr6; val m = List(1) { Measure(b.id()) }
        return b.project("Bad ￿ char ￾", m, listOf(b.track("Trk ￿", g, m, listOf(listOf(quarters(b, g, 0 to 0))))))
    }
    fun loneSurrogate(): Project {
        val b = B("ls"); val g = gtr6; val m = List(1) { Measure(b.id()) }
        return b.project("Lone \uD800 surrogate", m, listOf(b.track("Trk \uDC00", g, m, listOf(listOf(quarters(b, g, 0 to 0))))))
    }

    fun keys(): Project {
        val b = B("ky")
        val fifths = listOf(-7, -3, 0, 2, 5, 7)
        val m = fifths.mapIndexed { i, f -> Measure(b.id(), key = KeySignature(f, i % 2 == 1)) }
        val piano = InstrumentDefinition(InstrumentKind.PIANO, emptyList(), emptyList(), midiProgram = 0)
        val bars = m.indices.map { i -> listOf((0 until 4).map { k -> b.beat(b.f(k.toLong(), 4), b.dur(4), b.pitched(21 + i * 15 + k * 3, if (k == 3) 50 else 0)) }) }
        return b.project("Keys", m, listOf(b.track("Piano", piano, m, bars)))
    }

    fun graces(): Project {
        val b = B("gr"); val g = gtr6; val m = List(1) { Measure(b.id()) }
        val bar = listOf(b.beat(Fraction.ZERO, b.dur(4), b.note(g, 2, 7, Articulation.Grace(Pitch(g.pitch(2, 5)), fret = 5, string = 2))),
            b.beat(b.f(1, 4), b.dur(4), b.note(g, 2, 7, Articulation.Grace(Pitch(g.pitch(2, 9)), beforeBeat = false))),
            b.beat(b.f(1, 2), b.dur(2), b.note(g, 1, 3, Articulation.Grace(Pitch(g.pitch(1, 1)))), b.note(g, 2, 2, Articulation.Grace(Pitch(g.pitch(2, 0))))))
        return b.project("Graces", m, listOf(b.track("Gtr", g, m, listOf(listOf(bar)))))
    }

    fun manyTracks(): Project {
        val b = B("mt"); val m = List(1) { Measure(b.id()) }
        val tracks = (0 until 18).map { i -> b.track("T$i", gtr6, m, listOf(listOf(quarters(b, gtr6, (i % 6) to i, (i % 6) to (i + 1))))) }
        return b.project("Many", m, tracks)
    }

    fun dense(): Project {
        val b = B("dn"); val g = gtr6; val m = listOf(Measure(b.id(), TimeSignature(2, 4)))
        val beats = ArrayList<Beat>()
        var o = Fraction.ZERO
        for (k in 0 until 16) { beats += b.beat(o, b.dur(64), b.note(g, k % 6, k)); o += Fraction.of(1, 64) }
        beats += b.beat(o, b.dur(128, 1), b.note(g, 0, 1)); o += WrittenDuration(128, 1).length()
        beats += b.beat(o, b.dur(256), b.note(g, 0, 2)); o += WrittenDuration(256).length()
        // fill remaining with 1/8 rest gap left implicit (gap spelling)
        beats += b.beat(Fraction.of(3, 8), b.dur(8), b.note(g, 1, 1))
        return b.project("Dense", m, listOf(b.track("Gtr", g, m, listOf(listOf(beats)))))
    }

    fun strumDyn(): Project {
        val b = B("sd"); val g = gtr6; val m = List(1) { Measure(b.id()) }
        val chord = { (0 until 6).map { s -> b.note(g, s, 2) }.toTypedArray() }
        val bar = listOf(b.beat(Fraction.ZERO, b.dur(4), *chord(), strum = Strum(StrumKind.STRUM, StrokeDirection.DOWN)),
            b.beat(b.f(1, 4), b.dur(4), b.note(g, 1, 3, Articulation.Dynamic(DynamicLevel.FF), Articulation.Mark(NoteMark.ACCENT), Articulation.Mark(NoteMark.STACCATO))),
            b.beat(b.f(1, 2), b.dur(4), b.note(g, 1, 3, Articulation.TremoloPicking(WrittenDuration(16)), Articulation.Mark(NoteMark.PALM_MUTE))),
            b.beat(b.f(3, 4), b.dur(4), b.note(g, 1, 3, Articulation.Trill(5), Articulation.Mark(NoteMark.DEAD))))
        return b.project("StrumDyn", m, listOf(b.track("Gtr", g, m, listOf(listOf(bar)))))
    }


    fun swing16(): Project {
        val b = B("sw"); val g = gtr6; val m = listOf(Measure(b.id(), feel = RhythmicFeel.SWING_EIGHTH))
        val bar = (0 until 4).map { b.beat(b.f(it.toLong(), 16), b.dur(16), b.note(g, 2, it)) } + listOf(b.beat(b.f(1, 4), b.dur(4), b.note(g, 2, 5, Articulation.Trill(7))),
            b.beat(b.f(1, 2), b.dur(4), b.note(g, 2, 5, Articulation.TremoloPicking(WrittenDuration(32)))), b.beat(b.f(3, 4), b.dur(4), b.note(g, 2, 7, Articulation.Grace(Pitch(g.pitch(2, 5))))))
        return b.project("Swing16", m, listOf(b.track("Gtr", g, m, listOf(listOf(bar)))))
    }
    fun fermataTrill(): Project {
        val b = B("ft"); val g = gtr6; val m = listOf(Measure(b.id()))
        val bar = listOf(b.beat(Fraction.ZERO, b.dur(2), b.note(g, 2, 5, Articulation.Trill(7)), fermata = Fermata()), b.beat(b.f(1, 2), b.dur(2), b.note(g, 2, 5, Articulation.TremoloPicking(WrittenDuration(16))), fermata = Fermata(FermataKind.LONG, 2.0)))
        return b.project("FermataTrill", m, listOf(b.track("Gtr", g, m, listOf(listOf(bar)))))
    }
    fun unison3(): Project {
        val b = B("un"); val g = gtr6; val m = listOf(Measure(b.id()))
        val piano = InstrumentDefinition(InstrumentKind.PIANO, emptyList(), emptyList(), midiProgram = 0)
        // three voices with the same pitch at the same time on one channel
        val v = (0 until 3).map { k -> listOf(b.beat(Fraction.ZERO, b.dur(2), b.pitched(60)), b.beat(b.f(1, 2), b.dur(2), b.pitched(64))) }
        return b.project("Unison3", m, listOf(b.track("Piano", piano, m, listOf(v))))
    }
    fun unisonLong(): Project {
        val b = B("ul"); val m = listOf(Measure(b.id()))
        val piano = InstrumentDefinition(InstrumentKind.PIANO, emptyList(), emptyList(), midiProgram = 0)
        // long note + same-start short note (voice 1) + later note (voice 2) inside the long one
        val v0 = listOf(b.beat(Fraction.ZERO, b.dur(1), b.pitched(60)))
        val v1 = listOf(b.beat(Fraction.ZERO, b.dur(4), b.pitched(60)))
        val v2 = listOf(b.beat(b.f(1, 2), b.dur(4), b.pitched(60)))
        return b.project("UnisonLong", m, listOf(b.track("Piano", piano, m, listOf(listOf(v0, v1, v2)))))
    }
    fun gap64(): Project {
        val b = B("gp"); val g = gtr6; val m = listOf(Measure(b.id()))
        // divisions = 8 (1/32 grid); the rest 1/32..5/32 is spelled as a double-dotted 16th + 64th
        val bar = listOf(b.beat(Fraction.ZERO, b.dur(32), b.note(g, 0, 0)), b.beat(b.f(5, 32), b.dur(32), b.note(g, 0, 1)))
        return b.project("Gap64", m, listOf(b.track("Gtr", g, m, listOf(listOf(bar)))))
    }

    fun pianoGrace(): Project {
        val b = B("pg"); val m = listOf(Measure(b.id()))
        val piano = InstrumentDefinition(InstrumentKind.PIANO, emptyList(), emptyList(), midiProgram = 0)
        val bar = listOf(b.beat(Fraction.ZERO, b.dur(2), b.pitched(64, 0, Articulation.Grace(Pitch(62)))), b.beat(b.f(1, 2), b.dur(2), b.pitched(67)))
        return b.project("PianoGrace", m, listOf(b.track("Piano", piano, m, listOf(listOf(bar)))))
    }
    fun rasgShort(): Project {
        val b = B("rs"); val g = gtr6; val m = listOf(Measure(b.id()))
        val bar = listOf(b.beat(Fraction.ZERO, b.dur(32), b.note(g, 0, 0), b.note(g, 4, 1), strum = Strum(StrumKind.RASGUEADO, StrokeDirection.UP, Fraction.of(1, 32))),
            b.beat(b.f(1, 32), b.dur(32), b.note(g, 0, 3), b.note(g, 4, 3), strum = Strum(StrumKind.STRUM, StrokeDirection.DOWN, Fraction.of(1, 16))))
        return b.project("RasgShort", m, listOf(b.track("Gtr", g, m, listOf(listOf(bar)))), tempo = listOf(TempoChange(ScorePosition(m[0].id), 140.0)))
    }
    fun ctrlTitle(): Project {
        val b = B("ct"); val g = gtr6; val m = listOf(Measure(b.id()))
        return b.project("Riff\u0001A", m, listOf(b.track("Gtr\u0007", g, m, listOf(listOf(quarters(b, g, 0 to 0))))))
    }
    fun capoMajority(): Project {
        val b = B("cm"); val m = listOf(Measure(b.id()))
        val inst = InstrumentDefinition(tuning = listOf(40, 45, 50, 55, 59, 64), capoByString = listOf(2, 2, 0, 2, 2, 2))
        return b.project("CapoMajority", m, listOf(b.track("Gtr", inst, m, listOf(listOf(quarters(b, inst, 2 to 0, 2 to 2, 1 to 0, 3 to 0))))))
    }

    fun projects(): List<Pair<String, Project>> = listOf("hm-pianograce" to pianoGrace(), "hm-rasgshort" to rasgShort(), "hm-ctrltitle" to ctrlTitle(), "hm-capomajority" to capoMajority(), "hm-swing16" to swing16(), "hm-fermatatrill" to fermataTrill(), "hm-unison3" to unison3(), "hm-unisonlong" to unisonLong(), "hm-gap64" to gap64(), "hm-bends" to bends(), "hm-tempo" to tempo(), "hm-drums" to drums(), "hm-tuplets" to tuplets(), "hm-ties" to ties(),
        "hm-capo" to capo(), "hm-voices" to voices(), "hm-repeats" to repeats(), "hm-highfrets" to highFrets(), "hm-names" to names(), "hm-nonchars" to nonchars(),
        "hm-lonesurrogate" to loneSurrogate(), "hm-keys" to keys(), "hm-graces" to graces(), "hm-manytracks" to manyTracks(), "hm-dense" to dense(), "hm-strumdyn" to strumDyn())
}
