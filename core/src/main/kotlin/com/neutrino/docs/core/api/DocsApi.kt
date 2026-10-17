package com.neutrino.docs.core.api

import com.neutrino.docs.core.model.DeviceSession
import com.neutrino.docs.core.model.DocFileInfo
import com.neutrino.docs.core.model.DocVersion
import com.neutrino.docs.core.model.ShareRole
import com.neutrino.shared.core.api.ApiException
import com.neutrino.shared.core.api.AuthClient
import com.neutrino.shared.core.api.DriveDate
import com.neutrino.shared.core.api.FileDto
import com.neutrino.shared.core.api.FolderContentsDto
import com.neutrino.shared.core.api.FolderDto
import com.neutrino.shared.core.api.NeutrinoApi
import com.neutrino.shared.core.api.apiJson
import com.neutrino.shared.core.api.httpError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.time.Instant

/**
 * Docs has no backend of its own: everything is Drive's folder, file, trash, version and key APIs,
 * plus the account's session list. The base [NeutrinoApi] holds the token path and the calls every
 * Neutrino app shares.
 */
class DocsApi(http: OkHttpClient, auth: AuthClient) : NeutrinoApi(http, auth) {

    // ── Listings ────────────────────────────────────────────────────────────────────────────────

    /**
     * One folder's contents in Drive's `office` category, the narrowest filter that provably holds
     * a `.docx` (`type=doc` is pinned to the bespoke JSON that predates OOXML, and lists a drive full
     * of documents as empty). The caller narrows to documents.
     *
     * Only the root is paged: a page cut from the whole office category before narrowing can come
     * back empty while the folder holds documents further down, so a subfolder is asked for whole.
     */
    suspend fun documentFolder(folderId: String?): FolderContentsDto {
        val u = if (folderId != null) {
            url("/drive/folders/$folderId").newBuilder().addQueryParameter("type", DOCUMENT_TYPE_FILTER).build()
        } else {
            url("/drive/folders/${rootFolderId()}").newBuilder()
                .addQueryParameter("limit", LISTING_PAGE_SIZE.toString()).addQueryParameter("offset", "0")
                .addQueryParameter("orderBy", "createdAt").addQueryParameter("direction", "desc")
                .addQueryParameter("type", DOCUMENT_TYPE_FILTER).build()
        }
        return live(json(FolderContentsDto.serializer(), get(u)))
    }

    /** Pictures in a folder, for the insert-image picker: `type=photo` is the server's `image/%` filter. */
    suspend fun imageFolder(folderId: String?): FolderContentsDto {
        val u = if (folderId != null) {
            url("/drive/folders/$folderId").newBuilder().addQueryParameter("type", "photo").build()
        } else {
            url("/drive/folders/${rootFolderId()}").newBuilder()
                .addQueryParameter("limit", "200").addQueryParameter("offset", "0")
                .addQueryParameter("orderBy", "createdAt").addQueryParameter("direction", "desc")
                .addQueryParameter("type", "photo").build()
        }
        return live(json(FolderContentsDto.serializer(), get(u)))
    }

    suspend fun recent(type: String = DOCUMENT_TYPE_FILTER, limit: Int = LISTING_PAGE_SIZE): FolderContentsDto =
        live(json(FolderContentsDto.serializer(), get(url("/drive/recent").newBuilder().addQueryParameter("type", type).addQueryParameter("limit", limit.toString()).build())))

    suspend fun starred(): FolderContentsDto =
        live(json(FolderContentsDto.serializer(), get(url("/drive/starred").newBuilder().addQueryParameter("type", DOCUMENT_TYPE_FILTER).build())))

    suspend fun sharedWithMe(): FolderContentsDto =
        live(json(FolderContentsDto.serializer(), get(url("/drive/shared-with-me").newBuilder().addQueryParameter("type", DOCUMENT_TYPE_FILTER).build())))

    suspend fun trash(): TrashContentsDto =
        json(TrashContentsDto.serializer(), get(url("/drive/trash").newBuilder().addQueryParameter("type", DOCUMENT_TYPE_FILTER).build()))

    private fun live(listing: FolderContentsDto) = listing.copy(files = listing.files.filter { it.deletedAt.isNullOrEmpty() })

    // ── One file ────────────────────────────────────────────────────────────────────────────────

    /**
     * `/files/{id}/info` — the one endpoint answering for a file the caller can *access*, with its
     * role. Null when the file is gone (404) or not shared with this account (403).
     */
    suspend fun fileInfo(fileId: String): DocFileInfo? {
        val dto = try {
            json(FileInfoDto.serializer(), get("/drive/files/$fileId/info"))
        } catch (e: ApiException.Http) {
            if (e.status == 404 || e.status == 403) return null
            throw e
        }
        return DocFileInfo(
            id = dto.id, name = dto.name, sizeBytes = dto.sizeBytes ?: 0, folderId = dto.folderId, mimeType = dto.mimeType,
            updatedAt = DriveDate.parse(dto.updatedAt) ?: Instant.EPOCH, deletedAt = DriveDate.parse(dto.deletedAt),
            // Absent means the caller owns it: `/info` omits the field for a file in their own drive.
            yourRole = ShareRole.of(dto.yourRole ?: "owner"), contentVersion = dto.contentVersion,
        )
    }

