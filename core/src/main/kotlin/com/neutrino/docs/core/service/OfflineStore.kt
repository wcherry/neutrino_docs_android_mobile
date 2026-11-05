package com.neutrino.docs.core.service

import com.neutrino.docs.core.document.DocumentBody
import com.neutrino.docs.core.document.DocumentCodec
import com.neutrino.docs.core.model.CacheReason
import com.neutrino.docs.core.model.Conflict
import com.neutrino.docs.core.model.DocItem
import com.neutrino.docs.core.model.OfflineDoc
import com.neutrino.docs.core.model.PendingEdit
import com.neutrino.docs.core.model.PendingMutation
import com.neutrino.shared.core.keys.KeyException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.text.Collator
import java.util.logging.Logger

sealed class OfflineException(message: String) : Exception(message) {
    class NotCached : OfflineException("That document isn't available offline.")
    class HasPendingEdit : OfflineException("That document has unsynced changes. Sync it before removing the offline copy.")
    class DecryptionFailed : OfflineException("Failed to decrypt the offline copy of the document.")
}

/**
 * The documents available offline, and the metadata changes waiting for the server.
 *
 *     index.json         the catalogue
 *     queue.json         the mutation queue
 *     <id>.bin           server ciphertext of the cached version, byte for byte
 *     <id>.pending.bin   a local edit, encrypted under the same DEK
 *
 * **No plaintext is ever written to disk**: the cache holds the server's ciphertext and re-encrypts
 * local edits under the document's own DEK, so reading it back still needs this device's keyring.
 * Crypto and networking are the content service's; this invents none of its own. Main-thread confined.
 */
class OfflineStore(private val directory: File, private val content: DocContentService) {

