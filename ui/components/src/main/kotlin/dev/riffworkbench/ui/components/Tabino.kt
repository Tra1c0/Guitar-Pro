@file:OptIn(ExperimentalTextApi::class)

package dev.riffworkbench.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Design tokens of the "Tabino Mobile" V2 design: dark ground, rounded surfaces, one coral accent and one pastel
 * colour per string. Fonts are bundled (SIL OFL 1.1, see third_party/), so the look does not depend on the device.
 */
object Tabino {
    val Ground = Color(0xFF16131C)
    val Surface = Color(0xFF221E2A)
    val Raised = Color(0xFF2E2938)
    val Line = Color(0xFF463F52)
    val Dashed = Color(0xFF5A5266)
    val Text = Color(0xFFF4F0FA)
    val Muted = Color(0xFFA39BB3)
    val Faint = Color(0xFF8C84A0)
    val Ink = Color(0xFF1A1620)
    val Coral = Color(0xFFFF6B5E)
    val Lavender = Color(0xFFC9B8FF)
    val Sky = Color(0xFF8FD3FF)
    val Lime = Color(0xFFC5F07A)
    val Sun = Color(0xFFFFD86B)
    val Mint = Color(0xFF7FE6C5)
    val Pink = Color(0xFFFF9EC7)
    val Peach = Color(0xFFFFB38A)
    val Periwinkle = Color(0xFFB8C4FF)

    /** String colours from the highest string down (e, B, G, D, A, low strings…). */
    val StringColors = listOf(Lavender, Sky, Lime, Sun, Mint, Pink, Peach, Periwinkle)
    fun stringColor(stringFromLowest: Int, stringCount: Int): Color =
        StringColors[((stringCount - 1 - stringFromLowest).coerceAtLeast(0)) % StringColors.size]
    /** Accent cycle for tiles and project art. */
    val Accents = listOf(Coral, Lavender, Lime, Sky, Sun, Mint, Pink)

    private fun variable(id: Int, weight: Int) = Font(id, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
    val Display = FontFamily(variable(R.font.bricolage_grotesque, 500), variable(R.font.bricolage_grotesque, 600), variable(R.font.bricolage_grotesque, 700), variable(R.font.bricolage_grotesque, 800))
    val Body = FontFamily(variable(R.font.dm_sans, 400), variable(R.font.dm_sans, 500), variable(R.font.dm_sans, 600), variable(R.font.dm_sans, 700), variable(R.font.dm_sans, 800))
}

internal val TabinoDark = darkColorScheme(
    primary = Tabino.Coral, onPrimary = Tabino.Ink, primaryContainer = Tabino.Coral, onPrimaryContainer = Tabino.Ink,
    secondary = Tabino.Lavender, onSecondary = Tabino.Ink, secondaryContainer = Tabino.Text, onSecondaryContainer = Tabino.Ink,
    tertiary = Tabino.Lime, onTertiary = Tabino.Ink, tertiaryContainer = Tabino.Lime, onTertiaryContainer = Tabino.Ink,
    background = Tabino.Ground, onBackground = Tabino.Text, surface = Tabino.Surface, onSurface = Tabino.Text,
    surfaceVariant = Tabino.Raised, onSurfaceVariant = Tabino.Muted, outline = Tabino.Line, outlineVariant = Color(0xFF3A3446),
    surfaceContainerLowest = Tabino.Ground, surfaceContainerLow = Tabino.Surface, surfaceContainer = Tabino.Surface,
    surfaceContainerHigh = Tabino.Raised, surfaceContainerHighest = Color(0xFF3A3446),
    error = Color(0xFFFF8F85), onError = Tabino.Ink, errorContainer = Color(0xFF5A2A2A), onErrorContainer = Color(0xFFFFDAD5),
    inverseSurface = Tabino.Text, inverseOnSurface = Tabino.Ink, inversePrimary = Color(0xFFB8382D), scrim = Color(0xCC000000),
)
internal val TabinoLight = lightColorScheme(
    primary = Color(0xFFD9443A), onPrimary = Color.White, primaryContainer = Tabino.Coral, onPrimaryContainer = Tabino.Ink,
    secondary = Color(0xFF6B54C8), onSecondary = Color.White, secondaryContainer = Tabino.Ink, onSecondaryContainer = Color.White,
    tertiary = Color(0xFF4E6B12), tertiaryContainer = Tabino.Lime, onTertiaryContainer = Tabino.Ink,
    background = Color(0xFFF6F3FA), onBackground = Tabino.Ink, surface = Color.White, onSurface = Tabino.Ink,
    surfaceVariant = Color(0xFFECE7F3), onSurfaceVariant = Color(0xFF5E566E), outline = Color(0xFFCFC7DB), outlineVariant = Color(0xFFE1DAEA),
    surfaceContainerLowest = Color(0xFFF6F3FA), surfaceContainerLow = Color.White, surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFECE7F3), surfaceContainerHighest = Color(0xFFE1DAEA),
)
internal val TabinoShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(14.dp), medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp),
)
internal val TabinoType: Typography = Typography().let { t ->
    fun TextStyle.display(weight: Int = 700) = copy(fontFamily = Tabino.Display, fontWeight = FontWeight(weight), letterSpacing = (-0.02).em)
    fun TextStyle.body(weight: Int? = null) = copy(fontFamily = Tabino.Body, fontWeight = weight?.let { FontWeight(it) } ?: fontWeight)
    Typography(
        displayLarge = t.displayLarge.display(800), displayMedium = t.displayMedium.display(800), displaySmall = t.displaySmall.display(),
        headlineLarge = t.headlineLarge.display(), headlineMedium = t.headlineMedium.display(), headlineSmall = t.headlineSmall.display(),
        titleLarge = t.titleLarge.display(), titleMedium = t.titleMedium.body(700), titleSmall = t.titleSmall.body(700),
        bodyLarge = t.bodyLarge.body(), bodyMedium = t.bodyMedium.body(), bodySmall = t.bodySmall.body(500),
        labelLarge = t.labelLarge.body(700), labelMedium = t.labelMedium.body(700), labelSmall = t.labelSmall.body(700),
    )
}

