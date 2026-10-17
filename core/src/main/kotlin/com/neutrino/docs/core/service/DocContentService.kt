package com.neutrino.docs.core.service

import com.neutrino.docs.core.api.DocsApi
import com.neutrino.docs.core.document.DocumentBody
import com.neutrino.docs.core.document.DocumentCodec
import com.neutrino.docs.core.editing.DriveImageReference
import com.neutrino.docs.core.model.DocFileInfo
import com.neutrino.docs.core.model.DocItem
import com.neutrino.shared.core.api.DriveDate
import com.neutrino.shared.core.api.FileDto
import com.neutrino.shared.core.crypto.FileCrypto
import com.neutrino.shared.core.keys.DeviceKeyCheck
import com.neutrino.shared.core.keys.KeyException
import com.neutrino.shared.core.keys.Keyring
import com.neutrino.shared.core.keys.openDek
import com.neutrino.shared.core.ooxml.DocxCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/** A document's DEK as the server holds it, with the identity version it is sealed to — meaningful only together. */
data class SealedFileKey(val sealed: String, val keyVersion: Int)

sealed class DocContentException(message: String) : Exception(message) {
    /**
     * The device holds an identity, but not the version this document's DEK was sealed to: not
     * "import your key" but "bring the older keys across".
     */
    class MissingKeyVersion(val version: Int) : DocContentException(
        "This document needs encryption key version $version, which this device does not have. On a device that holds " +
            "your key, open Settings › Encryption and back up your older keys, then reopen this app.",
    )
    class DecryptionFailed : DocContentException("Failed to decrypt the document.")
}

/**
 * Encrypts, uploads, downloads and decrypts the body of a document — Drive's E2EE protocol exactly
 * (secretstream content under a per-file DEK sealed to the account's key), through the shared
 * crypto, so a document made here opens on the web and the other way round.
 *
 * What a document stores is a `.docx`; [DocumentCodec] enters and leaves it just after decrypting
 * and just before encrypting. Nothing here logs a key, a sealed key or plaintext — ids and sizes
 * are the most it says about a document.
 */
