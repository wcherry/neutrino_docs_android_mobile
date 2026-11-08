package com.neutrino.docs.core.search

import com.neutrino.docs.core.model.DocItem
import com.neutrino.docs.core.service.DocContentService
import com.neutrino.docs.core.service.DocsDriveService
import com.neutrino.docs.core.service.OfflineStore
import com.neutrino.shared.core.crypto.FileCrypto
import com.neutrino.shared.core.ooxml.DocNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.logging.Logger

/** Where the index key lives — the Keystore-sealed store on Android, memory in tests. */
interface IndexKeyStore {
    fun load(): ByteArray?
    fun save(key: ByteArray)
    fun delete()
}

/**
 * The index and saved searches at rest, sealed with a secretstream under a key generated once and
 * kept only on this device. It is *not* the E2EE key and is never sent anywhere: it protects a
 * local derivative of documents the user can already read. A file that cannot be read — no key,
 * corruption — is discarded and rebuilt: the index costs time to lose, never data.
 */
class SearchIndexStore(private val directory: File, private val keys: IndexKeyStore) {
    private val log = Logger.getLogger("SearchIndexStore")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; allowStructuredMapKeys = true }

    init {
        directory.mkdirs()
    }

    fun load(): SearchIndex = read(INDEX, SearchIndex.serializer()) ?: SearchIndex()
    fun save(index: SearchIndex) = write(INDEX, SearchIndex.serializer(), index)
    fun loadSavedSearches(): List<SavedSearch> = read(SEARCHES, ListSerializer(SavedSearch.serializer())) ?: emptyList()
    fun saveSavedSearches(searches: List<SavedSearch>) = write(SEARCHES, ListSerializer(SavedSearch.serializer()), searches)

    /** Sign-out: the files and the key. The next account must not inherit what this one could read. */
    fun destroy() {
        File(directory, INDEX).delete()
        File(directory, SEARCHES).delete()
        keys.delete()
    }

    private fun <T> read(name: String, serializer: KSerializer<T>): T? {
        val file = File(directory, name)
        // A read must not mint a key: that would replace the one that could have opened this file.
        val key = keys.load() ?: return null
        if (!file.exists()) return null
        return try {
            json.decodeFromString(serializer, FileCrypto.decryptFile(file.readBytes(), key).decodeToString())
        } catch (e: Exception) {
            log.warning("read $name: unreadable, discarding")
            file.delete()
            null
        }
    }

    private fun <T> write(name: String, serializer: KSerializer<T>, value: T) {
        val key = keys.load() ?: FileCrypto.generateDek().also(keys::save)
        val file = File(directory, name)
        val tmp = File(directory, "$name.tmp")
        tmp.writeBytes(FileCrypto.encryptFile(json.encodeToString(serializer, value).toByteArray(), key))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        private const val INDEX = "index.bin"
        private const val SEARCHES = "searches.bin"
    }
}

/**
 * Search: owns the on-device index, keeps it current, answers queries, holds saved searches.
 *
 * The index is on the device because indexing on the server would mean handing it the decrypted
 * text of every document. A document is indexed when it is opened or saved — its plaintext is
 * already in hand — and the rest by the crawl, which is started by the user: it downloads and
 * decrypts every document the account can read. Main-thread confined.
 */
