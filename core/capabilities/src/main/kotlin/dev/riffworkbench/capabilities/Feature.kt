package dev.riffworkbench.capabilities

enum class Dimension { READ, DISPLAY, EDIT, PLAYBACK, EXPORT }
enum class ImplementationStatus { IMPLEMENTED, SCAFFOLDED, EXPERIMENTAL, LICENSE_BOUND, NOT_APPLICABLE }
enum class Milestone { PHASE1, B, C, D }
enum class Verification { CONTRACT_ONLY, AUTOMATED_PENDING, AUTOMATED, DEVICE_PENDING, DESKTOP_PENDING }
data class Feature(val id: String, val labelDe: String, val labelEn: String, val group: String, val groupDe: String, val route: String, val milestone: Milestone, val contractId: String, val dimensions: Map<Dimension, ImplementationStatus>, val verification: Verification)
