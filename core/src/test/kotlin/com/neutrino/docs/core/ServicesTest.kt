package com.neutrino.docs.core

import com.neutrino.docs.core.api.DocsApi
import com.neutrino.docs.core.document.DocumentBody
import com.neutrino.docs.core.model.DocItem
import com.neutrino.docs.core.model.PendingMutation
import com.neutrino.docs.core.search.DocumentSearchContent
import com.neutrino.docs.core.search.IndexKeyStore
import com.neutrino.docs.core.search.IndexedDocument
import com.neutrino.docs.core.search.SavedSearch
import com.neutrino.docs.core.search.SearchField
import com.neutrino.docs.core.search.SearchFilters
import com.neutrino.docs.core.search.SearchIndex
import com.neutrino.docs.core.search.SearchIndexStore
import com.neutrino.docs.core.search.SearchTokenizer
import com.neutrino.docs.core.service.DocContentService
import com.neutrino.docs.core.service.DocsDriveService
import com.neutrino.docs.core.service.OfflineStore
import com.neutrino.docs.core.service.SyncEngine
import com.neutrino.docs.core.service.VersionHistoryService
import com.neutrino.shared.core.FakeServer
import com.neutrino.shared.core.api.NeutrinoApps
import com.neutrino.shared.core.keys.DeviceKeyCheck
import com.neutrino.shared.core.keys.InMemoryKeyringStore
import com.neutrino.shared.core.keys.KeyManager
import com.neutrino.shared.core.ooxml.DocNode
import com.neutrino.shared.core.ooxml.DocxCodec
import com.neutrino.shared.core.ooxml.ZipArchive
import com.neutrino.shared.core.ooxml.docString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.MultipartReader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

class SearchTest {

    private fun indexed(id: String, title: String, fields: Map<SearchField, String>, tags: List<String> = emptyList(), shared: Boolean = false, modified: Long = 1, folder: String? = null) =
        SearchIndex().also { it.index(IndexedDocument(id, title, "", tags, folder, shared, modified, 0), fields) }

    @Test
    fun tokenizingMatchesTheWebsRules() {
        assertEquals(listOf("café", "über", "x2", "naïve"), SearchTokenizer.words("Café — Über-x2, naïve!"))
        assertEquals(listOf("mod", "bud"), SearchTokenizer.queryPrefixes("mod bud an"))
    }

    @Test
    fun prefixesCombineWithAndAndTitlesOutrankContent() {
        val index = SearchIndex()
        index.index(IndexedDocument("a", "Budget", "", emptyList(), null, false, 1, 0), mapOf(SearchField.TITLE to "Budget", SearchField.CONTENT to "the modesto plan"))
        index.index(IndexedDocument("b", "Notes", "", emptyList(), null, false, 2, 0), mapOf(SearchField.CONTENT to "budget for modesto"))
        index.index(IndexedDocument("c", "Other", "", emptyList(), null, false, 3, 0), mapOf(SearchField.CONTENT to "budget only"))
        assertEquals(listOf("a", "b"), index.search("bud mod").map { it.documentId })
        index.remove("a")
        assertEquals(listOf("b"), index.search("bud mod").map { it.documentId })
        assertEquals(emptyList<String>(), index.search("bu").map { it.documentId })
    }

    @Test
    fun filtersNarrowByOwnerFolderDateAndTags() {
        val index = SearchIndex()
        index.index(IndexedDocument("mine", "x", "", listOf("Swift"), "f1", false, 100, 0), mapOf(SearchField.CONTENT to "alpha"))
        index.index(IndexedDocument("theirs", "y", "", emptyList(), null, true, 200, 0), mapOf(SearchField.CONTENT to "alpha"))
        assertEquals(listOf("mine"), index.search("alpha", SearchFilters(ownership = SearchFilters.Ownership.MINE)).map { it.documentId })
        assertEquals(listOf("theirs"), index.search("alpha", SearchFilters(ownership = SearchFilters.Ownership.SHARED)).map { it.documentId })
        assertEquals(listOf("mine"), index.search("alpha", SearchFilters(folderIds = setOf("f1"))).map { it.documentId })
        assertEquals(listOf("theirs"), index.search("alpha", SearchFilters(modifiedAfter = 150)).map { it.documentId })
        assertEquals(listOf("mine"), index.browse(SearchFilters(tags = setOf("swift"))).map { it.documentId })
        assertEquals(listOf("swift"), index.allTags)
    }

