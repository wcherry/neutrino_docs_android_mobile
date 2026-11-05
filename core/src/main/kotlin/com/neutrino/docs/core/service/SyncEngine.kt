package com.neutrino.docs.core.service

import com.neutrino.docs.core.api.DocsApi
import com.neutrino.docs.core.document.DocumentBody
import com.neutrino.docs.core.document.DocumentCodec
import com.neutrino.docs.core.model.DeviceSession
import com.neutrino.docs.core.model.DocItem
import com.neutrino.docs.core.model.DocVersion
import com.neutrino.docs.core.model.OfflineDoc
import com.neutrino.docs.core.model.PendingMutation
import com.neutrino.shared.core.api.DriveDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.logging.Logger
import com.neutrino.shared.core.sync.SyncActivity

/** The parts of the content service the sync engine needs, so its conflict rules can be tested without HTTP. */
interface DocSyncing {
    suspend fun fileInfo(fileId: String): com.neutrino.docs.core.model.DocFileInfo?
    suspend fun saveContent(body: DocumentBody, item: DocItem, dek: ByteArray, expectedContentVersion: Long?): DocContentService.SaveResult
}

/** The parts of the drive service it needs to replay queued metadata changes. */
interface DocMutating {
    suspend fun sendTrash(itemId: String, isFolder: Boolean)
    suspend fun sendMove(itemId: String, isFolder: Boolean, target: String?)
    suspend fun sendRename(itemId: String, name: String, isFolder: Boolean): Instant
}

/**
 * Drains the offline queue whenever the device can transfer.
 *
 * The rule the feature rests on: **a queued edit is uploaded only if the server is still on the
 * version it was written against.** Otherwise the document is flagged as a conflict and left for
 * the user — a queued edit never silently clobbers a remote one. The server owns the verdict: the
 * edit's base `contentVersion` rides along as `expectedContentVersion`; the timestamp comparison
 * first is only a cheap pre-flight.
 *
 * Edits drain before metadata, so a document about to be trashed still gets its last edit uploaded.
 * One drain at a time. Main-thread confined.
 */
class SyncEngine(
    private val store: OfflineStore,
    private val content: DocSyncing,
    private val drive: DocMutating,
    private val scope: CoroutineScope,
    /** Whether the current network may be used, honouring the cellular preference. */
    private val canTransfer: () -> Boolean,
) {
    sealed class State {
        object Idle : State()
        object Offline : State()
        data class Syncing(val remaining: Int) : State()
        data class Failed(val reason: String) : State()

        val message: String
            get() = when (this) {
                Idle -> "Up to date"
                Offline -> "Offline"
                is Syncing -> if (remaining == 1) "Syncing 1 item…" else "Syncing $remaining items…"
                is Failed -> "Sync failed: $reason"
            }
    }

    private val log = Logger.getLogger("SyncEngine")
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    val lastSyncedAt = MutableStateFlow<Instant?>(null)

    private var inFlight: Job? = null

    /** Fire-and-forget; a no-op while a drain is running. */
    fun requestSync() {
        if (inFlight?.isActive == true) return
        inFlight = scope.launch { drain() }
    }

    /** Awaitable — pull to refresh, Sync Now, the background job. Joins a drain already running. */
    suspend fun syncNow() {
        inFlight?.takeIf { it.isActive }?.let { it.join(); return }
        val job = scope.launch { drain() }
        inFlight = job
        job.join()
    }

    fun connectivityChanged(online: Boolean) {
        if (online) requestSync() else {
            inFlight?.cancel()
            _state.value = State.Offline
        }
    }

    private suspend fun drain() {
        if (!canTransfer()) {
            _state.value = State.Offline
            return
        }
        val edits = drainableEdits()
        val mutations = store.drainableMutations()
        if (edits.isEmpty() && mutations.isEmpty()) {
            _state.value = State.Idle
            lastSyncedAt.value = Instant.now()
            return
        }
        var remaining = edits.size + mutations.size
        _state.value = State.Syncing(remaining)
        var lastFailure: String? = null
        for (doc in edits) {
            if (!currentCoroutineContext().isActive) break
            uploadEdit(doc)?.let { lastFailure = it }
            _state.value = State.Syncing(--remaining)
        }
        for (mutation in mutations) {
            if (!currentCoroutineContext().isActive) break
            replay(mutation)?.let { lastFailure = it }
            _state.value = State.Syncing(--remaining)
        }
        _state.value = when {
            !canTransfer() -> State.Offline
            lastFailure != null -> State.Failed(lastFailure!!)
            else -> State.Idle.also { lastSyncedAt.value = Instant.now() }
        }
    }

    /** Edits out of their backoff window and not conflicted, oldest first. */
    private fun drainableEdits(now: Long = System.currentTimeMillis()): List<OfflineDoc> =
        store.documents.value.filter { d -> d.conflict == null && d.pendingEdit?.let { now >= it.nextAttemptAt } == true }
            .sortedBy { it.pendingEdit?.editedAt }

    /** Uploads one queued edit; a failure message, or null on success or a conflict recorded. */
    private suspend fun uploadEdit(doc: OfflineDoc): String? {
        // Re-read: an earlier iteration, or the editor, may have changed it.
        val current = store.document(doc.id) ?: return null
        val edit = current.pendingEdit ?: return null
        if (current.conflict != null) return null
        return try {
            val item = current.asDocItem
            val info = content.fileInfo(item.id)
            if (info != null && info.isLive && info.updatedAt.toEpochMilli() > edit.baseServerModifiedAt) {
                store.markConflict(doc.id, info.updatedAt.toEpochMilli(), info.contentVersion)
                return null
            }
            val (body, dek) = store.readPlaintext(doc.id)
            val saved = content.saveContent(body, item, dek, edit.baseContentVersion)
            // What the server now holds — re-encoded with the pictures the offline save could not fetch.
            store.clearPendingEdit(doc.id, saved.updatedAt.toEpochMilli(), saved.contentVersion, saved.encoded, dek)
            null
        } catch (e: DocsApi.ContentVersionConflict) {
            // Something landed between the check and the upload — what the pre-flight cannot catch.
            val serverModifiedAt = runCatching { content.fileInfo(doc.id)?.updatedAt?.toEpochMilli() }.getOrNull() ?: System.currentTimeMillis()
            store.markConflict(doc.id, serverModifiedAt, e.current)
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warning("drain: ${doc.id} failed: ${e.javaClass.simpleName}")
            store.recordFailure(doc.id, e.message ?: "Upload failed")
            e.message ?: "Upload failed"
        }
    }

    private suspend fun replay(mutation: PendingMutation): String? = try {
        when (mutation.kind) {
            PendingMutation.Kind.DELETE -> drive.sendTrash(mutation.itemId, mutation.isFolder)
            PendingMutation.Kind.MOVE -> drive.sendMove(mutation.itemId, mutation.isFolder, mutation.targetFolderId)
            PendingMutation.Kind.RENAME -> { drive.sendRename(mutation.itemId, mutation.newName ?: "", mutation.isFolder) }
        }
        store.removeMutation(mutation.id)
        null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        store.recordMutationFailure(mutation.id, e.message ?: "Failed")
        e.message ?: "Failed"
    }

    /**
     * Background download: re-fetches a cached document whose copy is behind the server's, so an
     * offline reader opens current text. Anything with unsynced work is skipped — replacing its base
     * would silently resolve a conflict in the server's favour.
     */
    suspend fun refreshCachedDocuments() {
        for (doc in store.documents.value) {
            if (doc.pendingEdit != null || doc.conflict != null) continue
            runCatching {
                val info = content.fileInfo(doc.id) ?: return@runCatching
                if (info.isLive && info.updatedAt.toEpochMilli() > doc.serverModifiedAt) store.download(doc.asDocItem, doc.cacheReason)
            }
        }
    }
}

