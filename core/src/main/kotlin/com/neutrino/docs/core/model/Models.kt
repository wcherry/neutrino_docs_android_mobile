package com.neutrino.docs.core.model

import com.neutrino.shared.core.ooxml.DocxCodec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.Collator
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.math.pow

/** A folder or a Neutrino document in the user's Drive. */
data class DocItem(
    val id: String,
    val name: String,
    val type: ItemType,
    /** Null is the root of the drive. */
    val parentId: String?,
    val size: Long? = null,
    val modifiedAt: Instant,
    val isTrashed: Boolean = false,
    val mimeType: String? = null,
    /** Drive's `isStarred` — the Favorites model the web shares, stored on the row itself. */
    val isStarred: Boolean = false,
    /** Drive's `contentVersion` as last heard; sent back as `expectedContentVersion` so the server can refuse a stale write. */
    val contentVersion: Long? = null,
    /** Reached through `shared-with-me`: owned by somebody else. Everything else is owner-scoped server-side. */
    val isShared: Boolean = false,
) {
    enum class ItemType { FOLDER, FILE }

    val isFolder: Boolean get() = type == ItemType.FOLDER

    /** Whether this app opens the document's body for editing. */
    val isEditable: Boolean get() = type == ItemType.FILE && isDocument(mimeType)

    /** The title to show: `Report.docx` is called "Report", as the web library shows it. */
    val displayName: String get() = DocxCodec.strippingExtension(name)

    /** `Report.docx` → `Report copy.docx`: `Report.docx copy` is a file no operating system recognises. */
    val duplicateName: String get() = DocxCodec.withExtension("$displayName copy")

    companion object {
        /** A document created by any Neutrino client is a real Word document (issue #127). */
        const val DOCUMENT_MIME = DocxCodec.MIME_TYPE

        fun isDocument(mimeType: String?) = mimeType == DOCUMENT_MIME
    }
}

/** The browser's sections. */
enum class DocsSection(val title: String) {
    HOME("Home"), RECENT("Recent"), FAVORITES("Favorites"), SHARED("Shared"), TRASH("Trash");

    /** Only Home navigates folders: Recent and Favorites cut across the tree, and someone else's folders cannot be listed. */
    val supportsFolderNavigation: Boolean get() = this == HOME

    /** Recent is newest-first by definition; everywhere else a browser reads A–Z. */
    val defaultSortOrder: DocSortOrder get() = if (this == RECENT) DocSortOrder.NEWEST else DocSortOrder.NAME_ASCENDING

    val emptyMessage: String
        get() = when (this) {
            HOME -> "No documents yet. Tap + to create one."
            RECENT -> "Documents you open or edit will appear here."
            FAVORITES -> "Star a document to keep it here."
            SHARED -> "Documents other people share with you will appear here."
            TRASH -> "Trash is empty."
        }
}

/** How a listing is ordered; stored by name so it survives reordering. */
enum class DocSortOrder(val title: String) {
    NEWEST("Newest First"), OLDEST("Oldest First"), NAME_ASCENDING("Name (A–Z)"), NAME_DESCENDING("Name (Z–A)");

    /**
     * Folders ahead of documents in every order; by the title the row shows, so the hidden `.docx`
     * cannot decide where a document lands; ties broken by name and then id, so a reload cannot shuffle.
     */
    fun sorted(items: List<DocItem>): List<DocItem> {
        val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.SECONDARY }
        return items.sortedWith { lhs, rhs ->
            if (lhs.type != rhs.type) return@sortedWith if (lhs.isFolder) -1 else 1
            if (this == NEWEST && lhs.modifiedAt != rhs.modifiedAt) return@sortedWith rhs.modifiedAt.compareTo(lhs.modifiedAt)
            if (this == OLDEST && lhs.modifiedAt != rhs.modifiedAt) return@sortedWith lhs.modifiedAt.compareTo(rhs.modifiedAt)
            val byName = collator.compare(lhs.displayName, rhs.displayName)
            if (byName != 0) return@sortedWith if (this == NAME_DESCENDING) -byName else byName
            lhs.id.compareTo(rhs.id)
        }
    }
}

/** What the signed-in account may do with a document, from `yourRole` on `/files/{id}/info`. */
enum class ShareRole(val title: String) {
    OWNER("Owner"), EDITOR("Editor"), COMMENTER("Commenter"), VIEWER("Viewer");

    val canEdit: Boolean get() = this == OWNER || this == EDITOR
    /** Rename, move, trash, star and versions are the owner's. */
    val canManage: Boolean get() = this == OWNER

    companion object {
        /** An unknown role reads as the least privileged: read-only is the safe misreading. */
        fun of(server: String?): ShareRole = entries.firstOrNull { it.name.lowercase() == server?.lowercase() } ?: VIEWER
    }
}

/** `/files/{id}/info`: the one endpoint that answers for a file the caller can *access*, not only one it owns. */
data class DocFileInfo(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val folderId: String?,
    val mimeType: String?,
    val updatedAt: Instant,
    val deletedAt: Instant?,
    val yourRole: ShareRole,
    val contentVersion: Long?,
) {
    /** Still somewhere an edit can land. */
    val isLive: Boolean get() = deletedAt == null
}

/** One snapshot in a document's version history. */
data class DocVersion(
    val id: String,
    val fileId: String,
    val versionNumber: Int,
    val sizeBytes: Long,
    val label: String?,
    val createdAt: Instant,
    val isNamed: Boolean,
) {
    val displayTitle: String get() = if (label.isNullOrBlank()) "Version $versionNumber" else "$label (v$versionNumber)"
    val shortTitle: String get() = "v$versionNumber"
}