/** Stroke icons drawn from the design's 24-unit SVG paths; [fills] are solid parts (note heads, play triangles). */
object TabinoIcons {
    private fun icon(name: String, strokes: List<String> = emptyList(), fills: List<String> = emptyList(), width: Float = 2f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            strokes.forEach { addPath(PathParser().parsePathString(it).toNodes(), stroke = SolidColor(Color.Black), strokeLineWidth = width, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) }
            fills.forEach { addPath(PathParser().parsePathString(it).toNodes(), fill = SolidColor(Color.Black)) }
        }.build()
    private const val OVAL = "M5.5 17a4.3 3.1 0 1 0 8.6 0a4.3 3.1 0 1 0 -8.6 0z"
    val Back = icon("back", listOf("M15 5l-7 7 7 7"))
    val Undo = icon("undo", listOf("M1 4v6h6M3.51 15a9 9 0 1 0 2.13-9.36L1 10"))
    val Redo = icon("redo", listOf("M23 4v6h-6M20.49 15a9 9 0 1 1-2.13-9.36L23 10"))
    val Library = icon("library", listOf("M4 6h16M4 12h16M4 18h10"))
    val Pen = icon("pen", listOf("M4 20h4L19 9l-4-4L4 16z"))
    val Metronome = icon("metronome", listOf("M8 21h8l-2.5-16h-3zM12 17l4-9"))
    val More = icon("more", fills = listOf("M5 10a2 2 0 1 0 0 4a2 2 0 1 0 0-4zM12 10a2 2 0 1 0 0 4a2 2 0 1 0 0-4zM19 10a2 2 0 1 0 0 4a2 2 0 1 0 0-4z"))
    val Play = icon("play", fills = listOf("M7 4l13 8-13 8z"))
    val Pause = icon("pause", fills = listOf("M6 4h4v16H6zM14 4h4v16h-4z"))
    val Stop = icon("stop", fills = listOf("M6 6h12v12H6z"))
    val Plus = icon("plus", listOf("M12 5v14M5 12h14"), width = 2.5f)
    val Minus = icon("minus", listOf("M5 12h14"), width = 2.5f)
    val Search = icon("search", listOf("M18 11a7 7 0 1 1-14 0a7 7 0 0 1 14 0zM20 20l-4-4"))
    val Settings = icon("settings", listOf("M12 15a3 3 0 1 0 0-6a3 3 0 0 0 0 6z", "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"))
    val Open = icon("open", listOf("M4 20h16M12 4v12M7 9l5-5 5 5"))
    val Loop = icon("loop", listOf("M17 1l4 4-4 4M3 11V9a4 4 0 0 1 4-4h14M7 23l-4-4 4-4M21 13v2a4 4 0 0 1-4 4H3"))
    val Layers = icon("layers", listOf("M12 3l9 5-9 5-9-5zM3 13l9 5 9-5"))
    val Left = icon("left", listOf("M15 6l-6 6 6 6"))
    val Right = icon("right", listOf("M9 6l6 6-6 6"))
    val Up = icon("up", listOf("M12 19V5M6 11l6-6 6 6"))
    val Down = icon("down", listOf("M12 5v14M6 13l6 6 6-6"))
    val Erase = icon("erase", listOf("M21 5H9l-6 7 6 7h12zM18 9l-6 6M12 9l6 6"))
    val Chord = icon("chord", listOf("M6 4v16M12 4v16M18 4v16M4 8h16M4 14h16"))
    val Trash = icon("trash", listOf("M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"))
    val Copy = icon("copy", listOf("M9 9h11v11H9zM5 15V4h11"))
    val Close = icon("close", listOf("M6 6l12 12M18 6L6 18"))
    val Back5 = icon("prevbar", fills = listOf("M6 5h2.5v14H6zM20 5v14L9.5 12z"))
    val Next5 = icon("nextbar", fills = listOf("M15.5 5H18v14h-2.5zM4 5v14l10.5-7z"))
    // Techniques
    val Hammer = icon("hammer", listOf("M4 17C4 7 20 7 20 17"))
    val Slide = icon("slide", listOf("M5 18L19 6"))
    val Bend = icon("bend", listOf("M6 20V14C6 9 10 6 18 6M14 2l4 4-4 4"))
    val Vibrato = icon("vibrato", listOf("M2 12c1.5-5 3-5 4.5 0s3 5 4.5 0 3-5 4.5 0 3 5 4.5 0"))
    val Harmonic = icon("harmonic", listOf("M12 4l8 8-8 8-8-8z"))
    val PalmMute = icon("pm", listOf("M3 9h3M9 9h3M15 9h4v7"))
    val Sparkle = icon("sparkle", listOf("M12 3v4M12 17v4M3 12h4M17 12h4M6 6l2.5 2.5M15.5 15.5L18 18M6 18l2.5-2.5M15.5 8.5L18 6"))
    // Durations
    val Whole = icon("whole", listOf("M6.5 13a5.5 3.8 0 1 0 11 0a5.5 3.8 0 1 0 -11 0z"), width = 1.8f)
    val Half = icon("half", listOf("$OVAL M14.1 17V3"), width = 1.8f)
    val Quarter = icon("quarter", listOf("M14.1 17V3"), listOf(OVAL), width = 1.8f)
    val Eighth = icon("eighth", listOf("M14.1 17V3M14.1 3c0 4 5 5 5 9"), listOf(OVAL), width = 1.8f)
    val Sixteenth = icon("sixteenth", listOf("M14.1 17V3M14.1 3c0 3 5 4 5 7M14.1 8c0 3 5 4 5 7"), listOf(OVAL), width = 1.8f)
    val ThirtySecond = icon("thirtysecond", listOf("M14.1 17V3M14.1 3c0 2.5 4.5 3.5 4.5 6M14.1 7c0 2.5 4.5 3.5 4.5 6M14.1 11c0 2.5 4.5 3.5 4.5 6"), listOf(OVAL), width = 1.6f)
    val Dot = icon("dot", fills = listOf("M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z"))
    val Rest = icon("rest", listOf("M10 8.5C12.5 9 15 7.5 17 5L11.5 19"), listOf("M7.5 7a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0 -4.4 0z"), width = 1.8f)
}