/**
 * A document's version history, through Drive's own versioning. Every snapshot is the ciphertext
 * uploaded at the time and a file's DEK never rotates, so the session's DEK opens every version.
 * Autosave does not snapshot — only an explicit save does, which is why the editor offers one.
 */
class VersionHistoryService(private val api: DocsApi, private val content: DocContentService) {

    /** Newest first, as the server orders them. */
    suspend fun listVersions(fileId: String): List<DocVersion> = api.versions(fileId)

    suspend fun versionBody(fileId: String, versionId: String, dek: ByteArray): DocumentBody {
        val ciphertext = api.download(fileId, versionId)
        return withContext(Dispatchers.Default) { DocumentCodec.decode(content.decrypt(ciphertext, dek)) }
    }

    /**
     * A named snapshot. The server also makes it the current content, so the body is encoded
     * exactly as an autosave encodes it, pictures embedded — a restorable snapshot must be whole.
     */
    suspend fun saveVersion(body: DocumentBody, item: DocItem, dek: ByteArray, label: String?): DocVersion {
        val images = content.imageBytes(body)
        val ciphertext = withContext(Dispatchers.Default) { content.encrypt(DocumentCodec.encode(body, item.displayName, images), dek) }
        return api.saveVersion(item.id, item.name, item.mimeType ?: DocItem.DOCUMENT_MIME, ciphertext, label)
    }

    /** Returns the document's new size and modification time. */
    suspend fun restore(versionId: String, fileId: String): Pair<Long, Instant> {
        val file = api.restoreVersion(fileId, versionId)
        return (file.sizeBytes ?: 0) to (DriveDate.parse(file.updatedAt) ?: Instant.now())
    }
}

/**
 * The visible half of device registration: signing in names this device (`X-Device-Name`), and this
 * reads the account's devices back and revokes them.
 */
class DeviceSessionService(private val api: DocsApi, private val scope: CoroutineScope, private val currentDeviceName: () -> String) {
    val sessions = MutableStateFlow<List<DeviceSession>>(emptyList())
    val isLoading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    /** This device first — somebody checking is looking for the ones they don't recognise — then most recent. */
    suspend fun load() {
        isLoading.value = true
        error.value = null
        try {
            sessions.value = api.sessions(currentDeviceName()).sortedWith(order)
        } catch (e: Exception) {
            error.value = e.message
        } finally {
            isLoading.value = false
        }
    }

    /** Optimistic: the row goes now and comes back if the server refuses. */
    fun revoke(id: String) {
        val before = sessions.value
        sessions.value = before.filter { it.id != id }
        scope.launch {
            try {
                api.revokeSession(id)
            } catch (e: Exception) {
                sessions.value = before
                error.value = e.message
            }
        }
    }

    private val order = Comparator<DeviceSession> { a, b ->
        if (a.isCurrentDevice != b.isCurrentDevice) return@Comparator if (a.isCurrentDevice) -1 else 1
        (b.lastUsedAt ?: b.createdAt).compareTo(a.lastUsedAt ?: a.createdAt)
    }
}

/**
 * The engine's state in the shared connectivity banner's terms. [SyncEngine.State.Offline] maps to
 * idle: the banner reads connectivity from the monitor, and on a metered network the user keeps sync
 * off it is the queued count, not "offline", that is true.
 */
val SyncEngine.State.activity: SyncActivity
    get() = when (this) {
        is SyncEngine.State.Syncing -> SyncActivity.Syncing(remaining)
        is SyncEngine.State.Failed -> SyncActivity.Failed(reason)
        SyncEngine.State.Idle, SyncEngine.State.Offline -> SyncActivity.Idle
    }
