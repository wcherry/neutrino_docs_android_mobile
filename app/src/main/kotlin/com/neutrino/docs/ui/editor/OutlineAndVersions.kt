package com.neutrino.docs.ui.editor

import android.graphics.Typeface
import android.util.TypedValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.neutrino.docs.core.document.DocumentBody
import com.neutrino.docs.core.document.DocumentOutline
import com.neutrino.docs.core.model.DocItem
import com.neutrino.docs.core.model.DocVersion
import com.neutrino.docs.graph
import com.neutrino.docs.ui.PageBar
import com.neutrino.docs.ui.formatBytes
import com.neutrino.docs.ui.formatDateTime
import com.neutrino.shared.android.ui.ConfirmDialog
import kotlinx.coroutines.launch

/** Heading navigation, collapsible sections and heading search — read off the text the editor shows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutlineSheet(outline: DocumentOutline, onDismiss: () -> Unit, onSelect: (DocumentOutline.Heading) -> Unit) {
    var query by remember { mutableStateOf("") }
    var collapsed by remember { mutableStateOf(setOf<Int>()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text("Outline", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
            OutlinedTextField(query, { query = it }, placeholder = { Text("Search headings") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp))
            val shown = if (query.isBlank()) outline.visibleHeadings(collapsed) else outline.filtered(query)
            when {
                outline.isEmpty -> Text("This document has no headings.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                shown.isEmpty() -> Text("No headings match “$query”.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
            LazyColumn {
                items(shown, key = { it.id }) { heading ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(heading); onDismiss() }.padding(start = (8 + (heading.level - 1) * 16).dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (heading.childIds.isNotEmpty() && query.isBlank()) {
                            IconButton(onClick = { collapsed = if (heading.id in collapsed) collapsed - heading.id else collapsed + heading.id }, modifier = Modifier.size(32.dp)) {
                                Icon(if (heading.id in collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
                                    if (heading.id in collapsed) "Expand" else "Collapse")
                            }
                        } else Spacer(Modifier.width(32.dp))
                        Text(heading.title.ifBlank { "Untitled heading" }, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = if (heading.level == 1) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium)
                        Text("${heading.line + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * Previous versions with their times; preview one read-only through the same editor; restore it.
 * Autosave does not make versions — only an explicit save does — which the empty state says.
 */
@Composable
fun VersionHistoryDialog(item: DocItem, dek: ByteArray, onDismiss: () -> Unit, onRestored: (DocumentBody) -> Unit) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    var versions by remember { mutableStateOf<List<DocVersion>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var previewing by remember { mutableStateOf<Pair<DocVersion, DocumentBody>?>(null) }
    var restoring by remember { mutableStateOf<DocVersion?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { graph.versions.listVersions(item.id) }.onSuccess { versions = it }.onFailure { error = it.message }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                PageBar("Version History", onDismiss)
                Box(Modifier.weight(1f)) {
                    val list = versions
                    when {
                        error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                        list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        list.isEmpty() -> Text("No previous versions yet. Automatic saves don't create versions — use “Save Version…” to keep one.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
                        else -> LazyColumn {
                            items(list, key = { it.id }) { version ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(version.displayTitle, style = MaterialTheme.typography.bodyLarge)
                                            if (version.isNamed) Icon(Icons.Filled.Bookmark, "Named version", Modifier.padding(start = 4.dp).size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                        }
                                        Text("${formatDateTime(version.createdAt)} · ${formatBytes(version.sizeBytes)}", style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    TextButton(enabled = !busy, onClick = {
                                        busy = true
                                        scope.launch {
                                            runCatching { graph.versions.versionBody(item.id, version.id, dek) }
                                                .onSuccess { previewing = version to it }.onFailure { error = it.message }
                                            busy = false
                                        }
                                    }) { Text("Preview") }
                                    TextButton(enabled = !busy, onClick = { restoring = version }) { Text("Restore") }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                    if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
            }
        }
    }

    previewing?.let { (version, body) ->
        Dialog(onDismissRequest = { previewing = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    PageBar(version.displayTitle, { previewing = null })
                    ReadOnlyDocument(body)
                }
            }
        }
    }

    // The server snapshots what is there now before restoring, so a restore can itself be undone from here.
    restoring?.let { version ->
        ConfirmDialog("Restore ${version.displayTitle}?", "The current content is kept as a version first, so this can be undone.", "Restore",
            onDismiss = { restoring = null }) {
            busy = true
            scope.launch {
                try {
                    graph.versions.restore(version.id, item.id)
                    val body = graph.versions.versionBody(item.id, version.id, dek)
                    onRestored(body)
                    onDismiss()
                } catch (e: Exception) {
                    error = e.message
                }
                busy = false
            }
        }
    }
}

/** A document drawn by the same editor with editing switched off: one projection, rather than a second renderer to keep in step. */
@Composable
fun ReadOnlyDocument(body: DocumentBody) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val text = MaterialTheme.colorScheme.onSurface.toArgb()
    val palette = EditorPalette(text, MaterialTheme.colorScheme.onSurfaceVariant.toArgb(), MaterialTheme.colorScheme.primary.toArgb(), Color(0xFFAF52DE).toArgb(),
        MaterialTheme.colorScheme.surfaceContainerHigh.toArgb(), MaterialTheme.colorScheme.outlineVariant.toArgb(), Color(0x59FFCC00).toArgb(),
        MaterialTheme.colorScheme.surfaceContainerHigh.toArgb(), Color(0x66FFCC00).toArgb(), Color(0xCCFF9500).toArgb())
    val bodyPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, graph.prefs.fontSize.value, context.resources.displayMetrics)
    val controller = remember {
        EditorController(scope, EditorImageStore(scope, { graph.content.attachmentData(it) }, graph.account.http, context.resources.displayMetrics.widthPixels * 2)).also {
            it.isEditable = false
            it.load(body.doc)
        }
    }
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
        DocumentEditText(ctx) { _, _ -> false }.also { view ->
            controller.renderer.style = EditorStyle(density, bodyPx, Typeface.DEFAULT, palette)
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, bodyPx)
            view.setTextColor(text)
            view.showSoftInputOnFocus = false
            controller.attach(view)
            controller.isEditable = false
        }
    })
}
