package com.neutrino.docs.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FilterListOff
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.neutrino.docs.Route
import com.neutrino.docs.Tab
import com.neutrino.docs.core.model.DocItem
import com.neutrino.docs.core.search.SearchFilters
import com.neutrino.docs.core.search.SearchResult
import com.neutrino.docs.graph
import com.neutrino.docs.ui.ActionRow
import com.neutrino.docs.ui.ChoiceRow
import com.neutrino.docs.ui.FormSection
import com.neutrino.docs.ui.PageBar
import com.neutrino.docs.ui.SwitchRow
import com.neutrino.docs.ui.relativeTime
import com.neutrino.shared.android.ui.TextInputDialog
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Search: the on-device index over titles, headings, tags and content, plus documents the index has
 * not read matched on their name; filters by owner, folder, date and tag; and saved searches. The
 * index lives here because indexing on the server would mean giving it the decrypted text.
 */
@Composable
fun SearchScreen() {
    val graph = LocalContext.current.graph
    val search = graph.search
    var query by rememberSaveable { mutableStateOf("") }
    var filters by remember { mutableStateOf(SearchFilters.unfiltered) }
    var editingFilters by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    val generation by search.generation.collectAsState()
    val count by search.indexedDocumentCount.collectAsState()
    val progress by search.crawlProgress.collectAsState()
    val saved by search.savedSearches.collectAsState()
    val error by search.lastError.collectAsState()
    val known by graph.drive.allItems.collectAsState()
    val results = remember(query, filters, generation, known) { search.searchEverywhere(query, filters) }

    Column(Modifier.fillMaxSize()) {
        PageBar("Search", null) {
            IconButton(onClick = { editingFilters = true }) { Icon(if (filters.isActive) Icons.Filled.FilterList else Icons.Filled.FilterListOff, "Filters") }
            IconButton(onClick = { naming = true }, enabled = query.isNotBlank() || filters.isActive) { Icon(Icons.Filled.BookmarkAdd, "Save Search") }
        }
        OutlinedTextField(query, { query = it }, placeholder = { Text("Titles, headings, tags and text") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        progress?.let { p ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Indexed ${p.indexed} of ${p.total}" + if (p.failed > 0) " · ${p.failed} couldn't be read" else "", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
                TextButton(onClick = { search.cancelCrawl() }) { Text("Stop") }
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (query.isBlank() && !filters.isActive) {
                item {
                    FormSection("Saved Searches", if (saved.isEmpty()) "Save a search to keep its query and filters together." else null) {
                        saved.forEach { s ->
                            Row(Modifier.fillMaxWidth().clickable {
                                search.markUsed(s.id)
                                query = s.query
                                filters = s.filters
                            }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Bookmark, null, tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(s.name)
                                    Text(s.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { search.deleteSavedSearch(s.id) }) { Icon(Icons.Filled.Delete, "Delete") }
                            }
                        }
                    }
                }
                item {
                    FormSection("Index", "Searching inside documents needs them decrypted on this phone, so the index is built here rather than on the server. " +
                        "Indexing everything downloads and decrypts every document you can read.") {
                        Text("$count documents indexed", Modifier.padding(16.dp))
                        ActionRow(if (progress != null) "Indexing…" else "Index Everything", enabled = progress == null) { search.indexEverything() }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 28.dp)) }
                }
            } else {
                if (filters.tags.isNotEmpty() || filters.folderIds != null) item {
                    Text("Filters are on. Documents that haven't been indexed yet can only be matched by name, so they're left out while a tag or folder filter is set.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                }
                if (results.isEmpty()) item { Text("No documents match.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp)) }
                items(results, key = { it.documentId }) { result -> ResultRow(result) { open(graph, result) } }
            }
        }
    }

    if (editingFilters) FiltersDialog(filters, search.allTags, onDismiss = { editingFilters = false }) { filters = it }
    if (naming) TextInputDialog("Save Search", query, "Name", "Save", onDismiss = { naming = false }) {
        naming = false
        search.addSavedSearch(it, query, filters)
    }
}

/** A result opens on the Documents tab, where documents are edited. */
private fun open(graph: com.neutrino.docs.AppGraph, result: SearchResult) {
    val item = graph.drive.item(result.documentId) ?: DocItem(result.documentId, result.title, DocItem.ItemType.FILE, null,
        modifiedAt = Instant.ofEpochMilli(result.modifiedAt), mimeType = DocItem.DOCUMENT_MIME, isShared = result.isShared)
    graph.router.select(Tab.DOCUMENTS)
    graph.router.push(Route.Editor(item), Tab.DOCUMENTS)
}

@Composable
private fun ResultRow(result: SearchResult, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(result.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (result.isShared) Icon(Icons.Filled.People, "Shared with you", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (result.preview.isNotEmpty()) Text(result.preview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val why = if (result.nameOnly) "Name only — not indexed yet" else result.matchedFields.joinToString(", ") { it.name.lowercase() }
        Text("$why · ${relativeTime(Instant.ofEpochMilli(result.modifiedAt))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}

/** Owner, folder (everything beneath it), modified range and tags — a document must carry every selected tag. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FiltersDialog(initial: SearchFilters, tags: List<String>, onDismiss: () -> Unit, onChange: (SearchFilters) -> Unit) {
    val graph = LocalContext.current.graph
    var filters by remember { mutableStateOf(initial) }
    fun set(f: SearchFilters) { filters = f; onChange(f) }
    val folders = graph.drive.allItems.collectAsState().value.filter { it.isFolder }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                PageBar("Filters", onDismiss) { TextButton(onClick = { set(SearchFilters.unfiltered) }) { Text("Clear") } }
                Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                    FormSection("Owner") {
                        ChoiceRow("Owned by", filters.ownership, SearchFilters.Ownership.entries, { it.title }) { set(filters.copy(ownership = it)) }
                    }
                    FormSection("Folder", "Searching a folder includes everything inside it.") {
                        val chosen = filters.folderIds?.let { ids -> folders.firstOrNull { it.id in ids && it.parentId !in ids } }
                        ChoiceRow("Folder", chosen, listOf<DocItem?>(null) + folders, { it?.name ?: "Anywhere" }) { f ->
                            set(filters.copy(folderIds = f?.let { subtree(it.id, folders) }))
                        }
                    }
                    FormSection("Modified") {
                        SwitchRow("In the last 7 days", filters.modifiedAfter != null && filters.modifiedBefore == null) { on ->
                            set(filters.copy(modifiedAfter = if (on) Instant.now().minus(7, ChronoUnit.DAYS).toEpochMilli() else null, modifiedBefore = null))
                        }
                        SwitchRow("Older than 30 days", filters.modifiedBefore != null) { on ->
                            set(filters.copy(modifiedBefore = if (on) Instant.now().minus(30, ChronoUnit.DAYS).toEpochMilli() else null, modifiedAfter = null))
                        }
                    }
                    FormSection("Tags", if (filters.tags.isNotEmpty()) "A document has to carry every selected tag." else null) {
                        if (tags.isEmpty()) Text("No tags yet. Add a tags: line to a document's front matter.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                        FlowRow(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            tags.forEach { tag ->
                                FilterChip(selected = tag in filters.tags, onClick = {
                                    set(filters.copy(tags = if (tag in filters.tags) filters.tags - tag else filters.tags + tag))
                                }, label = { Text("#$tag") })
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun subtree(root: String, folders: List<DocItem>): Set<String> {
    val out = mutableSetOf(root)
    var grew = true
    while (grew) {
        grew = false
        for (f in folders) if (f.parentId in out && out.add(f.id)) grew = true
    }
    return out
}
