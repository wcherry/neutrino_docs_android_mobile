package com.neutrino.docs.core

import com.neutrino.docs.core.api.DocsApi
import com.neutrino.docs.core.document.DocumentBody
import com.neutrino.docs.core.document.DocumentCodec
import com.neutrino.docs.core.model.DocsSection
import com.neutrino.docs.core.model.ShareRole
import com.neutrino.docs.core.service.DocContentService
import com.neutrino.docs.core.service.DocsDriveService
import com.neutrino.docs.core.service.OfflineStore
import com.neutrino.docs.core.service.SyncEngine
import com.neutrino.docs.core.service.VersionHistoryService
import com.neutrino.shared.core.MemorySessionStore
import com.neutrino.shared.core.api.AuthClient
import com.neutrino.shared.core.api.NeutrinoApps
import com.neutrino.shared.core.keys.DeviceKeyCheck
import com.neutrino.shared.core.keys.InMemoryKeyringStore
import com.neutrino.shared.core.keys.KeyManager
import com.neutrino.shared.core.ooxml.DocNode
import com.neutrino.shared.core.ooxml.DocxCodec
import com.neutrino.shared.core.ooxml.docString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.util.Base64
import java.util.UUID

/**
 * Against a real server, when `NEUTRINO_LIVE_URL` names one (a throwaway database with the
 * `neutrino-docs-android` migration, 00143). Registers a fresh account each run.
 */
class LiveServerTest {
    @Test
    fun theWholeAppAgainstARealServer() = runBlocking<Unit> {
        val url = System.getenv("NEUTRINO_LIVE_URL")
        assumeTrue("set NEUTRINO_LIVE_URL to run", url != null)
        val http = OkHttpClient()
        val auth = AuthClient(NeutrinoApps.docs, http, MemorySessionStore(), url!!) { "JVM test - Neutrino Docs" }
        val api = DocsApi(http, auth)
        auth.register("Docs Test", "docs-${UUID.randomUUID().toString().take(8)}@example.com", "correct horse battery")
        assertTrue(auth.isSignedIn)

        val keys = InMemoryKeyringStore()
        val check = DeviceKeyCheck(api, keys)
        KeyManager(api, keys, check).provision()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val content = DocContentService(api, { keys.load(auth.userId!!) }, check)
        val tmp = Files.createTempDirectory("live").toFile()
        val offline = OfflineStore(tmp, content)
        val drive = DocsDriveService(api, content, offline, scope) { true }

        // Create: a real, empty .docx, listed in the drive root.
        val item = drive.createDocument("Live Plan", null)
        assertEquals("Live Plan.docx", item.name)
        drive.load(DocsSection.HOME)
        assertTrue(drive.items(DocsSection.HOME).any { it.id == item.id })
        val info = api.fileInfo(item.id)!!
        assertEquals(ShareRole.OWNER, info.yourRole)
        assertNotNull(info.contentVersion)

        // Save with the version guard, read it back, and see a stale save refused by the server.
        val (_, dek) = content.loadContent(item)
        val body = DocumentBody(doc(h(1, "Plan"), p(t("Quarterly numbers", "bold")), ul(li(p(t("one"))))))
        // What any reader makes of it (lists gain their default listStyleType), as the web's does.
        val stored = DocumentCodec.decode(DocumentCodec.encode(body, "Plan")).doc
        val saved = content.saveContent(body, item, dek, info.contentVersion)
        assertTrue(DocxCodec.isDocument(saved.encoded))
        assertEquals(stored, content.loadContent(item).first.doc)
        try {
            content.saveContent(body, item, dek, info.contentVersion)
            fail("a stale write should be refused")
        } catch (e: DocsApi.ContentVersionConflict) {
        }

        // A picture into Attachments, embedded on save.
        val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")
        val picture = content.uploadAttachment(png, "dot.png", "image/png")
        content.reset()
        assertTrue(content.attachmentData(picture).contentEquals(png))
        val withPicture = DocumentBody(doc(p(DocNode("image", attrs = mapOf("src" to docString("neutrino-drive:$picture"), "width" to docString("200"))))))
        val current = api.fileInfo(item.id)!!.contentVersion
        content.saveContent(withPicture, item, dek, current)
        assertEquals(withPicture.doc, content.loadContent(item).first.doc)

        // Versions: a named one, listed and read; restored.
        val versions = VersionHistoryService(api, content)
        val named = versions.saveVersion(body, item, dek, "Milestone")
        assertTrue(versions.listVersions(item.id).any { it.id == named.id })
        assertEquals(stored, versions.versionBody(item.id, named.id, dek).doc)
        versions.restore(named.id, item.id)

        // Offline: an edit queued as ciphertext, drained by the sync engine.
        offline.download(item.copy(contentVersion = api.fileInfo(item.id)!!.contentVersion))
        offline.writePendingEdit(DocumentBody(doc(p(t("edited offline")))), item.id, dek)
        SyncEngine(offline, content, drive, scope) { true }.syncNow()
        assertEquals(0, offline.pendingEditCount)
        assertEquals(doc(p(t("edited offline"))), content.loadContent(item).first.doc)

        // Organising: rename keeps the extension, duplicate under a fresh key, star, trash and restore.
        drive.rename(item.id, "Renamed")
        delay(500)
        assertEquals("Renamed.docx", api.fileMetadata(item.id).name)
        val copy = drive.duplicate(item.id)
        assertEquals(doc(p(t("edited offline"))), content.loadContent(copy).first.doc)
        drive.setStarred(item.id, true)
        delay(500)
        drive.load(DocsSection.FAVORITES)
        assertTrue(drive.items(DocsSection.FAVORITES).any { it.id == item.id })
        drive.load(DocsSection.RECENT)
        assertTrue(drive.items(DocsSection.RECENT).isNotEmpty())
        drive.delete(copy.id)
        delay(500)
        drive.load(DocsSection.TRASH)
        assertTrue(drive.items(DocsSection.TRASH).any { it.id == copy.id })
        drive.restore(copy.id)
        delay(500)
        drive.load(DocsSection.TRASH)
        assertFalse(drive.items(DocsSection.TRASH).any { it.id == copy.id })

        // The device list carries this session.
        assertTrue(api.sessions("JVM test - Neutrino Docs").any { it.isCurrentDevice })
        tmp.deleteRecursively()
    }
}
