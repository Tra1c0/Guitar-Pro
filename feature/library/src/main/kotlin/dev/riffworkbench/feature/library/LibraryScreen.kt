package dev.riffworkbench.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.riffworkbench.model.EntityId
import dev.riffworkbench.model.ProjectSummary
import dev.riffworkbench.ui.components.*
import dev.riffworkbench.ui.components.R

/** Library after the Tabino V2 design: big title, a colourful "continue" card, quick tiles and the project list. */
@Composable fun LibraryScreen(vm: LibraryViewModel, onOpen: (EntityId) -> Unit, onImport: () -> Unit, onSettings: () -> Unit) {
    val projects by vm.projects.collectAsStateWithLifecycle()
    val recent = projects.maxByOrNull { it.modifiedMillis }
    val error by vm.error.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val issues by vm.issues.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val en = english()
    var confirmDelete by remember { mutableStateOf<ProjectSummary?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf("") }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Logo()
                    Text("RiffWorkbench", fontFamily = Tabino.Display, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, modifier = Modifier.weight(1f))
                    RoundIconButton(TabinoIcons.Open, stringResource(R.string.open_native), onImport, enabled = !busy)
                    RoundIconButton(TabinoIcons.Settings, stringResource(R.string.settings), onSettings)
                } }
                item { BigTitle(stringResource(R.string.library), if (en) "${projects.size} ${if (projects.size == 1) "project" else "projects"} · offline" else "${projects.size} ${if (projects.size == 1) "Projekt" else "Projekte"} · offline", Modifier.padding(horizontal = 8.dp), size = 30) }
                error?.let { message -> item { Banner(message, true) { vm.showError(null) } } }
                notice?.let { message -> item { Banner(message, false, vm::dismissNotice) } }
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) }
                items(issues) { issue -> NoticeCard(issue.message, true) }
                if (creating) item { CreateCard(title, { title = it.take(200) }, busy, onCreate = { creating = false; vm.create(title, onOpen); title = "" }, onCancel = { creating = false }) }
                item { Row(Modifier.fillMaxWidth().height(230.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ContinueCard(recent, onOpen, onNew = { creating = true }, Modifier.weight(1f).fillMaxHeight())
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        QuickTile("+ ${stringResource(R.string.new_project)}", if (en) "E standard guitar, bass, drums" else "E-Standard-Gitarre, Bass, Drums", Tabino.Lime, Modifier.weight(1f).fillMaxWidth().testTag("new-project"), enabled = !busy) { creating = true }
                        QuickTile(stringResource(R.string.open_native), "GP3–8 · MusicXML · MIDI", Tabino.Lavender, Modifier.weight(1f).fillMaxWidth(), enabled = !busy, onClick = onImport)
                    }
                } }
                if (projects.isNotEmpty() || !creating) item {
                    Text(if (projects.isEmpty()) stringResource(R.string.empty_library) else if (en) "All projects" else "Alle Projekte",
                        fontFamily = Tabino.Display, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(start = 8.dp, top = 4.dp))
                }
                if (projects.isEmpty()) item { Text(stringResource(R.string.empty_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp)) }
                // Every project, including the one on the "continue" card: the list is where projects are deleted.
                items(projects, key = { it.id.value }) { project -> ProjectRow(project, projects.indexOf(project), onOpen = { onOpen(project.id) }, onDelete = { confirmDelete = project }) }
                item { Text(if (en) "Opens Guitar Pro 3–8 (.gp3/.gp4/.gp5/.gpx/.gp), MusicXML (.musicxml/.mxl), MIDI and RiffWorkbench projects (.riffproj). The original file is kept with the project." else
                    "Öffnet Guitar Pro 3–8 (.gp3/.gp4/.gp5/.gpx/.gp), MusicXML (.musicxml/.mxl), MIDI und RiffWorkbench-Projekte (.riffproj). Die Originaldatei bleibt im Projekt erhalten.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) }
            }
            PillNavBar(
                items = listOf(
                    PillItem(TabinoIcons.Library, stringResource(R.string.library), true, {}),
                    PillItem(TabinoIcons.Open, stringResource(R.string.open_native), false, onImport),
                    // Without any project the tap would do nothing: say so instead (QA 21).
                    PillItem(TabinoIcons.Pen, if (en) "Open most recent project" else "Letztes Projekt öffnen", false, {
                        projects.maxByOrNull { it.modifiedMillis }?.let { onOpen(it.id) }
                            ?: vm.showNotice(if (en) "No project yet: create one with +" else "Noch kein Projekt – mit + eines anlegen")
                    }),
                    PillItem(TabinoIcons.Settings, stringResource(R.string.settings), false, onSettings, "nav-settings"),
                ),
                centerIcon = TabinoIcons.Plus, centerLabel = stringResource(R.string.new_project), onCenter = { creating = true }, centerTag = "nav-new",
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
            )
        }
    }
    confirmDelete?.let { project -> AlertDialog(onDismissRequest = { confirmDelete = null },
        title = { Text(if (en) "Delete “${project.title}”?" else "„${project.title}“ löschen?") },
        text = { Text(if (en) "The project moves to the app's trash folder." else "Das Projekt wird in den Papierkorb der App verschoben.") },
        confirmButton = { TextButton(onClick = { vm.delete(project.id); confirmDelete = null }) { Text(if (en) "Delete" else "Löschen", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } }) }
}