class DocContentService(
    private val api: DocsApi,
    private val keyring: () -> Keyring?,
    private val keyCheck: DeviceKeyCheck,
    /** A small JPEG preview of picture bytes, base64 without a prefix; the server can't make one from ciphertext. */
    private val thumbnail: (ByteArray) -> String? = { null },
) : DocSyncing {
    private val log = Logger.getLogger("DocContentService")
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The outcome of a save: the server's view afterwards, and the bytes that produced it. */
    data class SaveResult(val updatedAt: Instant, val contentVersion: Long?, val encoded: ByteArray)

    // ── Create ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Creates a document, with content when `body` has any. A new file holds a real, empty `.docx`
     * rather than nothing, so it opens in Word before it has ever been edited. A copy gets a fresh
     * DEK: sharing a key between two files would make revoking one revoke both.
     */
    suspend fun createDocument(name: String, parentId: String?, body: DocumentBody? = null): DocItem {
        val (item, dek) = create(name, parentId)
        if (body == null || body.blocks.isEmpty()) return item
        val saved = saveContent(body, item, dek, item.contentVersion)
        return item.copy(modifiedAt = saved.updatedAt, contentVersion = saved.contentVersion, size = saved.encoded.size.toLong())
    }

    private suspend fun create(name: String, parentId: String?): Pair<DocItem, ByteArray> {
        val (entry, version) = keyCheck.sealingVersion()
        val dek = FileCrypto.generateDek()
        val content = withContext(Dispatchers.Default) {
            FileCrypto.encryptFile(DocumentCodec.encode(DocumentBody.empty, DocxCodec.strippingExtension(name)), dek)
        }
        val created = api.create(
            ciphertext = content, fileName = name, mimeType = DocItem.DOCUMENT_MIME, folderId = parentId,
            encryptedMetadata = FileCrypto.encryptMetadata(name, DocItem.DOCUMENT_MIME, dek),
            sealedFileKey = FileCrypto.sealDek(dek, entry.publicKey), keyVersion = version,
        )
        log.fine("createDocument: ${created.id}")
        return item(created) to dek
    }

    // ── Load ────────────────────────────────────────────────────────────────────────────────────

    /**
     * Downloads and decrypts a document. The DEK comes back with the body: hold it for the session
     * so autosaves need no re-unseal — and hold the body's layout, the only copy of its page setup.
     */
    suspend fun loadContent(item: DocItem): Pair<DocumentBody, ByteArray> {
        val sealed = fetchSealedKey(item.id) ?: throw DocContentException.DecryptionFailed()
        val dek = unsealDek(sealed, item.id)
        val stored = decrypt(api.download(item.id), dek)
        return withContext(Dispatchers.Default) { DocumentCodec.decode(stored) } to dek
    }

    /** The still-encrypted body and its sealed key, so the offline cache holds the server's bytes verbatim. */
    suspend fun downloadEncrypted(item: DocItem): Pair<ByteArray, SealedFileKey> {
        val sealed = fetchSealedKey(item.id) ?: throw DocContentException.DecryptionFailed()
        return api.download(item.id) to sealed
    }

    override suspend fun fileInfo(fileId: String): DocFileInfo? = api.fileInfo(fileId)

    // ── Save ────────────────────────────────────────────────────────────────────────────────────

    /**
     * Encrypts `body` under the document's DEK and autosaves it. `expectedContentVersion` makes the
     * server refuse the write if anything landed since ([DocsApi.ContentVersionConflict]); null saves
     * unguarded. The pictures the document points at go *into* the package, resolved here because
     * this is what can reach Drive for them.
     */
    override suspend fun saveContent(body: DocumentBody, item: DocItem, dek: ByteArray, expectedContentVersion: Long?): SaveResult {
        val images = imageBytes(body)
        val encoded = withContext(Dispatchers.Default) { DocumentCodec.encode(body, item.displayName, images) }
        val ciphertext = withContext(Dispatchers.Default) { FileCrypto.encryptFile(encoded, dek) }
        val updated = api.autosave(item.id, item.name, item.mimeType ?: DocItem.DOCUMENT_MIME, ciphertext, expectedContentVersion)
        return SaveResult(DriveDate.parse(updated.updatedAt) ?: Instant.now(), updated.contentVersion, encoded)
    }

    // ── Pictures ────────────────────────────────────────────────────────────────────────────────

    /** Decrypted picture bytes by `src`, memoised: an autosave every few seconds must not re-download photographs. */
    private val imageCache = ConcurrentHashMap<String, ByteArray>()

    /**
     * Bytes for every Drive picture in `body`, as far as they resolve. One that cannot be fetched is
     * left out and written as a placeholder that keeps the node — a save must not fail over a picture.
     */
    suspend fun imageBytes(body: DocumentBody): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        for (source in body.imageSources) {
            val fileId = DriveImageReference.fileId(source) ?: continue
            imageCache[source]?.let { out[source] = it; continue }
            runCatching { attachmentData(fileId) }.onSuccess { out[source] = it }.onFailure { log.fine("imageBytes: could not resolve $fileId") }
        }
        return out
    }

    /** The same without the network: what an offline save has to work from. */
    fun cachedImageBytes(body: DocumentBody): Map<String, ByteArray> =
        body.imageSources.mapNotNull { src -> imageCache[src]?.let { src to it } }.toMap()

    /**
     * Downloads and decrypts the picture a reference names. No key ref for this caller means the file
     * was never encrypted to them, and the download already is the picture — the web allows the same.
     */
    suspend fun attachmentData(fileId: String): ByteArray {
        val bytes = api.download(fileId)
        val sealed = fetchSealedKey(fileId)
        val picture = if (sealed != null) decrypt(bytes, unsealDek(sealed, fileId)) else bytes
        imageCache[DriveImageReference.source(fileId)] = picture
        return picture
    }

    /** The folder pictures go in — the same name the backend seeds and the web get-or-creates. */
    private val attachmentsLock = Mutex()
    private var attachmentsFolder: String? = null

    suspend fun attachmentsFolderId(): String = attachmentsLock.withLock {
        attachmentsFolder?.let { return it }
        val root = api.folder(null)
        val found = root.folders.firstOrNull { it.name.equals(ATTACHMENTS_FOLDER_NAME, ignoreCase = true) }?.id
            ?: api.createFolder(ATTACHMENTS_FOLDER_NAME, null).id
        attachmentsFolder = found
        found
    }

    /**
     * Uploads a picture into Attachments under a fresh DEK of its own and returns its file id — the
     * reference a document stores. A preview goes with it, made here so no caller can forget one.
     */
    suspend fun uploadAttachment(data: ByteArray, name: String, mimeType: String, onProgress: ((Double) -> Unit)? = null): String {
        val folderId = attachmentsFolderId()
        val (entry, version) = keyCheck.sealingVersion()
        val dek = FileCrypto.generateDek()
        val ciphertext = withContext(Dispatchers.Default) { FileCrypto.encryptFile(data, dek) }
        val preview = withContext(Dispatchers.Default) { thumbnail(data) }
        val created = api.create(
            ciphertext = ciphertext, fileName = name, mimeType = mimeType, folderId = folderId,
            encryptedMetadata = FileCrypto.encryptMetadata(name, mimeType, dek),
            sealedFileKey = FileCrypto.sealDek(dek, entry.publicKey), keyVersion = version,
            thumbnailBase64 = preview,
            onProgress = onProgress?.let { report -> { sent: Long, total: Long -> if (total > 0) report(sent.toDouble() / total) } },
        )
        imageCache[DriveImageReference.source(created.id)] = data
        return created.id
    }

    // ── Crypto ──────────────────────────────────────────────────────────────────────────────────

    fun encrypt(bytes: ByteArray, dek: ByteArray): ByteArray = FileCrypto.encryptFile(bytes, dek)

    fun decrypt(data: ByteArray, dek: ByteArray): ByteArray = try {
        FileCrypto.decryptFile(data, dek)
    } catch (e: FileCrypto.CryptoException) {
        throw DocContentException.DecryptionFailed()
    }

    private suspend fun fetchSealedKey(fileId: String): SealedFileKey? =
        api.fileKey(fileId)?.let { SealedFileKey(it.encryptedFileKey, it.keyVersion ?: 1) }

    /**
     * Opens a sealed DEK with whichever key this device holds, the named version first. A version the
     * device lacks is reported as such — the ciphertext is fine and one key can still be brought
     * across. A ref filed under the wrong version is re-filed in the background.
     */
    fun unsealDek(sealed: SealedFileKey, fileId: String? = null): ByteArray {
        val ring = keyring() ?: throw KeyException.NoEncryptionKey()
        val (dek, version) = ring.openDek(sealed.sealed, sealed.keyVersion)
            ?: throw if (ring.entry(sealed.keyVersion) == null) DocContentException.MissingKeyVersion(sealed.keyVersion) else DocContentException.DecryptionFailed()
        if (version != sealed.keyVersion && fileId != null) {
            log.info("refiling $fileId's key under v$version")
            background.launch { runCatching { api.setFileKey(fileId, sealed.sealed, version) } }
        }
        return dek
    }

    /** Forget everything decrypted this session: the pictures belong to the account that fetched them. */
    fun reset() {
        imageCache.clear()
        attachmentsFolder = null
    }

    companion object {
        const val ATTACHMENTS_FOLDER_NAME = "Attachments"

        fun item(file: FileDto, isShared: Boolean = false): DocItem = DocItem(
            id = file.id, name = file.name, type = DocItem.ItemType.FILE, parentId = file.folderId,
            size = file.sizeBytes, modifiedAt = DriveDate.parse(file.updatedAt) ?: Instant.EPOCH,
            mimeType = file.mimeType, isStarred = if (isShared) false else file.isStarred ?: false,
            contentVersion = file.contentVersion, isShared = isShared,
        )
    }
}