class SearchService(
    private val store: SearchIndexStore,
    private val scope: CoroutineScope,
    private val drive: DocsDriveService,
    private val content: DocContentService,
    private val offline: OfflineStore,
) {
    data class CrawlProgress(val indexed: Int, val total: Int, val failed: Int) {
        val fraction: Float get() = if (total == 0) 1f else minOf(1f, (indexed + failed).toFloat() / total)
    }

    private val log = Logger.getLogger("SearchService")
    private var index = SearchIndex()

    private val _savedSearches = MutableStateFlow<List<SavedSearch>>(emptyList())
    val savedSearches: StateFlow<List<SavedSearch>> = _savedSearches
    val indexedDocumentCount = MutableStateFlow(0)
    val crawlProgress = MutableStateFlow<CrawlProgress?>(null)
    val lastError = MutableStateFlow<String?>(null)
    /** Bumped on every index change, so an open results list re-runs its query. */
    val generation = MutableStateFlow(0)

    private var crawlJob: Job? = null
    private var saveJob: Job? = null

    /** Loads the stored index — once signed in, since before that there is no account to read it for. */
    suspend fun start() {
        index = withContext(Dispatchers.IO) { store.load() }
        indexedDocumentCount.value = index.documentCount
        _savedSearches.value = withContext(Dispatchers.IO) { store.loadSavedSearches() }.sortedByDescending { it.lastUsedAt }
        generation.value++
    }

    // ── Indexing ────────────────────────────────────────────────────────────────────────────────

    /** Cheap enough for every autosave: one walk of a tree in memory; the disk write is debounced. */
    fun indexDocument(item: DocItem, document: DocNode, modifiedAt: Long? = null) {
        val extracted = DocumentSearchContent.extract(document, item.displayName)
        index.index(
            IndexedDocument(item.id, extracted.title, extracted.preview, extracted.tags, item.parentId, item.isShared,
                modifiedAt ?: item.modifiedAt.toEpochMilli(), System.currentTimeMillis()),
            extracted.indexedFields,
        )
        changed()
    }

    fun removeFromIndex(id: String) {
        if (index.document(id) == null) return
        index.remove(id)
        changed()
    }

    fun isStale(item: DocItem): Boolean = index.document(item.id)?.let { it.indexedAt < item.modifiedAt.toEpochMilli() } ?: true

    private fun changed() {
        indexedDocumentCount.value = index.documentCount
        generation.value++
        scheduleSave()
    }

    // ── Searching ───────────────────────────────────────────────────────────────────────────────

    /** Synchronous: the index is in memory, and results update as the user types. No query + filters is a browse. */
    fun searchLocally(query: String, filters: SearchFilters = SearchFilters.unfiltered): List<SearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return if (filters.isActive) index.browse(filters) else emptyList()
        return index.search(trimmed, filters)
    }

    /**
     * Local results, then documents the index has not read yet matched on their *name*. The server's
     * name search (`/drive/search`) was removed (neutrino 01604d0), so the names are those this app
     * has listed. A name hit carries no tags or folder ancestry, so it is dropped rather than guessed
     * at while a tag or folder filter is on.
     */
    fun searchEverywhere(query: String, filters: SearchFilters = SearchFilters.unfiltered): List<SearchResult> {
        val local = searchLocally(query, filters)
        val prefixes = SearchTokenizer.queryPrefixes(query)
        if (prefixes.isEmpty() || filters.tags.isNotEmpty() || filters.folderIds != null) return local
        val known = local.map { it.documentId }.toSet()
        val extra = drive.knownDocuments.filter { item ->
            item.id !in known && index.document(item.id) == null &&
                SearchTokenizer.words(item.displayName).let { words -> prefixes.all { p -> words.any { it.startsWith(p) } } } &&
                when (filters.ownership) {
                    SearchFilters.Ownership.ANY -> true
                    SearchFilters.Ownership.MINE -> !item.isShared
                    SearchFilters.Ownership.SHARED -> item.isShared
                } &&
                (filters.modifiedAfter?.let { item.modifiedAt.toEpochMilli() >= it } ?: true) &&
                (filters.modifiedBefore?.let { item.modifiedAt.toEpochMilli() <= it } ?: true)
        }.map {
            SearchResult(it.id, it.displayName, "Matched the document's name", setOf(SearchField.TITLE), 0.0, it.modifiedAt.toEpochMilli(), it.isShared, nameOnly = true)
        }
        return local + extra
    }

    val allTags: List<String> get() = index.allTags

    // ── Crawling ────────────────────────────────────────────────────────────────────────────────

    val isCrawling: Boolean get() = crawlJob?.isActive == true

    /**
     * Walks the drive and indexes what is missing or stale, one document at a time with a pause
     * between, reading from the offline cache where it can.
     */
    fun indexEverything() {
        if (isCrawling) return
        crawlJob = scope.launch { crawl() }
    }

    fun cancelCrawl() {
        crawlJob?.cancel()
        crawlJob = null
        crawlProgress.value = null
    }

    private suspend fun crawl() {
        lastError.value = null
        val documents = try {
            collectDocuments()
        } catch (e: Exception) {
            lastError.value = e.message
            return
        }
        val outstanding = documents.filter(::isStale)
        crawlProgress.value = CrawlProgress(0, outstanding.size, 0)
        // Anything the index holds that the drive no longer lists was deleted or unshared.
        val live = documents.map { it.id }.toSet()
        index.documentIds.filter { it !in live }.forEach(index::remove)
        for (item in outstanding) {
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) break
            val ok = indexByReading(item)
            crawlProgress.value = crawlProgress.value?.let { if (ok) it.copy(indexed = it.indexed + 1) else it.copy(failed = it.failed + 1) }
            delay(CRAWL_PAUSE_MS)
        }
        indexedDocumentCount.value = index.documentCount
        generation.value++
        flushSave()
        crawlProgress.value = null
    }

    private suspend fun indexByReading(item: DocItem): Boolean = try {
        val body = if (offline.isAvailableOffline(item.id)) offline.readPlaintext(item.id).first else content.loadContent(item).first
        indexDocument(item, body.doc)
        true
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        log.fine("crawl: could not read ${item.id}")
        false
    }

    /** The account's own drive folder by folder, plus what is shared with it; a cycle in bad data cannot loop. */
    private suspend fun collectDocuments(): List<DocItem> {
        val documents = ArrayList<DocItem>()
        val pending = ArrayDeque<String?>().apply { add(null) }
        val visited = HashSet<String>()
        while (pending.isNotEmpty()) {
            val (folders, files) = drive.fetchContents(pending.removeLast())
            documents += files
            for (folder in folders) if (visited.add(folder.id)) pending.add(folder.id)
        }
        documents += runCatching { drive.fetchSharedDocuments() }.getOrDefault(emptyList())
        return documents.filter { it.isEditable }.distinctBy { it.id }
    }

    // ── Saved searches ──────────────────────────────────────────────────────────────────────────

    fun addSavedSearch(name: String, query: String, filters: SearchFilters) {
        _savedSearches.value = listOf(SavedSearch(name = name.trim().ifEmpty { query }, query = query, filters = filters)) + _savedSearches.value
        persistSaved()
    }

    fun deleteSavedSearch(id: String) {
        _savedSearches.value = _savedSearches.value.filter { it.id != id }
        persistSaved()
    }

    fun renameSavedSearch(id: String, name: String) {
        _savedSearches.value = _savedSearches.value.map { if (it.id == id) it.copy(name = name) else it }
        persistSaved()
    }

    /** So the list leads with what is actually used. */
    fun markUsed(id: String) {
        _savedSearches.value = _savedSearches.value.map { if (it.id == id) it.copy(lastUsedAt = System.currentTimeMillis()) else it }
            .sortedByDescending { it.lastUsedAt }
        persistSaved()
    }

    private fun persistSaved() {
        val snapshot = _savedSearches.value
        scope.launch(Dispatchers.IO) { runCatching { store.saveSavedSearches(snapshot) }.onFailure { lastError.value = it.message } }
    }

    // ── Sign-out and persistence ────────────────────────────────────────────────────────────────

    /** The index holds text decrypted from the signed-out account's documents: it cannot outlive the session. */
    fun signOut() {
        cancelCrawl()
        saveJob?.cancel()
        index = SearchIndex()
        _savedSearches.value = emptyList()
        indexedDocumentCount.value = 0
        generation.value++
        store.destroy()
    }

    /** A burst of indexing is one encrypted write, not one per document. */
    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            flushSave()
        }
    }

    /** Now — at the end of a crawl and when the app leaves the foreground. */
    fun flushSave() {
        saveJob?.cancel()
        saveJob = null
        val json = runCatching { Json.encodeToString(SearchIndex.serializer(), index) }.getOrNull() ?: return
        val snapshot = Json.decodeFromString(SearchIndex.serializer(), json)
        scope.launch(Dispatchers.IO) { runCatching { store.save(snapshot) }.onFailure { lastError.value = it.message } }
    }

    companion object {
        private const val SAVE_DEBOUNCE_MS = 5_000L
        private const val CRAWL_PAUSE_MS = 120L
    }
}
