package dev.riffworkbench.editor

import dev.riffworkbench.model.*

enum class DragPhase { IDLE, ARMED, PREVIEW, COMMITTED, CANCELLED }
data class DragPreview(val phase: DragPhase = DragPhase.IDLE, val source: Selection? = null, val target: Selection? = null)

/** Preview has no reference to repositories, audio, or the command dispatcher. Commit returns one command. */
class GestureTransaction {
    var preview = DragPreview(); private set
    fun arm(source: Selection) { preview = DragPreview(DragPhase.ARMED, source) }
    fun move(target: Selection) { require(preview.phase in listOf(DragPhase.ARMED, DragPhase.PREVIEW)); preview = preview.copy(phase = DragPhase.PREVIEW, target = target) }
    fun cancel() { preview = preview.copy(phase = DragPhase.CANCELLED, target = null) }
    fun secondPointer() = cancel()
    fun commit(command: EditorCommand): Outcome<EditorCommand> {
        if (preview.phase != DragPhase.PREVIEW) return Outcome.Failure(FailureCode.VALIDATION, "Keine bestätigbare Vorschau")
        preview = preview.copy(phase = DragPhase.COMMITTED)
        return Outcome.Success(command)
    }
}
