package dev.riffworkbench.feature.workspace

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

fun Modifier.semanticsLabel(label: String) = semantics { contentDescription = label }
