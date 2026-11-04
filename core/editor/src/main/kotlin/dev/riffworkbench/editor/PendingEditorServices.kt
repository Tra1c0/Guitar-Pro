package dev.riffworkbench.editor

import dev.riffworkbench.model.*
import dev.riffworkbench.ports.*

/** C-WORKFLOW: searchable command palette entries; ids are stable and shared with keyboard shortcuts. */
class DefaultCommandCatalog : CommandCatalog {
    val commands = listOf(
        CommandDescriptor("history.undo", "Rückgängig", "Strg+Z", false), CommandDescriptor("history.redo", "Wiederholen", "Strg+Umschalt+Z", false),
        CommandDescriptor("project.save", "Speichern", "Strg+S", false), CommandDescriptor("transport.play", "Wiedergabe / Pause", "Leertaste", false),
        CommandDescriptor("note.enter", "Bund eintragen", "Enter", true), CommandDescriptor("note.rest", "Pause", "R", true), CommandDescriptor("note.delete", "Note löschen", "Entf", true),
        CommandDescriptor("beat.delete", "Beat löschen (nachrücken)", "Strg+Entf", true), CommandDescriptor("beat.insert", "Beat einfügen", "Einfg", true),
        CommandDescriptor("note.tie", "Haltebogen", "L", true), CommandDescriptor("fx.palm_mute", "Palm Mute", "P", true), CommandDescriptor("fx.dead", "Dead Note", "X", true),
        CommandDescriptor("fx.ghost", "Ghost Note", "G", true), CommandDescriptor("fx.accent", "Akzent", ">", true), CommandDescriptor("fx.let_ring", "Let Ring", "I", true),
        CommandDescriptor("fx.staccato", "Staccato", ".", true), CommandDescriptor("fx.hammer", "Hammer-on / Pull-off", "H", true), CommandDescriptor("fx.slide", "Slide", "S", true),
        CommandDescriptor("fx.bend", "Bend (ganzer Ton)", "B", true), CommandDescriptor("fx.vibrato", "Vibrato", "V", true), CommandDescriptor("fx.harmonic", "Flageolett", "N", true),
        CommandDescriptor("fx.pinch", "Pinch Harmonic", null, true), CommandDescriptor("fx.tapping", "Tapping", "T", true), CommandDescriptor("fx.tremolo", "Tremolo Picking", null, true),
        CommandDescriptor("rhythm.triplet", "Triole", "3", true), CommandDescriptor("rhythm.dot", "Punktierung", "D", true),
        CommandDescriptor("measure.insert", "Takt einfügen", "Strg+Einfg", true), CommandDescriptor("measure.delete", "Takt löschen", null, true),
        CommandDescriptor("measure.duplicate", "Takte duplizieren", "Strg+D", true), CommandDescriptor("clipboard.copy", "Takte kopieren", "Strg+C", false),
        CommandDescriptor("clipboard.paste", "Takte einfügen", "Strg+V", true), CommandDescriptor("repeat.open", "Wiederholung Anfang", null, true),
        CommandDescriptor("repeat.close", "Wiederholung Ende", null, true), CommandDescriptor("track.duplicate", "Spur duplizieren", null, true),
        CommandDescriptor("transpose.up", "Halbton höher", "Strg+↑", true), CommandDescriptor("transpose.down", "Halbton tiefer", "Strg+↓", true),
        CommandDescriptor("edit.repeat", "Letzte Aktion wiederholen", "F4", false),
    )
    override fun search(query: String) = commands.filter { query.isBlank() || it.id.contains(query, true) || it.label.contains(query, true) }
}
