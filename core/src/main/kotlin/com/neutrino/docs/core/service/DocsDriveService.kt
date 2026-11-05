package com.neutrino.docs.core.service

import com.neutrino.docs.core.api.DocsApi
import com.neutrino.docs.core.model.DocItem
import com.neutrino.docs.core.model.DocsSection
import com.neutrino.docs.core.model.PendingMutation
import com.neutrino.shared.core.api.DriveDate
import com.neutrino.shared.core.api.FolderDto
import com.neutrino.shared.core.ooxml.DocxCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

class DocumentNotFound : Exception("That document is no longer available.")

/**
 * Browses and organises the documents in Neutrino Drive through Drive's own folder, file and trash
 * APIs. Every listing asks for the `office` category and narrows to documents here.
 *
 * Changes are optimistic: the local model moves first and is rolled back if the server refuses.
 * Offline, deletes, moves and renames are queued in [OfflineStore] instead. Main-thread confined.
 */
class DocsDriveService(
    private val api: DocsApi,
    private val content: DocContentService,
    private val offline: OfflineStore,
    private val scope: CoroutineScope,
    private val isOnline: () -> Boolean,
) : DocMutating {
    /** The user's own folders and documents, every folder visited so far. Also backs the move picker. */
    val allItems = MutableStateFlow<List<DocItem>>(emptyList())
    val recentItems = MutableStateFlow<List<DocItem>>(emptyList())
    val starredItems = MutableStateFlow<List<DocItem>>(emptyList())
    val sharedItems = MutableStateFlow<List<DocItem>>(emptyList())
    val trashItems = MutableStateFlow<List<DocItem>>(emptyList())

    private val _loading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _loading
    val error = MutableStateFlow<String?>(null)

    // ── Queries ─────────────────────────────────────────────────────────────────────────────────

    fun items(section: DocsSection, parentId: String? = null): List<DocItem> = when (section) {
        DocsSection.HOME -> allItems.value.filter { it.parentId == parentId }
        DocsSection.RECENT -> recentItems.value
        DocsSection.FAVORITES -> starredItems.value
        DocsSection.SHARED -> sharedItems.value
        DocsSection.TRASH -> trashItems.value
    }

    /** An item wherever it currently lives: one reached from Recent or Shared was never in a folder listing. */
    fun item(id: String): DocItem? =
        (allItems.value + recentItems.value + starredItems.value + sharedItems.value + trashItems.value).firstOrNull { it.id == id }

    /** Every document the app has seen this session, for name search. */
    val knownDocuments: List<DocItem>
        get() = (allItems.value + recentItems.value + starredItems.value + sharedItems.value).filter { it.isEditable }.distinctBy { it.id }

    /** The folders from the root down to `folderId`. Bounded, so a parent cycle in bad data cannot spin. */
    fun breadcrumb(folderId: String?): List<DocItem> {
        val chain = ArrayList<DocItem>()
        var current = folderId
        var guard = allItems.value.size + 1
        while (current != null && guard-- > 0) {
            val folder = allItems.value.firstOrNull { it.id == current } ?: break
            chain.add(0, folder)
            current = folder.parentId
        }
        return chain
    }

    /** Whether `child` is `folderId` itself or beneath it — moving a folder into its own subtree would detach it. */
    fun isDescendant(child: String?, folderId: String): Boolean {
        var current = child
        var guard = allItems.value.size + 1
        while (current != null && guard-- > 0) {
            if (current == folderId) return true
            current = allItems.value.firstOrNull { it.id == current }?.parentId
        }
        return false
    }

    /**
     * One document's metadata straight from the server, for a link that names a document no listing
     * has loaded. Not a document this app reads → [DocumentNotFound].
     */
    suspend fun fetchItem(id: String): DocItem {
        val file = api.fileMetadata(id)
        if (!DocItem.isDocument(file.mimeType)) throw DocumentNotFound()
        return DocContentService.item(file)
    }

    // ── Loading ─────────────────────────────────────────────────────────────────────────────────

    suspend fun load(section: DocsSection, parentId: String? = null) {
        _loading.value = true
        error.value = null
        try {
            when (section) {
                DocsSection.HOME -> {
                    val listing = api.documentFolder(parentId)
                    val folders = listing.folders.map { folder(it) }
                    val files = listing.files.filter { DocItem.isDocument(it.mimeType) }.map { DocContentService.item(it) }
                    // Replace this parent's items so a refresh cannot duplicate rows.
                    allItems.update { items -> items.filter { it.parentId != parentId } + folders + files }
                }
                DocsSection.RECENT -> recentItems.value = api.recent().files.filter { DocItem.isDocument(it.mimeType) }.take(RECENT_LIMIT).map { DocContentService.item(it) }
                DocsSection.FAVORITES -> {
                    val listing = api.starred()
                    starredItems.value = listing.folders.map { folder(it) } + listing.files.filter { DocItem.isDocument(it.mimeType) }.map { DocContentService.item(it) }
                }
                DocsSection.SHARED -> {
                    val listing = api.sharedWithMe()
                    // Somebody else's folder: its parent is a folder this account cannot list, and its star is the owner's.
                    sharedItems.value = listing.folders.map { folder(it).copy(parentId = null, isStarred = false, isShared = true) } +
                        listing.files.filter { DocItem.isDocument(it.mimeType) }.map { DocContentService.item(it, isShared = true) }
                }
                DocsSection.TRASH -> {
                    val listing = api.trash()
                    trashItems.value = listing.folders.map {
                        DocItem(it.id, it.name, DocItem.ItemType.FOLDER, null, modifiedAt = DriveDate.parse(it.deletedAt) ?: Instant.EPOCH, isTrashed = true)
                    } + listing.files.filter { DocItem.isDocument(it.mimeType) }.map {
                        DocItem(it.id, it.name, DocItem.ItemType.FILE, null, it.sizeBytes, DriveDate.parse(it.deletedAt) ?: Instant.EPOCH, isTrashed = true, mimeType = it.mimeType)
                    }
                }
            }
        } catch (e: Exception) {
            error.value = e.message
        } finally {
            _loading.value = false
        }
    }

    /** One folder's contents without disturbing the browser — the search crawl walks the drive with this. */
    suspend fun fetchContents(folderId: String?): Pair<List<DocItem>, List<DocItem>> {
        val listing = api.documentFolder(folderId)
        return listing.folders.map { folder(it) } to listing.files.filter { DocItem.isDocument(it.mimeType) }.map { DocContentService.item(it) }
    }

    suspend fun fetchSharedDocuments(): List<DocItem> =
        api.sharedWithMe().files.filter { DocItem.isDocument(it.mimeType) }.map { DocContentService.item(it, isShared = true) }

    /** Images in a folder, for the picker; folders come back too so it can be browsed. */
    suspend fun fetchImages(folderId: String?): Pair<List<DocItem>, List<DocItem>> {
        val listing = api.imageFolder(folderId)
        return listing.folders.map { folder(it) } to listing.files.filter { it.mimeType?.startsWith("image/") == true }.map { DocContentService.item(it) }
    }

    /** Recently touched images across the drive — where the picker opens. */
    suspend fun fetchRecentImages(limit: Int = 50): List<DocItem> =
        api.recent(type = "photo", limit = limit).files.filter { it.mimeType?.startsWith("image/") == true }.map { DocContentService.item(it) }

    // ── Create ──────────────────────────────────────────────────────────────────────────────────

    fun createFolder(name: String, parentId: String?) {
        val placeholder = DocItem(UUID.randomUUID().toString(), name, DocItem.ItemType.FOLDER, parentId, modifiedAt = Instant.now())
        allItems.update { it + placeholder }
        scope.launch {
            try {
                val created = folder(api.createFolder(name, parentId))
                allItems.update { items -> items.map { if (it.id == placeholder.id) created else it } }
            } catch (e: Exception) {
                allItems.update { items -> items.filter { it.id != placeholder.id } }
                error.value = e.message
            }
        }
    }

    /** Needs the network: a new file has no id to queue work against. */
    suspend fun createDocument(name: String, parentId: String?): DocItem {
        val item = content.createDocument(normalizedName(name), parentId)
        allItems.update { it + item }
        return item
    }

    /**
     * Drive has no copy endpoint, so a duplicate is downloaded, decrypted and uploaded as a new file
     * under a fresh DEK — written by this app's own encoder, layout included.
     */
    suspend fun duplicate(itemId: String): DocItem {
        val source = item(itemId)?.takeIf { it.isEditable } ?: throw DocumentNotFound()
        val (body, _) = content.loadContent(source)
        val copy = content.createDocument(source.duplicateName, source.parentId, body)
        allItems.update { it + copy }
        return copy
    }

    // ── Rename ──────────────────────────────────────────────────────────────────────────────────

    /** A document keeps its `.docx` whatever was typed: the sheet shows the title, and "Q3" means `Q3.docx`. */
    fun rename(itemId: String, newName: String) {
        val existing = item(itemId) ?: return
        val previousName = existing.name
        val previousModifiedAt = existing.modifiedAt
        val typed = newName.trim()
        val finalName = if (existing.isFolder) typed else normalizedName(typed)
        applyEverywhere(itemId) { it.copy(name = finalName) }
        offline.updateName(itemId, finalName)

        if (!isOnline()) {
            offline.enqueue(PendingMutation(itemId = itemId, kind = PendingMutation.Kind.RENAME, isFolder = existing.isFolder, newName = finalName, queuedAt = System.currentTimeMillis()))
            return
        }
        scope.launch {
            try {
                val updatedAt = sendRename(itemId, finalName, existing.isFolder)
                // A rename bumps `updated_at` without touching content: keep the cache's idea of the server in step.
                offline.rebasePendingEdit(itemId, previousModifiedAt.toEpochMilli(), updatedAt.toEpochMilli())
            } catch (e: Exception) {
                applyEverywhere(itemId) { it.copy(name = previousName) }
                offline.updateName(itemId, previousName)
                error.value = e.message
            }
        }
    }

    override suspend fun sendRename(itemId: String, name: String, isFolder: Boolean): Instant =
        DriveDate.parse(if (isFolder) api.updateFolder(itemId, name = name).updatedAt else api.updateFile(itemId, name = name).updatedAt) ?: Instant.now()

    // ── Favorites ───────────────────────────────────────────────────────────────────────────────

    /** Offline, starring is reverted with a message: it has no safe offline semantics. */
    fun setStarred(itemId: String, starred: Boolean) {
        val item = item(itemId) ?: return
        val previousModifiedAt = item.modifiedAt
        applyStarred(item.copy(isStarred = starred))
        if (!isOnline()) {
            applyStarred(item.copy(isStarred = !starred))
            error.value = "Favorites need a connection."
            return
        }
        scope.launch {
            try {
                val updatedAt = if (item.isFolder) api.updateFolder(itemId, isStarred = starred).updatedAt else api.updateFile(itemId, isStarred = starred).updatedAt
                DriveDate.parse(updatedAt)?.let { offline.rebasePendingEdit(itemId, previousModifiedAt.toEpochMilli(), it.toEpochMilli()) }
            } catch (e: Exception) {
                applyStarred(item.copy(isStarred = !starred))
                error.value = e.message
            }
        }
    }

    // ── Delete ──────────────────────────────────────────────────────────────────────────────────

    /** Moves an item to Trash, or deletes it for good when it is already there. */
    fun delete(itemId: String) {
        if (trashItems.value.any { it.id == itemId }) permanentlyDelete(itemId) else trash(itemId)
    }

    private fun trash(itemId: String) {
        val item = allItems.value.firstOrNull { it.id == itemId } ?: recentItems.value.firstOrNull { it.id == itemId }
            ?: starredItems.value.firstOrNull { it.id == itemId } ?: return
        allItems.update { items -> items.filter { it.id != itemId } }
        trashItems.update { it + item.copy(isTrashed = true, modifiedAt = Instant.now()) }
        // Trashed items leave both server-side views; a Favorites row must not open a deleted document.
        starredItems.update { items -> items.filter { it.id != itemId } }
        recentItems.update { items -> items.filter { it.id != itemId } }

        if (!isOnline()) {
            offline.enqueue(PendingMutation(itemId = itemId, kind = PendingMutation.Kind.DELETE, isFolder = item.isFolder, queuedAt = System.currentTimeMillis()))
            return
        }
        scope.launch {
            try {
                sendTrash(itemId, item.isFolder)
            } catch (e: Exception) {
                trashItems.update { items -> items.filter { it.id != itemId } }
                allItems.update { it + item }
                if (item.isStarred) applyStarred(item)
                error.value = e.message
            }
        }
    }

    override suspend fun sendTrash(itemId: String, isFolder: Boolean) {
        if (isFolder) api.bulkTrash(emptyList(), listOf(itemId)) else api.bulkTrash(listOf(itemId), emptyList())
    }

    private fun permanentlyDelete(itemId: String) {
        val item = trashItems.value.firstOrNull { it.id == itemId } ?: return
        trashItems.update { items -> items.filter { it.id != itemId } }
        scope.launch {
            try {
                api.deleteForever(itemId, item.isFolder)
            } catch (e: Exception) {
                trashItems.update { it + item }
                error.value = e.message
            }
        }
    }

    // ── Move ────────────────────────────────────────────────────────────────────────────────────

    fun move(itemId: String, newParentId: String?) {
        val item = allItems.value.firstOrNull { it.id == itemId } ?: return
        if (isDescendant(newParentId, itemId)) return
        val previousParent = item.parentId
        allItems.update { items -> items.map { if (it.id == itemId) it.copy(parentId = newParentId) else it } }
        offline.updateParent(itemId, newParentId)
        if (!isOnline()) {
            offline.enqueue(PendingMutation(itemId = itemId, kind = PendingMutation.Kind.MOVE, isFolder = item.isFolder, targetFolderId = newParentId, queuedAt = System.currentTimeMillis()))
            return
        }
        scope.launch {
            try {
                sendMove(itemId, item.isFolder, newParentId)
            } catch (e: Exception) {
                allItems.update { items -> items.map { if (it.id == itemId) it.copy(parentId = previousParent) else it } }
                offline.updateParent(itemId, previousParent)
                error.value = e.message
            }
        }
    }

    override suspend fun sendMove(itemId: String, isFolder: Boolean, target: String?) {
        if (isFolder) api.bulkMove(emptyList(), listOf(itemId), target) else api.bulkMove(listOf(itemId), emptyList(), target)
    }

    // ── Trash ───────────────────────────────────────────────────────────────────────────────────

    fun restore(itemId: String) {
        val item = trashItems.value.firstOrNull { it.id == itemId } ?: return
        trashItems.update { items -> items.filter { it.id != itemId } }
        val restored = item.copy(isTrashed = false)
        allItems.update { it + restored }
        scope.launch {
            try {
                api.restoreFromTrash(itemId, item.isFolder)
            } catch (e: Exception) {
                allItems.update { items -> items.filter { it.id != itemId } }
                trashItems.update { it + item }
                error.value = e.message
            }
        }
    }

    fun emptyTrash() {
        val snapshot = trashItems.value
        trashItems.value = emptyList()
        scope.launch {
            try {
                api.emptyTrash()
            } catch (e: Exception) {
                trashItems.value = snapshot
                error.value = e.message
            }
        }
    }

    // ── Editor callbacks ────────────────────────────────────────────────────────────────────────

    /** After a save, so the listing's size and date stay in step without a refetch. */
    fun documentWasSaved(itemId: String, size: Long, modifiedAt: Instant, contentVersion: Long?) {
        applyEverywhere(itemId) { it.copy(size = size, modifiedAt = modifiedAt, contentVersion = contentVersion ?: it.contentVersion) }
    }

    fun reset() {
        listOf(allItems, recentItems, starredItems, sharedItems, trashItems).forEach { it.value = emptyList() }
        error.value = null
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    private fun applyEverywhere(itemId: String, transform: (DocItem) -> DocItem) {
        for (flow in listOf(allItems, recentItems, starredItems, sharedItems, trashItems)) {
            if (flow.value.any { it.id == itemId }) flow.update { items -> items.map { if (it.id == itemId) transform(it) else it } }
        }
    }

    /** Writes a star through every list, and adds the item to (or drops it from) Favorites, newest first. */
    private fun applyStarred(item: DocItem) {
        allItems.update { items -> items.map { if (it.id == item.id) it.copy(isStarred = item.isStarred) else it } }
        recentItems.update { items -> items.map { if (it.id == item.id) it.copy(isStarred = item.isStarred) else it } }
        starredItems.update { items ->
            if (item.isStarred) {
                if (items.any { it.id == item.id }) items.map { if (it.id == item.id) item else it } else listOf(item) + items
            } else items.filter { it.id != item.id }
        }
    }

    private fun folder(dto: FolderDto) = DocItem(
        id = dto.id, name = dto.name, type = DocItem.ItemType.FOLDER, parentId = dto.parentId,
        modifiedAt = DriveDate.parse(dto.updatedAt) ?: Instant.EPOCH, isStarred = dto.isStarred ?: false,
    )

    companion object {
        const val RECENT_LIMIT = 50

        /** The title typed, with `.docx` on the end — what a download has to land on disk as. */
        fun normalizedName(name: String): String = DocxCodec.withExtension(name.trim().ifEmpty { "Untitled document" })
    }
}