@Composable private fun Logo() {
    Row(Modifier.size(36.dp).background(Tabino.Coral, RoundedCornerShape(12.dp)).padding(horizontal = 7.dp, vertical = 8.dp).clearAndSetSemantics { },
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        listOf(8, 16, 11, 19).forEach { h -> Box(Modifier.width(4.dp).height(h.dp).background(Tabino.Ink, RoundedCornerShape(2.dp))) }
    }
}

@Composable private fun Banner(text: String, error: Boolean, onDismiss: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = onDismiss) { Icon(TabinoIcons.Close, stringResource(R.string.dismiss), Modifier.size(18.dp)) }
        }
    }
}

@Composable private fun CreateCard(title: String, onTitle: (String) -> Unit, busy: Boolean, onCreate: () -> Unit, onCancel: () -> Unit) {
    TabinoCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(title, onValueChange = onTitle, label = { Text(stringResource(R.string.project_title)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = onCreate, shape = RoundedCornerShape(22.dp), modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.create)) }
                TextButton(onClick = onCancel, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

/** The gradient "continue with" card from the design; without projects it invites to start one. */
@Composable private fun ContinueCard(project: ProjectSummary?, onOpen: (EntityId) -> Unit, onNew: () -> Unit, modifier: Modifier) {
    val en = english()
    Surface(onClick = { if (project != null) onOpen(project.id) else onNew() }, shape = RoundedCornerShape(28.dp), color = Color.Transparent, contentColor = Tabino.Ink, modifier = modifier) {
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Tabino.Coral, Tabino.Pink, Tabino.Lavender, Tabino.Sky))).padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(if (project != null) (if (en) "Continue with" else "Weiter mit") else (if (en) "Start" else "Los geht's"), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF3A1A1A))
                Text(project?.title ?: (if (en) "Your first riff" else "Dein erstes Riff"), fontFamily = Tabino.Display, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 26.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(project?.let { if (en) "${it.tracks} ${if (it.tracks == 1) "track" else "tracks"} · rev. ${it.revision}" else "${it.tracks} ${if (it.tracks == 1) "Spur" else "Spuren"} · Rev. ${it.revision}" } ?: "", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color(0xFF1F2E45), modifier = Modifier.weight(1f))
                Box(Modifier.size(48.dp).background(Tabino.Ink, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(if (project != null) TabinoIcons.Play else TabinoIcons.Plus, null, Modifier.size(22.dp), tint = Tabino.Text)
                }
            }
        }
    }
}

@Composable private fun QuickTile(title: String, subtitle: String, color: Color, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(28.dp), color = color, contentColor = Tabino.Ink, modifier = modifier) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(title, fontFamily = Tabino.Display, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 21.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Tabino.Ink.copy(alpha = 0.7f), maxLines = 2)
        }
    }
}

@Composable private fun ProjectRow(project: ProjectSummary, index: Int, onOpen: () -> Unit, onDelete: () -> Unit) {
    val en = english()
    val color = Tabino.Accents[index % Tabino.Accents.size]
    Surface(onClick = onOpen, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().height(68.dp)) {
        Row(Modifier.padding(start = 10.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.size(48.dp).background(color, RoundedCornerShape(16.dp)).padding(vertical = 12.dp).clearAndSetSemantics { }, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center) {
                listOf(10, 22, 14, 20).forEachIndexed { k, h -> if (k > 0) Spacer(Modifier.width(3.dp)); Box(Modifier.width(5.dp).height(((h + index * 3 + k * 5) % 16 + 8).dp).background(Tabino.Ink, RoundedCornerShape(3.dp))) }
            }
            Column(Modifier.weight(1f)) {
                Text(project.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (en) "${project.tracks} ${if (project.tracks == 1) "track" else "tracks"} · rev. ${project.revision}" else "${project.tracks} ${if (project.tracks == 1) "Spur" else "Spuren"} · Rev. ${project.revision}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete) { Icon(TabinoIcons.Trash, if (en) "Delete ${project.title}" else "${project.title} löschen", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) { Icon(TabinoIcons.Play, null, Modifier.size(16.dp)) }
        }
    }
}