/** A device registered against the account — the session row signing in writes. */
data class DeviceSession(
    val id: String,
    val deviceName: String?,
    val userAgent: String?,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
    val isCurrentDevice: Boolean,
)

/** Word and character counts and the reading time the status bar shows. */
data class DocTextStats(val wordCount: Int, val characterCount: Int, val readingTimeMinutes: Int) {
    val readingTimeText: String get() = if (readingTimeMinutes <= 1) "1 min read" else "$readingTimeMinutes min read"

    val summaryText: String
        get() = "${if (wordCount == 1) "1 word" else "$wordCount words"} · " +
            "${if (characterCount == 1) "1 character" else "$characterCount characters"} · $readingTimeText"

    companion object {
        /** 200 wpm, the low end of the usual range: an estimate that runs long is the kinder error. */
        const val WORDS_PER_MINUTE = 200.0

        fun compute(text: String): DocTextStats {
            val words = text.split(Regex("\\s+")).count { it.isNotEmpty() }
            val minutes = if (words == 0) 0 else maxOf(1, kotlin.math.ceil(words / WORDS_PER_MINUTE).toInt())
            return DocTextStats(words, text.codePointCount(0, text.length), minutes)
        }
    }
}

// ── Offline cache ───────────────────────────────────────────────────────────────────────────────

/** Why a document is cached. Pinned ones are the user's choice and are never evicted automatically. */
@Serializable
enum class CacheReason(val title: String) {
    @SerialName("pinned") PINNED("Kept offline"),
    @SerialName("opened") OPENED("Opened"),
    @SerialName("recent") RECENT("Recent"),
    @SerialName("favorite") FAVORITE("Favorite");

    val isEvictable: Boolean get() = this != PINNED
}

/** Exponential retry backoff: 0s, 5s, 15s, 45s, 135s, … capped at 300s. Pure — no clock. */
object SyncBackoff {
    const val MAXIMUM_DELAY_SECONDS = 300.0

    fun delaySeconds(attempt: Int): Double {
        if (attempt <= 0) return 0.0
        return minOf(5 * 3.0.pow(attempt - 1), MAXIMUM_DELAY_SECONDS)
    }
}

/** A body edit not yet on the server. Times are epoch milliseconds. */
@Serializable
data class PendingEdit(
    val editedAt: Long,
    /** The server version this edit was written against; a server past it is a conflict. */
    val baseServerModifiedAt: Long,
    /** The same base as Drive's `contentVersion` — what the server is asked to guard the write with. */
    val baseContentVersion: Long? = null,
    val attemptCount: Int = 0,
    val lastError: String? = null,
    val lastAttemptAt: Long? = null,
) {
    /** Measured from the last failure, so an old edit is still held back between attempts. */
    val nextAttemptAt: Long get() = (lastAttemptAt ?: editedAt) + (SyncBackoff.delaySeconds(attemptCount) * 1000).toLong()
}

/** The server moved past the version a pending edit was based on. */
@Serializable
data class Conflict(val detectedAt: Long, val serverModifiedAt: Long, val serverContentVersion: Long? = null)

/**
 * A document cached for offline use: `<id>.bin` is the server's ciphertext byte for byte and
 * `<id>.pending.bin` a local edit under the same DEK. Nothing is stored as plaintext.
 */
@Serializable
data class OfflineDoc(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val mimeType: String? = null,
    /** base64url `crypto_box_seal`, exactly as `/files/{id}/key` returned it. */
    val sealedDek: String,
    /** The identity version it is sealed to — an offline read cannot ask the server. */
    val keyVersion: Int = 1,
    val serverModifiedAt: Long,
    val serverContentVersion: Long? = null,
    val cachedAt: Long,
    /** Plaintext size, for display only. */
    val sizeBytes: Long,
    val pendingEdit: PendingEdit? = null,
    val conflict: Conflict? = null,
    val cacheReason: CacheReason = CacheReason.OPENED,
) {
    val displayName: String get() = DocxCodec.strippingExtension(name)

    val asDocItem: DocItem
        get() = DocItem(
            id = id, name = name, type = DocItem.ItemType.FILE, parentId = parentId, size = sizeBytes,
            modifiedAt = Instant.ofEpochMilli(serverModifiedAt), mimeType = mimeType ?: DocItem.DOCUMENT_MIME,
            contentVersion = serverContentVersion,
        )
}

/** A metadata change made offline, replayed in order when the connection is back. */
@Serializable
data class PendingMutation(
    val id: String = UUID.randomUUID().toString(),
    val itemId: String,
    val kind: Kind,
    val isFolder: Boolean,
    /** For a move; null is the drive root. */
    val targetFolderId: String? = null,
    /** For a rename. */
    val newName: String? = null,
    val queuedAt: Long,
    val attemptCount: Int = 0,
    val lastError: String? = null,
    val lastAttemptAt: Long? = null,
) {
    @Serializable
    enum class Kind { @SerialName("delete") DELETE, @SerialName("move") MOVE, @SerialName("rename") RENAME }

    val nextAttemptAt: Long get() = (lastAttemptAt ?: queuedAt) + (SyncBackoff.delaySeconds(attemptCount) * 1000).toLong()

    val summary: String
        get() = when (kind) {
            Kind.DELETE -> "Delete"
            Kind.MOVE -> if (targetFolderId == null) "Move to Drive root" else "Move"
            Kind.RENAME -> "Rename to $newName"
        }

    /** A delete makes an earlier move or rename pointless; two moves or two renames collapse to the last. */
    fun supersedes(other: PendingMutation): Boolean {
        if (other.itemId != itemId) return false
        return kind == Kind.DELETE || kind == other.kind
    }
}