    @Test
    fun extractionReadsHeadingsTagsAndAltTextButNotCode() {
        val d = doc(
            DocNode("codeBlock", attrs = mapOf("language" to docString("yaml")), content = listOf(t("title: Plan\ntags: [q3, money]"))),
            h(1, "Overview"),
            p(t("Spend less"), DocNode("image", attrs = mapOf("alt" to docString("pricing chart")))),
            DocNode("codeBlock", content = listOf(t("class Foo"))),
        )
        val content = DocumentSearchContent.extract(d, "file name")
        assertEquals("Plan", content.title)
        assertEquals(listOf("q3", "money"), content.tags)
        assertEquals(listOf("Overview"), content.headings)
        assertTrue("pricing" in content.content)
        assertFalse("class" in content.content)
    }

    @Test
    fun aWordSplitByFormattingIsStillOneWord() {
        val d = doc(p(t("We "), t("saw", "italic"), t(" he"), t("ll", "bold"), t("o")), p(t("next")))
        assertEquals("We saw hello next", DocumentSearchContent.extract(d, "f").content)
    }

    @Test
    fun theStoreIsEncryptedAtRestAndForgetsEverythingOnSignOut() {
        val dir = Files.createTempDirectory("idx").toFile()
        val keys = object : IndexKeyStore {
            var key: ByteArray? = null
            override fun load() = key
            override fun save(key: ByteArray) { this.key = key }
            override fun delete() { key = null }
        }
        val store = SearchIndexStore(dir, keys)
        val index = indexed("a", "Secret plans", mapOf(SearchField.CONTENT to "severance terms"))
        store.save(index)
        store.saveSavedSearches(listOf(SavedSearch(name = "s", query = "severance")))
        assertFalse(String(File(dir, "index.bin").readBytes(), Charsets.ISO_8859_1).contains("severance"))
        assertEquals(listOf("a"), store.load().search("seve").map { it.documentId })
        assertEquals("severance", store.loadSavedSearches().single().query)
        store.destroy()
        assertNull(keys.key)
        assertTrue(store.load().isEmpty)
        dir.deleteRecursively()
    }
}

/**
 * The services against an in-memory server holding real ciphertext: real HTTP, real crypto, real
 * `.docx` packages. The crypto is never mocked — a fake that always "decrypts" asserts nothing.
 */
class ServicesTest {

    private lateinit var fake: FakeServer
    private lateinit var tmp: File
    private lateinit var api: DocsApi
    private lateinit var content: DocContentService
    private lateinit var offline: OfflineStore
    private lateinit var drive: DocsDriveService
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var online = true

    /** Docs' own routes on top of the shared fake: info, autosave with a version guard, versions, trash, renames. */
    private val versions = HashMap<String, Long>()
    private val savedVersions = HashMap<String, MutableList<Pair<String, ByteArray>>>()
    private val trashed = HashSet<String>()

    @Before
    fun setUp() = runBlocking {
        fake = FakeServer()
        tmp = Files.createTempDirectory("docs-test").toFile()
        fake.extraRoutes = { r, _ -> route(r) }
        api = fake.client(config = NeutrinoApps.docs) { http, auth -> DocsApi(http, auth) }
        api.auth.login(fake.email, fake.password)
        val keys = InMemoryKeyringStore()
        val check = DeviceKeyCheck(api, keys)
        KeyManager(api, keys, check).provision()
        content = DocContentService(api, { keys.load(fake.userId) }, check)
        offline = OfflineStore(File(tmp, "offline"), content)
        drive = DocsDriveService(api, content, offline, scope) { online }
    }

    @After
    fun tearDown() {
        fake.shutdown()
        tmp.deleteRecursively()
    }