    // ── Organising ──────────────────────────────────────────────────────────────────────────────

    suspend fun updateFolder(id: String, name: String? = null, isStarred: Boolean? = null): FolderDto =
        json(FolderDto.serializer(), method("PATCH", "/drive/folders/$id", buildJsonObject {
            name?.let { put("name", it) }
            isStarred?.let { put("isStarred", it) }
        }))

    suspend fun updateFile(id: String, name: String? = null, isStarred: Boolean? = null): FileDto =
        json(FileDto.serializer(), method("PATCH", "/drive/files/$id", buildJsonObject {
            name?.let { put("name", it) }
            isStarred?.let { put("isStarred", it) }
        }))

    suspend fun bulkTrash(fileIds: List<String>, folderIds: List<String>) {
        text(method("POST", "/drive/bulk/trash", buildJsonObject {
            put("fileIds", buildJsonArray { fileIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            put("folderIds", buildJsonArray { folderIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        }))
    }

    suspend fun bulkMove(fileIds: List<String>, folderIds: List<String>, targetFolderId: String?) {
        text(method("POST", "/drive/bulk/move", buildJsonObject {
            put("fileIds", buildJsonArray { fileIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            put("folderIds", buildJsonArray { folderIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            if (targetFolderId != null) put("targetFolderId", targetFolderId) else put("targetFolderId", JsonNull)
        }))
    }

    suspend fun restoreFromTrash(id: String, isFolder: Boolean) {
        text(method("POST", "/drive/trash/${if (isFolder) "folders" else "files"}/$id/restore", null as String?))
    }

    suspend fun deleteForever(id: String, isFolder: Boolean) {
        text(method("DELETE", "/drive/trash/${if (isFolder) "folders" else "files"}/$id", null as String?))
    }

    suspend fun emptyTrash() {
        text(method("DELETE", "/drive/trash", null as String?))
    }

    // ── Content ─────────────────────────────────────────────────────────────────────────────────

    /** A file's stored bytes — ciphertext for an encrypted one. */
    suspend fun download(fileId: String, versionId: String? = null): ByteArray = openBlob(fileId, versionId).use { response ->
        withContext(Dispatchers.IO) { response.body?.bytes() ?: ByteArray(0) }
    }

    /**
     * Creates a file. **Part order is load-bearing**: the server reads scalar fields as it walks the
     * stream and stops at the file part, so every field precedes the blob. The sealed key rides in
     * the same request, so a document can never exist without the key that opens it.
     */
    suspend fun create(
        ciphertext: ByteArray,
        fileName: String,
        mimeType: String,
        folderId: String?,
        encryptedMetadata: String,
        sealedFileKey: String,
        keyVersion: Int,
        thumbnailBase64: String? = null,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): FileDto {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            addFormDataPart("encrypted_metadata", encryptedMetadata)
            folderId?.let { addFormDataPart("folder_id", it) }
            thumbnailBase64?.let { addFormDataPart("thumbnail_b64", it) }
            addFormDataPart("encrypted_file_key", sealedFileKey)
            addFormDataPart("key_version", keyVersion.toString())
            addFormDataPart("file", fileName, bytesBody(ciphertext, mimeType, onProgress))
        }.build()
        return upload(body)
    }

    /**
     * The autosave endpoint: the same multipart `file` part as create. `expectedContentVersion` makes
     * the server refuse the write atomically if anything landed since — the only way to close the
     * gap between checking and writing. Autosave does not snapshot a version.
     */
    suspend fun autosave(fileId: String, fileName: String, mimeType: String, ciphertext: ByteArray, expectedContentVersion: Long?): FileDto {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, bytesBody(ciphertext, mimeType, null)).build()
        val u = url("/drive/files/$fileId/autosave").newBuilder().apply {
            expectedContentVersion?.let { addQueryParameter("expectedContentVersion", it.toString()) }
        }.build()
        return withContext(Dispatchers.IO) {
            send { Request.Builder().url(u).put(body) }.use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    if (response.code == 409 && isContentVersionConflict(text)) throw ContentVersionConflict(currentVersion(text))
                    throw httpError(response.code, text)
                }
                apiJson.decodeFromString(FileDto.serializer(), text)
            }
        }
    }

    // ── Versions ────────────────────────────────────────────────────────────────────────────────

    suspend fun versions(fileId: String): List<DocVersion> =
        json(VersionsDto.serializer(), get("/drive/files/$fileId/versions")).versions.map { it.model() }

    /** A named snapshot; the server also makes it the current content, like the web's explicit save. */
    suspend fun saveVersion(fileId: String, fileName: String, mimeType: String, ciphertext: ByteArray, label: String?): DocVersion {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            label?.let { addFormDataPart("label", it) }
            addFormDataPart("file", fileName, bytesBody(ciphertext, mimeType, null))
        }.build()
        return json(VersionDto.serializer()) { Request.Builder().url(url("/drive/files/$fileId/versions")).post(body) }.model()
    }

    /** The server snapshots the pre-restore content first, so a restore is itself undoable. */
    suspend fun restoreVersion(fileId: String, versionId: String): FileDto =
        json(FileDto.serializer(), method("POST", "/drive/files/$fileId/versions/$versionId/restore", null as String?))

    // ── Devices ─────────────────────────────────────────────────────────────────────────────────

    /** The account's sessions. The server does not mark the caller's own; the registered name is the only join. */
    suspend fun sessions(currentDeviceName: String): List<DeviceSession> =
        json(SessionsDto.serializer(), get("/auth/sessions")).sessions.map {
            DeviceSession(it.id, it.deviceName, it.userAgent, DriveDate.parse(it.createdAt) ?: Instant.EPOCH, DriveDate.parse(it.lastUsedAt),
                isCurrentDevice = it.deviceName == currentDeviceName)
        }

    suspend fun revokeSession(id: String) {
        text(method("DELETE", "/auth/sessions/$id", null as String?))
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    /** The server rejected the write because the document moved on; `current` is its version, when stated. */
    class ContentVersionConflict(val current: Long?) :
        Exception("This document changed on another device since it was opened. Reload to get the latest version, or keep your copy.")

    companion object {
        const val DOCUMENT_TYPE_FILTER = "office"
        const val LISTING_PAGE_SIZE = 500

        /** A 409 is a version conflict only when the body says so: Drive uses 409 for other faults too. */
        fun isContentVersionConflict(body: String): Boolean = runCatching {
            apiJson.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content == "CONTENT_VERSION_CONFLICT"
        }.getOrDefault(false)

        /** The server states the version it holds in the message: "… found 7". A nicety; callers tolerate null. */
        fun currentVersion(body: String): Long? = runCatching {
            val message = apiJson.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: return null
            message.substringAfter("found ", "").takeWhile { it.isDigit() }.toLongOrNull()
        }.getOrNull()

        private val octets = "application/octet-stream".toMediaType()

        /** A byte body that reports bytes written, so a multi-megabyte picture's upload is measurable. */
        fun bytesBody(bytes: ByteArray, mimeType: String, onProgress: ((Long, Long) -> Unit)?): RequestBody {
            val type = mimeType.toMediaTypeOrNull() ?: octets
            if (onProgress == null) return bytes.toRequestBody(type)
            return object : RequestBody() {
                override fun contentType() = type
                override fun contentLength() = bytes.size.toLong()
                override fun writeTo(sink: BufferedSink) {
                    val total = bytes.size.toLong()
                    var sent = 0
                    while (sent < bytes.size) {
                        val n = minOf(64 * 1024, bytes.size - sent)
                        sink.write(bytes, sent, n)
                        sent += n
                        onProgress(sent.toLong(), total)
                    }
                }
            }
        }
    }
}

// Shapes from `neutrino/src/drive/storage/dto.rs` and `auth/dto.rs`; camelCase on the wire.

@Serializable
data class FileInfoDto(
    val id: String,
    val name: String,
    val sizeBytes: Long? = null,
    val folderId: String? = null,
    val mimeType: String? = null,
    val updatedAt: String? = null,
    val deletedAt: String? = null,
    val yourRole: String? = null,
    val contentVersion: Long? = null,
)

@Serializable
data class TrashFileDto(val id: String, val name: String, val sizeBytes: Long? = null, val mimeType: String? = null, val deletedAt: String? = null)

@Serializable
data class TrashFolderDto(val id: String, val name: String, val deletedAt: String? = null)

@Serializable
data class TrashContentsDto(val files: List<TrashFileDto> = emptyList(), val folders: List<TrashFolderDto> = emptyList())

@Serializable
data class VersionDto(
    val id: String,
    val fileId: String,
    val versionNumber: Int,
    val sizeBytes: Long = 0,
    val label: String? = null,
    val createdAt: String,
    val isNamed: Boolean = false,
) {
    fun model() = DocVersion(id, fileId, versionNumber, sizeBytes, label, DriveDate.parse(createdAt) ?: Instant.EPOCH, isNamed)
}

@Serializable
data class VersionsDto(val versions: List<VersionDto> = emptyList())

@Serializable
data class SessionDto(
    val id: String,
    val deviceName: String? = null,
    val userAgent: String? = null,
    val createdAt: String? = null,
    val lastUsedAt: String? = null,
)

@Serializable
data class SessionsDto(val sessions: List<SessionDto> = emptyList())