    private val log = Logger.getLogger("OfflineStore")
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }

    private val _documents = MutableStateFlow<List<OfflineDoc>>(emptyList())
    /** The catalogue, by name. */
    val documents: StateFlow<List<OfflineDoc>> = _documents

    private val _mutations = MutableStateFlow<List<PendingMutation>>(emptyList())
    /** Deletes, moves and renames made offline, oldest first. */
    val pendingMutations: StateFlow<List<PendingMutation>> = _mutations

    init {
        directory.mkdirs()
        _documents.value = runCatching { json.decodeFromString(ListSerializer(OfflineDoc.serializer()), File(directory, INDEX).readText()) }
            .getOrDefault(emptyList()).let(::sorted)
        _mutations.value = runCatching { json.decodeFromString(ListSerializer(PendingMutation.serializer()), File(directory, QUEUE).readText()) }
            .getOrDefault(emptyList()).sortedBy { it.queuedAt }
    }

    fun document(id: String): OfflineDoc? = _documents.value.firstOrNull { it.id == id }
    fun isAvailableOffline(id: String): Boolean = document(id) != null

    val pendingEditCount: Int get() = _documents.value.count { it.pendingEdit != null }
    val conflictCount: Int get() = _documents.value.count { it.conflict != null }
    /** Everything waiting for the server — what the Offline tab badges. */
    val totalPendingCount: Int get() = pendingEditCount + _mutations.value.size

    val totalBytesOnDisk: Long get() = directory.listFiles { f -> f.name.endsWith(".bin") }?.sumOf { it.length() } ?: 0

    // ── Download / remove ───────────────────────────────────────────────────────────────────────

    /**
     * Fetches and stores the server's ciphertext and sealed DEK, replacing the cached version. A
     * pending edit is left alone — its base still names the version it was written against, so a
     * newer server copy is still caught as a conflict. Decrypted once in memory, to prove this device
     * can read the blob before it is cached.
     */
    suspend fun download(item: DocItem, reason: CacheReason = CacheReason.PINNED) {
        val (ciphertext, sealed) = content.downloadEncrypted(item)
        val plaintext = withContext(Dispatchers.Default) { decrypt(ciphertext, unseal(sealed)) }
        var serverModifiedAt = item.modifiedAt.toEpochMilli()
        var serverContentVersion = item.contentVersion
        runCatching { content.fileInfo(item.id) }.getOrNull()?.let {
            serverModifiedAt = it.updatedAt.toEpochMilli()
            serverContentVersion = it.contentVersion
        }
        withContext(Dispatchers.IO) { write(ciphertext, base(item.id)) }
        val existing = document(item.id)
        val now = System.currentTimeMillis()
        val doc = (existing ?: OfflineDoc(item.id, item.name, item.parentId, item.mimeType, sealed.sealed, sealed.keyVersion,
            serverModifiedAt, serverContentVersion, now, plaintext.size.toLong(), cacheReason = reason)).copy(
            name = item.name,
            parentId = item.parentId,
            mimeType = item.mimeType ?: existing?.mimeType,
            sealedDek = sealed.sealed,
            keyVersion = sealed.keyVersion,
            serverModifiedAt = serverModifiedAt,
            serverContentVersion = serverContentVersion,
            cachedAt = now,
            // A pinned document is never downgraded to an evictable reason by a background refresh.
            cacheReason = if (existing?.cacheReason == CacheReason.PINNED) CacheReason.PINNED else reason,
            sizeBytes = if (existing?.pendingEdit == null) plaintext.size.toLong() else existing.sizeBytes,
        )
        upsert(doc)
    }

    /** Refuses to discard unsynced work. */
    fun remove(id: String) {
        val doc = document(id) ?: throw OfflineException.NotCached()
        if (doc.pendingEdit != null) throw OfflineException.HasPendingEdit()
        deleteBlobs(id)
        _documents.value = _documents.value.filter { it.id != id }
        saveIndex()
    }

    /** Removes everything that has no unsynced changes. */
    fun removeAll() {
        val removable = _documents.value.filter { it.pendingEdit == null }
        removable.forEach { deleteBlobs(it.id) }
        _documents.value = _documents.value.filter { it.pendingEdit != null }
        saveIndex()
    }

    /** Drops the automatic entries, keeping pinned ones and anything unsynced. */
    fun evictAutomaticEntries() {
        val evictable = _documents.value.filter { it.cacheReason.isEvictable && it.pendingEdit == null }
        evictable.forEach { deleteBlobs(it.id) }
        val ids = evictable.map { it.id }.toSet()
        _documents.value = _documents.value.filter { it.id !in ids }
        saveIndex()
    }

    /** Sign-out: nothing of the account may outlive its session — queued work included. */
    fun wipe() {
        directory.listFiles()?.forEach { it.delete() }
        _documents.value = emptyList()
        _mutations.value = emptyList()
    }

    // ── Plaintext, in memory only ───────────────────────────────────────────────────────────────

    /** Decrypts for reading or editing; a pending edit wins over the cached version. Returns the DEK too. */
    suspend fun readPlaintext(id: String): Pair<DocumentBody, ByteArray> = withContext(Dispatchers.Default) { readPlaintextNow(id) }

    private fun readPlaintextNow(id: String): Pair<DocumentBody, ByteArray> {
        val doc = document(id) ?: throw OfflineException.NotCached()
        val dek = unseal(SealedFileKey(doc.sealedDek, doc.keyVersion))
        // A missing pending blob (an interrupted write) falls back to the base: a stale read beats a failure.
        val file = pending(id).takeIf { doc.pendingEdit != null && it.exists() } ?: base(id)
        if (!file.exists()) throw OfflineException.NotCached()
        return DocumentCodec.decode(decrypt(file.readBytes(), dek)) to dek
    }

    /**
     * Stores an edit as the pending edit, encrypted under the document's DEK and in the wire format —
     * a whole `.docx`, so the queue can upload it after a restart without anything else. Pictures come
     * from what this session already fetched; a missing one rides along as a placeholder. A chain of
     * offline edits keeps the base it started from.
     */
    suspend fun writePendingEdit(body: DocumentBody, id: String, dek: ByteArray) {
        val doc = document(id) ?: throw OfflineException.NotCached()
        val images = content.cachedImageBytes(body)
        val stored = withContext(Dispatchers.Default) {
            DocumentCodec.encode(body, doc.displayName, images).also { write(content.encrypt(it, dek), pending(id)) }
        }
        upsert(doc.copy(
            pendingEdit = PendingEdit(
                editedAt = System.currentTimeMillis(),
                baseServerModifiedAt = doc.pendingEdit?.baseServerModifiedAt ?: doc.serverModifiedAt,
                baseContentVersion = doc.pendingEdit?.baseContentVersion ?: doc.serverContentVersion,
            ),
            sizeBytes = stored.size.toLong(),
        ))
    }

    // ── Sync-engine callbacks ───────────────────────────────────────────────────────────────────

    /** Replaces the cached version with newer server ciphertext; pending edits and conflicts are the caller's. */
    fun acceptServerVersion(ciphertext: ByteArray, id: String, serverModifiedAt: Long, sizeBytes: Long, serverContentVersion: Long? = null) {
        val doc = document(id) ?: throw OfflineException.NotCached()
        write(ciphertext, base(id))
        // Null means "unchanged", never "unknown": clobbering a known version would disarm the guard.
        upsert(doc.copy(serverModifiedAt = serverModifiedAt, serverContentVersion = serverContentVersion ?: doc.serverContentVersion,
            sizeBytes = sizeBytes, cachedAt = System.currentTimeMillis()))
    }

    /**
     * Refreshes the cached version from what this device just uploaded — the very bytes, not a
     * re-encode, which could resolve a picture differently and leave the cache holding something the
     * server does not have.
     */
    fun cacheLocalVersion(stored: ByteArray, id: String, dek: ByteArray, serverModifiedAt: Long, serverContentVersion: Long?) {
        if (document(id) == null) throw OfflineException.NotCached()
        acceptServerVersion(content.encrypt(stored, dek), id, serverModifiedAt, stored.size.toLong(), serverContentVersion)
    }

    /**
     * After a successful upload: the uploaded package becomes the cached version and the conflict
     * flag clears. `uploaded` is what reached the server — re-encoded with its pictures, which a queued
     * offline edit could not fetch — so promoting the pending blob would leave the cache subtly wrong.
     */
    fun clearPendingEdit(id: String, serverModifiedAt: Long, serverContentVersion: Long?, uploaded: ByteArray? = null, dek: ByteArray? = null) {
        val doc = document(id) ?: throw OfflineException.NotCached()
        var size = doc.sizeBytes
        if (uploaded != null && dek != null) {
            write(content.encrypt(uploaded, dek), base(id))
            pending(id).delete()
            size = uploaded.size.toLong()
        } else if (pending(id).exists()) {
            write(pending(id).readBytes(), base(id))
            pending(id).delete()
        }
        upsert(doc.copy(pendingEdit = null, conflict = null, serverModifiedAt = serverModifiedAt,
            serverContentVersion = serverContentVersion ?: doc.serverContentVersion, cachedAt = System.currentTimeMillis(), sizeBytes = size))
    }

    /**
     * After a *metadata-only* write (rename, star), which bumps `updated_at` without touching content:
     * moves the base forward so the next drain does not report a conflict with the user's own rename.
     * Skipped when the cache already knows a version past the one the write was made against.
     */
    fun rebasePendingEdit(id: String, previousModifiedAt: Long, serverModifiedAt: Long) {
        val doc = document(id) ?: return
        if (serverModifiedAt <= doc.serverModifiedAt || doc.serverModifiedAt > previousModifiedAt) return
        upsert(doc.copy(serverModifiedAt = serverModifiedAt, pendingEdit = doc.pendingEdit?.copy(baseServerModifiedAt = serverModifiedAt)))
    }

    fun recordFailure(id: String, error: String) {
        val doc = document(id) ?: return
        val edit = doc.pendingEdit ?: return
        upsert(doc.copy(pendingEdit = edit.copy(attemptCount = edit.attemptCount + 1, lastError = error, lastAttemptAt = System.currentTimeMillis())))
    }

    fun markConflict(id: String, serverModifiedAt: Long, serverContentVersion: Long?) {
        val doc = document(id) ?: return
        upsert(doc.copy(conflict = Conflict(System.currentTimeMillis(), serverModifiedAt, serverContentVersion)))
    }

    // ── Conflicts ───────────────────────────────────────────────────────────────────────────────

    /**
     * Keeps the local edit: rebased onto the newer server version so the next drain overwrites it on
     * purpose, with the guard rearmed against the version the user chose to discard.
     */
    fun resolveKeepingLocal(id: String) {
        val doc = document(id) ?: throw OfflineException.NotCached()
        val edit = doc.pendingEdit ?: throw OfflineException.NotCached()
        val conflict = doc.conflict
        val serverModifiedAt = maxOf(doc.serverModifiedAt, conflict?.serverModifiedAt ?: 0)
        upsert(doc.copy(
            serverModifiedAt = serverModifiedAt,
            pendingEdit = edit.copy(
                baseServerModifiedAt = conflict?.serverModifiedAt ?: doc.serverModifiedAt,
                baseContentVersion = conflict?.serverContentVersion ?: doc.serverContentVersion,
                attemptCount = 0, lastError = null, lastAttemptAt = null,
            ),
            conflict = null,
        ))
    }

    /** Keeps the server's version: the local edit is discarded and the document downloaded again. */
    suspend fun resolveKeepingServer(id: String) {
        val doc = document(id) ?: throw OfflineException.NotCached()
        pending(id).delete()
        upsert(doc.copy(pendingEdit = null, conflict = null))
        download(doc.asDocItem, doc.cacheReason)
    }

    // ── Mutation queue ──────────────────────────────────────────────────────────────────────────

    /** Superseded entries go: "rename then delete" is one round trip, not two that can strand the queue. */
    fun enqueue(mutation: PendingMutation) {
        _mutations.value = (_mutations.value.filter { !mutation.supersedes(it) } + mutation).sortedBy { it.queuedAt }
        saveQueue()
    }

    fun removeMutation(id: String) {
        _mutations.value = _mutations.value.filter { it.id != id }
        saveQueue()
    }

    fun recordMutationFailure(id: String, error: String) {
        _mutations.value = _mutations.value.map {
            if (it.id == id) it.copy(attemptCount = it.attemptCount + 1, lastError = error, lastAttemptAt = System.currentTimeMillis()) else it
        }
        saveQueue()
    }

    /** For a queue wedged behind a change the server will never accept. */
    fun clearMutationQueue() {
        _mutations.value = emptyList()
        saveQueue()
    }

    fun drainableMutations(now: Long = System.currentTimeMillis()): List<PendingMutation> =
        _mutations.value.filter { now >= it.nextAttemptAt }.sortedBy { it.queuedAt }

    // ── Metadata mirroring ──────────────────────────────────────────────────────────────────────

    fun updateName(id: String, name: String) {
        val doc = document(id) ?: return
        if (doc.name != name) upsert(doc.copy(name = name))
    }

    fun updateParent(id: String, parentId: String?) {
        val doc = document(id) ?: return
        if (doc.parentId != parentId) upsert(doc.copy(parentId = parentId))
    }

    // ── Plumbing ────────────────────────────────────────────────────────────────────────────────

    /** A missing key is its own error; a missing *version* stays a decrypt failure — the blob is intact and the key may yet arrive. */
    private fun unseal(sealed: SealedFileKey): ByteArray = try {
        content.unsealDek(sealed)
    } catch (e: KeyException.NoEncryptionKey) {
        throw e
    } catch (e: Exception) {
        throw OfflineException.DecryptionFailed()
    }

    private fun decrypt(data: ByteArray, dek: ByteArray): ByteArray = try {
        content.decrypt(data, dek)
    } catch (e: Exception) {
        throw OfflineException.DecryptionFailed()
    }

    private fun base(id: String) = File(directory, "${safe(id)}.bin")
    private fun pending(id: String) = File(directory, "${safe(id)}.pending.bin")

    /** Ids are UUIDs; anything else is not allowed to name a path. */
    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }

    /** Written to a temporary file and renamed, so a crash mid-write never leaves half a blob. */
    private fun write(data: ByteArray, file: File) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun deleteBlobs(id: String) {
        base(id).delete()
        pending(id).delete()
    }

    private fun upsert(doc: OfflineDoc) {
        _documents.value = sorted(_documents.value.filter { it.id != doc.id } + doc)
        saveIndex()
    }

    private fun sorted(docs: List<OfflineDoc>) = docs.sortedWith { a, b -> collator.compare(a.name, b.name).takeIf { it != 0 } ?: a.id.compareTo(b.id) }

    private fun saveIndex() {
        runCatching { write(json.encodeToString(ListSerializer(OfflineDoc.serializer()), _documents.value).toByteArray(), File(directory, INDEX)) }
            .onFailure { log.warning("saveIndex failed: ${it.javaClass.simpleName}") }
    }

    /** Best effort: a queue that fails to persist still works this session. */
    private fun saveQueue() {
        runCatching { write(json.encodeToString(ListSerializer(PendingMutation.serializer()), _mutations.value).toByteArray(), File(directory, QUEUE)) }
    }

    companion object {
        private const val INDEX = "index.json"
        private const val QUEUE = "queue.json"
    }
}
