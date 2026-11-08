package com.neutrino.docs.ui.offline

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neutrino.docs.Route
import com.neutrino.docs.core.service.SyncEngine
import com.neutrino.docs.graph
import com.neutrino.docs.ui.ActionRow
import com.neutrino.docs.ui.DocsStyle
import com.neutrino.docs.ui.ErrorText
import com.neutrino.docs.ui.FormRow
import com.neutrino.docs.ui.FormSection
import com.neutrino.docs.ui.PageBar
import com.neutrino.docs.ui.formatBytes
import com.neutrino.docs.ui.formatDateTime
import com.neutrino.shared.android.ui.ConfirmDialog
import kotlinx.coroutines.launch

/**
 * What is stored on this phone and what is waiting for the server. Conflicts are the user's to
 * decide: nothing is uploaded until they choose, so a queued edit never overwrites somebody else's work.
 */
@Composable
fun OfflineScreen() {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val docs by graph.offline.documents.collectAsState()
    val mutations by graph.offline.pendingMutations.collectAsState()
    val state by graph.sync.state.collectAsState()
    val last by graph.sync.lastSyncedAt.collectAsState()
    val online by graph.isOnline.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    var discarding by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        PageBar("Offline", null)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            FormSection("Synchronization", last?.let { "Last synced ${formatDateTime(it)}" }) {
                FormRow {
                    Text(state.message, Modifier.weight(1f), color = if (state is SyncEngine.State.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    if (!online) Text("No connection", style = MaterialTheme.typography.bodySmall, color = DocsStyle.warning)
                }
                ActionRow("Sync Now", Icons.Filled.Sync, enabled = online && state !is SyncEngine.State.Syncing) { scope.launch { graph.sync.syncNow() } }
            }

            val conflicts = docs.filter { it.conflict != null }
            if (conflicts.isNotEmpty()) {
                FormSection("Conflicts", "Nothing is uploaded until you choose — a queued edit never overwrites somebody else's work.") {
                    conflicts.forEach { doc ->
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Warning, null, tint = DocsStyle.warning)
                                Text(doc.displayName, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
                            }
                            Text("This document changed on the server while you were editing it offline.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
                            Row {
                                OutlinedButton(onClick = {
                                    runCatching { graph.offline.resolveKeepingLocal(doc.id) }.onFailure { error = it.message }
                                    graph.sync.requestSync()
                                }) { Text("Keep Mine") }
                                Spacer(Modifier.padding(4.dp))
                                OutlinedButton(onClick = {
                                    scope.launch { runCatching { graph.offline.resolveKeepingServer(doc.id) }.onFailure { error = it.message } }
                                }) { Text("Use Server's") }
                            }
                        }
                    }
                }
            }

            if (mutations.isNotEmpty()) {
                FormSection("Queued Changes", "Deletes, moves and renames made offline. They replay in order when you reconnect.") {
                    mutations.forEach { m ->
                        FormRow {
                            Column(Modifier.weight(1f)) {
                                Text(m.summary)
                                Text(graph.drive.item(m.itemId)?.displayName ?: graph.offline.document(m.itemId)?.displayName ?: "Item", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                m.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                    ActionRow("Discard Queued Changes", color = MaterialTheme.colorScheme.error) { discarding = true }
                }
            }

            FormSection("Stored on This Phone", "${formatBytes(graph.offline.totalBytesOnDisk)} used. Documents are stored encrypted, and need your key to open.") {
                if (docs.isEmpty()) Text("No documents are stored on this phone yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                docs.forEach { doc ->
                    FormRow(onClick = { graph.router.push(Route.Editor(doc.asDocItem)) }) {
                        Column(Modifier.weight(1f)) {
                            Text(doc.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val parts = listOfNotNull(doc.cacheReason.title, formatBytes(doc.sizeBytes), if (doc.pendingEdit != null) "Unsynced" else null)
                            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                                color = if (doc.pendingEdit != null) DocsStyle.warning else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { runCatching { graph.offline.remove(doc.id) }.onFailure { error = it.message } }) { Icon(Icons.Filled.Delete, "Remove") }
                    }
                }
            }
            ErrorText(error)
            Spacer(Modifier.height(32.dp))
        }
    }
    if (discarding) ConfirmDialog("Discard Queued Changes?", "Deletes, moves and renames made offline won't reach the server.", "Discard",
        destructive = true, onDismiss = { discarding = false }) { graph.offline.clearMutationQueue() }

}