/** 44 dp round icon button on a raised surface (top bars, sheets). */
@Composable fun RoundIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                                container: Color = MaterialTheme.colorScheme.surface, content: Color = MaterialTheme.colorScheme.onSurface, size: Dp = 44.dp) {
    Surface(onClick = onClick, enabled = enabled, shape = CircleShape, color = container, contentColor = content, modifier = modifier.size(size).semantics { contentDescription = description }) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(20.dp), tint = if (enabled) content else content.copy(alpha = 0.35f)) }
    }
}

/** Two-line display title: bright first line, muted second line (the design's header). */
@Composable fun BigTitle(title: String, subtitle: String?, modifier: Modifier = Modifier, size: Int = 26) {
    Column(modifier) {
        Text(title, fontFamily = Tabino.Display, fontWeight = FontWeight.Bold, fontSize = size.sp, lineHeight = (size + 2).sp, letterSpacing = (-0.02).em,
            color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        subtitle?.let { Text(it, fontFamily = Tabino.Display, fontWeight = FontWeight.Bold, fontSize = size.sp, lineHeight = (size + 2).sp, letterSpacing = (-0.02).em,
            color = Tabino.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/**
 * A square-ish choice tile (duration, technique, fret): white when selected, raised surface otherwise,
 * or [accent] for accent-coloured selections such as the fret pad.
 */
@Composable fun ChoiceTile(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, accent: Color? = null,
                           description: String = label, enabled: Boolean = true, big: Boolean = false, idle: Color = MaterialTheme.colorScheme.surface) {
    val bg = when { selected && accent != null -> accent; selected -> MaterialTheme.colorScheme.secondaryContainer; else -> idle }
    val fg = if (selected) MaterialTheme.colorScheme.onSecondaryContainer.takeIf { accent == null } ?: Tabino.Ink else MaterialTheme.colorScheme.onSurface
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(16.dp), color = bg, contentColor = fg,
        modifier = modifier.semantics { contentDescription = description }) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = if (enabled) fg else fg.copy(alpha = 0.35f))
            if (icon == null || label.isNotEmpty()) Text(label, fontFamily = if (big) Tabino.Display else Tabino.Body, fontWeight = FontWeight.Bold,
                fontSize = if (big) 19.sp else 10.sp, maxLines = 1, color = if (enabled) fg else fg.copy(alpha = 0.35f))
        }
    }
}

data class PillItem(val icon: ImageVector, val label: String, val selected: Boolean, val onClick: () -> Unit, val tag: String? = null)

/**
 * The floating bottom navigation: a raised pill with round items and one large coral action in the middle.
 */
@Composable fun PillNavBar(items: List<PillItem>, centerIcon: ImageVector, centerLabel: String, onCenter: () -> Unit, modifier: Modifier = Modifier, centerTag: String? = null) {
    val half = items.size / 2
    Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier.fillMaxWidth().height(64.dp)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            items.take(half).forEach { PillNavItem(it) }
            Surface(onClick = onCenter, shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(56.dp).shadow(10.dp, CircleShape, ambientColor = MaterialTheme.colorScheme.primary, spotColor = MaterialTheme.colorScheme.primary)
                    .semantics { contentDescription = centerLabel }.let { m -> centerTag?.let { m.testTag(it) } ?: m }) {
                Box(contentAlignment = Alignment.Center) { Icon(centerIcon, null, Modifier.size(24.dp)) }
            }
            items.drop(half).forEach { PillNavItem(it) }
        }
    }
}

@Composable private fun PillNavItem(item: PillItem) {
    Surface(onClick = item.onClick, shape = CircleShape, color = if (item.selected) MaterialTheme.colorScheme.outline else Color.Transparent,
        contentColor = if (item.selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(44.dp).semantics { contentDescription = item.label }.let { m -> item.tag?.let { m.testTag(it) } ?: m }) {
        Box(contentAlignment = Alignment.Center) { Icon(item.icon, null, Modifier.size(22.dp)) }
    }
}


/** Rounded card on the surface colour, the design's main container. */
@Composable fun TabinoCard(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surface, radius: Dp = 28.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(radius), color = color, modifier = modifier) { Column(content = content) }
}

/** Small coloured dot used in chips (track colour, string colour). */
@Composable fun ColorDot(color: Color, size: Dp = 10.dp, square: Boolean = false) {
    Box(Modifier.size(size).background(color, if (square) RoundedCornerShape(3.dp) else CircleShape))
}

/** Outlined pill showing context information (selection, hints). */
@Composable fun InfoPill(text: String, dot: Color?, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(20.dp), color = Color.Transparent, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), modifier = modifier.height(40.dp)) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            dot?.let { ColorDot(it, square = true) }
            Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
