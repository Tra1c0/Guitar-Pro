package dev.riffworkbench.feature.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.riffworkbench.model.EntityId
import dev.riffworkbench.ui.components.*
import dev.riffworkbench.ui.components.R

@Composable fun LibraryScreen(vm: LibraryViewModel, onOpen: (EntityId) -> Unit, onImport: () -> Unit, onSettings: () -> Unit) {
    val projects by vm.projects.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val issues by vm.issues.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf("") }
    Scaffold { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(vertical = 24.dp)) {
            item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("RIFF / WORKBENCH", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = onSettings) { Text(stringResource(R.string.settings)) }
            } }
            item { SectionHeading(stringResource(R.string.library), stringResource(R.string.app_tagline), "OFFLINE / ${stringResource(R.string.phase1)}") }
            item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { creating = true }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("+ ${stringResource(R.string.new_project)}") }
                OutlinedButton(onClick = onImport, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.open_native)) }
            } }
            if (creating) item { OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(title, onValueChange = { title = it.take(200) }, label = { Text(stringResource(R.string.project_title)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy, onClick = { creating = false; vm.create(title, onOpen); title = "" }) { Text(stringResource(R.string.create)) }
                        TextButton(onClick = { creating = false }) { Text(stringResource(R.string.cancel)) }
                    }
                }
            } }
            error?.let { message -> item { NoticeCard(message, true); TextButton(onClick = { vm.showError(null) }) { Text(stringResource(R.string.dismiss)) } } }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            items(issues) { issue -> NoticeCard(issue.message, true) }
            if (projects.isEmpty()) item { OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("♪", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.empty_library), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.empty_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } }
            items(projects, key = { it.id.value }) { project -> Card(onClick = { onOpen(project.id) }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(20.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(project.title, style = MaterialTheme.typography.titleLarge)
                        Text("${project.tracks} ${stringResource(R.string.track)} · ${stringResource(R.string.revision)} ${project.revision}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("↗", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                }
            } }
            item { Text(stringResource(R.string.available_native), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