    private fun fileJson(id: String) = fake.files[id]!!.let {
        """{"id":"$id","name":"${it.name}","folderId":${it.folderId?.let { f -> "\"$f\"" } ?: "null"},"sizeBytes":${it.blob.size},"mimeType":"${it.mimeType}",""" +
            """"isStarred":false,"updatedAt":"2026-01-0${minOf(9, versions[id]?.toInt() ?: 1)}T00:00:00","contentVersion":${versions[id] ?: 1}}"""
    }

    private fun route(r: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse? {
        val path = r.requestUrl!!.encodedPath
        Regex("/api/v1/drive/files/([^/]+)/info").matchEntire(path)?.let { m ->
            val id = m.groupValues[1]
            if (id !in fake.files) return fake.status(404)
            return fake.ok(fileJson(id).dropLast(1) + ""","yourRole":"owner"${if (id in trashed) ",\"deletedAt\":\"2026-02-01T00:00:00\"" else ""}}""")
        }
        Regex("/api/v1/drive/files/([^/]+)/autosave").matchEntire(path)?.let { m ->
            val id = m.groupValues[1]
            val current = versions[id] ?: 1
            val expected = r.requestUrl!!.queryParameter("expectedContentVersion")?.toLong()
            if (expected != null && expected != current) {
                return fake.status(409, "content version mismatch: expected $expected, found $current").setBody(
                    """{"error":{"code":"CONTENT_VERSION_CONFLICT","message":"expected $expected, found $current"}}""")
            }
            val blob = filePart(r)
            fake.files[id] = fake.files[id]!!.copy(blob = blob)
            versions[id] = current + 1
            return fake.ok(fileJson(id))
        }
        Regex("/api/v1/drive/files/([^/]+)/versions").matchEntire(path)?.let { m ->
            val id = m.groupValues[1]
            if (r.method == "POST") {
                val blob = filePart(r)
                val list = savedVersions.getOrPut(id) { mutableListOf() }
                val vid = "v${list.size + 1}"
                list += vid to blob
                fake.files[id] = fake.files[id]!!.copy(blob = blob)
                return fake.ok("""{"id":"$vid","fileId":"$id","versionNumber":${list.size},"sizeBytes":${blob.size},"label":"named","createdAt":"2026-03-01T00:00:00Z","isNamed":true}""")
            }
            val list = savedVersions[id].orEmpty()
            return fake.ok("""{"versions":[${list.mapIndexed { i, (vid, b) -> """{"id":"$vid","fileId":"$id","versionNumber":${i + 1},"sizeBytes":${b.size},"createdAt":"2026-03-01T00:00:00Z","isNamed":true}""" }.joinToString(",")}],"total":${list.size}}""")
        }
        Regex("/api/v1/drive/files/([^/]+)/versions/([^/]+)/download").matchEntire(path)?.let { m ->
            val blob = savedVersions[m.groupValues[1]]?.firstOrNull { it.first == m.groupValues[2] }?.second ?: return fake.status(404)
            return okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody(okio.Buffer().write(blob))
        }
        if (path == "/api/v1/drive/bulk/trash") {
            Regex("\"fileIds\":\\[\"([^\"]+)\"").find(r.body.readUtf8())?.let { trashed += it.groupValues[1] }
            return fake.ok("""{"affected":1}""")
        }
        Regex("/api/v1/drive/files/([^/]+)").matchEntire(path)?.let { m ->
            if (r.method == "PATCH") {
                val name = Regex("\"name\":\"([^\"]+)\"").find(r.body.readUtf8())?.groupValues?.get(1)
                name?.let { fake.files[m.groupValues[1]] = fake.files[m.groupValues[1]]!!.copy(name = it) }
                return fake.ok(fileJson(m.groupValues[1]))
            }
        }
        return null
    }

    private fun filePart(r: okhttp3.mockwebserver.RecordedRequest): ByteArray {
        val boundary = r.getHeader("Content-Type")!!.substringAfter("boundary=")
        MultipartReader(r.body, boundary).use { reader ->
            while (true) {
                val part = reader.nextPart() ?: break
                if ("name=\"file\"" in part.headers["Content-Disposition"]!!) return part.body.readByteArray()
                part.body.readByteArray()
            }
        }
        error("no file part")
    }

    private val body = DocumentBody(doc(h(1, "Plan"), p(t("Secret quarterly numbers"))), com.neutrino.shared.core.ooxml.docObject("docTheme" to docString("serif")))

    @Test
    fun aDocumentIsCreatedAsARealDocxSavedAndReadBack() = runBlocking {
        val item = drive.createDocument("Plan", null)
        assertEquals("Plan.docx", item.name)
        // Created as a real package under its own key, the sealed key in the same request.
        assertTrue("encrypted_file_key" in fake.lastUploadParts)
        val stored = fake.files[item.id]!!.blob
        assertFalse(DocxCodec.isDocument(stored))
        val (empty, dek) = content.loadContent(item)
        assertTrue(empty.blocks.isEmpty())

        val saved = content.saveContent(body, item, dek, item.contentVersion)
        assertTrue(DocxCodec.isDocument(saved.encoded))
        assertFalse(String(fake.files[item.id]!!.blob, Charsets.ISO_8859_1).contains("quarterly"))
        val (loaded, _) = content.loadContent(item)
        assertEquals(body.doc, loaded.doc)
        // The web's page setup and theme ride through a phone's save.
        assertEquals(docString("serif"), (loaded.layout as kotlinx.serialization.json.JsonObject)["docTheme"])
    }

    @Test
    fun aStaleSaveIsRefusedByTheServer() = runBlocking {
        val item = drive.createDocument("Plan", null)
        val (_, dek) = content.loadContent(item)
        content.saveContent(body, item, dek, 1)
        try {
            content.saveContent(body, item, dek, 1)
            fail()
        } catch (e: DocsApi.ContentVersionConflict) {
            assertEquals(2L, e.current)
        }
    }

    @Test
    fun aPictureIsUploadedIntoAttachmentsAndEmbeddedWhenTheDocumentIsSaved() = runBlocking {
        val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")
        val fileId = content.uploadAttachment(png, "cat.png", "image/png")
        assertTrue(fake.folders.values.any { it.first == "Attachments" })
        content.reset()
        assertTrue(content.attachmentData(fileId).contentEquals(png))
        val item = drive.createDocument("Pics", null)
        val (_, dek) = content.loadContent(item)
        val withPicture = DocumentBody(doc(p(DocNode("image", attrs = mapOf("src" to docString("neutrino-drive:$fileId"), "width" to docString("320"))))))
        val saved = content.saveContent(withPicture, item, dek, null)
        assertTrue("word/media/image1.png" in ZipArchive(saved.encoded))
        assertEquals(withPicture.doc, content.loadContent(item).first.doc)
    }

    @Test
    fun duplicateIsANewFileUnderAFreshKey() = runBlocking {
        val item = drive.createDocument("Plan", null)
        content.saveContent(body, item, content.loadContent(item).second, null)
        val copy = drive.duplicate(item.id)
        assertEquals("Plan copy.docx", copy.name)
        assertEquals(body.doc, content.loadContent(copy).first.doc)
        assertFalse(fake.keyRefs[item.id]!!.values.first().first == fake.keyRefs[copy.id]!!.values.first().first)
    }

    @Test
    fun anOfflineEditQueuesAsCiphertextAndDrainsWhenOnline() = runBlocking {
        val item = drive.createDocument("Plan", null)
        offline.download(item)
        val (_, dek) = offline.readPlaintext(item.id)
        offline.writePendingEdit(body, item.id, dek)
        assertEquals(1, offline.pendingEditCount)
        tmp.walk().filter { it.isFile }.forEach { assertFalse(it.name, String(it.readBytes(), Charsets.ISO_8859_1).contains("quarterly")) }
        assertEquals(body.doc, offline.readPlaintext(item.id).first.doc)

        val engine = SyncEngine(offline, content, drive, scope) { true }
        engine.syncNow()
        assertEquals(0, offline.pendingEditCount)
        assertEquals(SyncEngine.State.Idle, engine.state.value)
        assertEquals(body.doc, content.loadContent(item).first.doc)
    }

    @Test
    fun aQueuedEditNeverOverwritesARemoteOne() = runBlocking {
        val item = drive.createDocument("Plan", null)
        offline.download(item)
        val (_, dek) = offline.readPlaintext(item.id)
        offline.writePendingEdit(body, item.id, dek)
        // Somebody else saves meanwhile.
        content.saveContent(DocumentBody(doc(p(t("theirs")))), item, dek, null)
        SyncEngine(offline, content, drive, scope) { true }.syncNow()
        assertNotNull(offline.document(item.id)!!.conflict)
        assertEquals(doc(p(t("theirs"))), content.loadContent(item).first.doc)
        // Keeping mine rearms the guard against the version I chose to overwrite.
        offline.resolveKeepingLocal(item.id)
        SyncEngine(offline, content, drive, scope) { true }.syncNow()
        assertEquals(body.doc, content.loadContent(item).first.doc)
        assertNull(offline.document(item.id)!!.conflict)
    }

    @Test
    fun deletesAndRenamesMadeOfflineReplayAndSupersede() = runBlocking {
        val item = drive.createDocument("Plan", null)
        online = false
        drive.rename(item.id, "Renamed")
        assertEquals("Renamed.docx", drive.item(item.id)!!.name)
        drive.rename(item.id, "Again")
        assertEquals(1, offline.pendingMutations.value.size)
        drive.delete(item.id)
        assertEquals(listOf(PendingMutation.Kind.DELETE), offline.pendingMutations.value.map { it.kind })
        online = true
        SyncEngine(offline, content, drive, scope) { true }.syncNow()
        assertTrue(item.id in trashed)
        assertTrue(offline.pendingMutations.value.isEmpty())
    }

    @Test
    fun aNamedVersionIsSavedListedAndRead() = runBlocking {
        val item = drive.createDocument("Plan", null)
        val dek = content.loadContent(item).second
        val history = VersionHistoryService(api, content)
        val version = history.saveVersion(body, item, dek, "named")
        assertEquals("named (v1)", version.displayTitle)
        assertEquals(listOf("v1"), history.listVersions(item.id).map { it.id })
        assertEquals(body.doc, history.versionBody(item.id, "v1", dek).doc)
    }

    @Test
    fun aBodyThatIsNotAPackageReadsAsPlainText() {
        val decoded = com.neutrino.docs.core.document.DocumentCodec.decode("line one\nline two".toByteArray())
        assertEquals(doc(p(t("line one")), p(t("line two"))), decoded.doc)
        assertTrue(com.neutrino.docs.core.document.DocumentCodec.decode(ByteArray(0)).blocks.isEmpty())
    }

    @Test
    fun namesKeepTheirExtensionAndSortByTitle() {
        assertEquals("Q3.docx", DocsDriveService.normalizedName(" Q3 "))
        assertEquals("Untitled document.docx", DocsDriveService.normalizedName(""))
        val items = listOf(
            DocItem("b", "beta.docx", DocItem.ItemType.FILE, null, modifiedAt = java.time.Instant.ofEpochSecond(2)),
            DocItem("f", "Zed", DocItem.ItemType.FOLDER, null, modifiedAt = java.time.Instant.ofEpochSecond(1)),
            DocItem("a", "Alpha.docx", DocItem.ItemType.FILE, null, modifiedAt = java.time.Instant.ofEpochSecond(3)),
        )
        assertEquals(listOf("f", "a", "b"), com.neutrino.docs.core.model.DocSortOrder.NAME_ASCENDING.sorted(items).map { it.id })
        assertEquals(listOf("f", "a", "b"), com.neutrino.docs.core.model.DocSortOrder.NEWEST.sorted(items).map { it.id })
        assertEquals(listOf("f", "b", "a"), com.neutrino.docs.core.model.DocSortOrder.OLDEST.sorted(items).map { it.id })
    }
}
