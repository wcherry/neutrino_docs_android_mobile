package com.neutrino.docs.ui.browser

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neutrino.docs.Route
import com.neutrino.docs.core.model.DocItem
import com.neutrino.docs.core.model.DocSortOrder
import com.neutrino.docs.core.model.DocsSection
import com.neutrino.docs.graph
import com.neutrino.docs.ui.DocsStyle
import com.neutrino.docs.ui.PageBar
import com.neutrino.docs.ui.formatBytes
import com.neutrino.docs.ui.relativeTime
import com.neutrino.docs.ui.settings.BugReport
import com.neutrino.shared.android.ui.ConfirmDialog
import com.neutrino.shared.android.ui.TextInputDialog
import com.neutrino.shared.android.ui.openUrl
import kotlinx.coroutines.launch

/**
 * One screen for every section: Home navigates folders; Recent, Favorites, Shared and Trash are flat.
 * The Documents tab's root offers My Drive, Favorites and Shared as chips, and Trash from its menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(initialSection: DocsSection, folderId: String?, title: String?) {
    val context = LocalContext.current
    val graph = context.graph
    val drive = graph.drive
    val router = graph.router
    val scope = rememberCoroutineScope()
    val isTabRoot = folderId == null && initialSection == DocsSection.HOME
    var section by rememberSaveable { mutableStateOf(initialSection) }

    val all by drive.allItems.collectAsState()
    val recent by drive.recentItems.collectAsState()
    val starred by drive.starredItems.collectAsState()
    val shared by drive.sharedItems.collectAsState()
    val trash by drive.trashItems.collectAsState()
    val loading by drive.isLoading.collectAsState()
    val online by graph.isOnline.collectAsState()
    val offlineDocs by graph.offline.documents.collectAsState()
    val chosenOrder by graph.prefs.sortOrder.collectAsState()
    val order = chosenOrder ?: section.defaultSortOrder

    val items = order.sorted(when (section) {
        DocsSection.HOME -> all.filter { it.parentId == folderId }
        DocsSection.RECENT -> recent
        DocsSection.FAVORITES -> starred
        DocsSection.SHARED -> shared
        DocsSection.TRASH -> trash
    })

    var refreshing by remember { mutableStateOf(false) }
    var creatingDocument by remember { mutableStateOf(false) }
    var creatingFolder by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<DocItem?>(null) }
    var moving by remember { mutableStateOf<DocItem?>(null) }
    var deleting by remember { mutableStateOf<DocItem?>(null) }
    var emptying by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    suspend fun reload() {
        drive.load(section, folderId)
        if (section == DocsSection.RECENT || section == DocsSection.FAVORITES) graph.cacheAutomatically()
    }
    LaunchedEffect(section, folderId) { reload() }

    // A shortcut asked for a new document.
    LaunchedEffect(router.newDocumentRequested) {
        if (router.newDocumentRequested && isTabRoot) {
            router.newDocumentRequested = false
            creatingDocument = true
        }
    }

    Column(Modifier.fillMaxSize()) {
        PageBar(title ?: if (isTabRoot) "Documents" else section.title, if (folderId != null || section == DocsSection.TRASH && !isTabRoot) ({ router.pop() }) else null) {
            SortMenu(order) { graph.prefs.setSortOrder(it) }
            if (section == DocsSection.TRASH) {
                TextButton(onClick = { emptying = true }, enabled = trash.isNotEmpty()) { Text("Empty", color = MaterialTheme.colorScheme.error) }
            }
            if (isTabRoot) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Trash") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; router.push(Route.Trash) })
                        // Not disabled offline: the bug most worth reporting is often the one that took the app off the network.
                        DropdownMenuItem(text = { Text("Report a Bug") }, leadingIcon = { Icon(Icons.Filled.BugReport, null) },
                            onClick = { menu = false; openUrl(context, BugReport.url(graph.account.settings.serverUrl)) })
                    }
                }
            }
        }
        if (isTabRoot) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(DocsSection.HOME to "My Drive", DocsSection.FAVORITES to "Favorites", DocsSection.SHARED to "Shared").forEach { (s, label) ->
                    FilterChip(selected = section == s, onClick = { section = s }, label = { Text(label) })
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    scope.launch {
                        refreshing = true
                        reload()
                        graph.sync.syncNow()
                        refreshing = false
                    }
                },
            ) {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (items.isEmpty() && !loading) item {
                        Text(section.emptyMessage, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(32.dp))
                    }
                    items(items, key = { it.id }) { item ->
                        DocRow(
                            item = item,
                            section = section,
                            isOffline = offlineDocs.any { it.id == item.id },
                            online = online,
                            onOpen = {
                                when {
                                    item.isFolder && section.supportsFolderNavigation -> router.push(Route.Folder(section, item.id, item.displayName))
                                    !item.isFolder && section != DocsSection.TRASH -> router.push(Route.Editor(item))
                                }
                            },
                            onStar = { drive.setStarred(item.id, !item.isStarred) },
                            onRename = { renaming = item },
                            onMove = { moving = item },
                            onDuplicate = {
                                working = true
                                scope.launch {
                                    runCatching { drive.duplicate(item.id) }.onFailure { actionError = it.message }
                                    working = false
                                }
                            },
                            onToggleOffline = {
                                scope.launch {
                                    runCatching {
                                        if (graph.offline.isAvailableOffline(item.id)) graph.offline.remove(item.id) else graph.offline.download(item)
                                    }.onFailure { actionError = it.message }
                                }
                            },
                            onDelete = { if (section == DocsSection.TRASH) deleting = item else drive.delete(item.id) },
                            onRestore = { drive.restore(item.id) },
                        )
                    }
                }
            }
            if (loading && items.isEmpty() || working) CircularProgressIndicator(Modifier.align(Alignment.Center))
            if (section == DocsSection.HOME) {
                var menu by remember { mutableStateOf(false) }
                Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                    FloatingActionButton(onClick = { menu = true }) { Icon(Icons.Filled.Add, "Create") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("New Document") }, leadingIcon = { Icon(Icons.Filled.NoteAdd, null) }, enabled = online,
                            onClick = { menu = false; creatingDocument = true })
                        DropdownMenuItem(text = { Text("New Folder") }, leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) }, enabled = online,
                            onClick = { menu = false; creatingFolder = true })
                    }
                }
            }
        }
    }

    val driveError by drive.error.collectAsState()
    (actionError ?: driveError)?.let { message ->
        AlertDialog(
            onDismissRequest = { actionError = null; drive.error.value = null },
            title = { Text("Something went wrong") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { actionError = null; drive.error.value = null }) { Text("OK") } },
        )
    }
    if (creatingDocument) TextInputDialog("New Document", "", "Document name", "Create", onDismiss = { creatingDocument = false }) { name ->
        creatingDocument = false
        working = true
        scope.launch {
            try {
                router.push(Route.Editor(drive.createDocument(name, folderId)))
            } catch (e: Exception) {
                actionError = e.message
            }
            working = false
        }
    }
    if (creatingFolder) TextInputDialog("New Folder", "", "Folder name", "Create", onDismiss = { creatingFolder = false }) {
        creatingFolder = false
        drive.createFolder(it, folderId)
    }
    // The title, not the file name: the `.docx` is put back by the rename.
    renaming?.let { item ->
        TextInputDialog("Rename", item.displayName, "Name", "Save", onDismiss = { renaming = null }) {
            renaming = null
            if (it != item.displayName) drive.rename(item.id, it)
        }
    }
    moving?.let { item -> MoveDialog(item, onDismiss = { moving = null }) { target -> moving = null; drive.move(item.id, target) } }
    deleting?.let { item ->
        ConfirmDialog("Delete Permanently?", "“${item.displayName}” will be deleted for good.", "Delete", destructive = true, onDismiss = { deleting = null }) {
            drive.delete(item.id)
        }
    }
    if (emptying) ConfirmDialog("Empty Trash?", "Everything in Trash will be deleted for good.", "Empty Trash", destructive = true, onDismiss = { emptying = false }) {
        drive.emptyTrash()
    }
}

@Composable
private fun SortMenu(order: DocSortOrder, onChoose: (DocSortOrder) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
        DropdownMenu(open, { open = false }) {
            DocSortOrder.entries.forEach { o ->
                DropdownMenuItem(text = { Text(o.title) }, leadingIcon = { RadioButton(o == order, null) }, onClick = { open = false; onChoose(o) })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocRow(
    item: DocItem,
    section: DocsSection,
    isOffline: Boolean,
    online: Boolean,
    onOpen: () -> Unit,
    onStar: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDuplicate: () -> Unit,
    onToggleOffline: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = { menu = true }).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (item.isFolder) Icons.Filled.Folder else Icons.Filled.Description, null,
                tint = if (item.isFolder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(item.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val subtitle = listOfNotNull(relativeTime(item.modifiedAt), item.size?.takeIf { !item.isFolder }?.let(::formatBytes)).joinToString(" · ")
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (item.isStarred) Icon(Icons.Filled.Star, "Favorite", Modifier.size(16.dp), tint = DocsStyle.star)
            if (isOffline) Icon(Icons.Filled.CloudDone, "Available offline", Modifier.padding(start = 6.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.isShared) Icon(Icons.Filled.People, "Shared with you", Modifier.padding(start = 6.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(menu, { menu = false }) {
            fun act(f: () -> Unit) { menu = false; f() }
            if (section == DocsSection.TRASH) {
                DropdownMenuItem(text = { Text("Restore") }, leadingIcon = { Icon(Icons.Filled.Restore, null) }, onClick = { act(onRestore) })
                DropdownMenuItem(text = { Text("Delete Permanently", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { act(onDelete) })
                return@DropdownMenu
            }
            // Drive scopes these to the owner; offering them on somebody else's document would only fail.
            if (!item.isShared) {
                DropdownMenuItem(text = { Text(if (item.isStarred) "Remove from Favorites" else "Add to Favorites") },
                    leadingIcon = { Icon(if (item.isStarred) Icons.Filled.StarBorder else Icons.Filled.Star, null) }, onClick = { act(onStar) })
                DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { act(onRename) })
                DropdownMenuItem(text = { Text("Move…") }, leadingIcon = { Icon(Icons.Filled.DriveFileMove, null) }, onClick = { act(onMove) })
            }
            if (item.isEditable) {
                DropdownMenuItem(text = { Text("Duplicate") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) }, enabled = online, onClick = { act(onDuplicate) })
                DropdownMenuItem(text = { Text(if (isOffline) "Remove Offline Copy" else "Make Available Offline") },
                    leadingIcon = { Icon(Icons.Filled.CloudDownload, null) }, onClick = { act(onToggleOffline) })
            }
            if (!item.isShared) {
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { act(onDelete) })
            }
        }
    }
}

/** Only folders the item can land in: its own subtree is left out, since a folder inside itself would detach from the drive. */
@Composable
private fun MoveDialog(item: DocItem, onDismiss: () -> Unit, onMove: (String?) -> Unit) {
    val graph = LocalContext.current.graph
    val all by graph.drive.allItems.collectAsState()
    var selection by remember { mutableStateOf(item.parentId) }
    val destinations = remember(all) {
        val out = ArrayList<Pair<DocItem, Int>>()
        fun walk(parent: String?, depth: Int) {
            all.filter { it.isFolder && it.parentId == parent && !it.isTrashed }.sortedBy { it.name.lowercase() }.forEach { folder ->
                if (folder.id == item.id || graph.drive.isDescendant(folder.id, item.id)) return@forEach
                out += folder to depth
                walk(folder.id, depth + 1)
            }
        }
        walk(null, 0)
        out
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move “${item.displayName}” to") },
        text = {
            LazyColumn {
                item { MoveRow("Drive", Icons.Filled.FolderOpen, 0, selection == null) { selection = null } }
                items(destinations) { (folder, depth) -> MoveRow(folder.name, Icons.Filled.Folder, depth + 1, selection == folder.id) { selection = folder.id } }
            }
        },
        confirmButton = { TextButton(onClick = { onMove(selection) }, enabled = selection != item.parentId) { Text("Move") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MoveRow(name: String, icon: androidx.compose.ui.graphics.vector.ImageVector, indent: Int, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = (indent * 16).dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(name, Modifier.weight(1f))
        RadioButton(selected, onClick)
    }
}
