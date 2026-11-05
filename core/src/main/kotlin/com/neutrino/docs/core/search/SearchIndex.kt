package com.neutrino.docs.core.search

import com.neutrino.docs.core.document.DocumentNodeType
import com.neutrino.docs.core.document.FrontMatter
import com.neutrino.docs.core.document.isFrontMatter
import com.neutrino.shared.core.ooxml.DocNode
import com.neutrino.shared.core.ooxml.stringValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.Collator
import java.text.Normalizer
import java.util.UUID

/**
 * Splits text into the terms the index stores, by the web's rules (`@neutrino/search`): NFC,
 * lowercase, anything neither a letter nor a number is a space. Two clients indexing the same
 * document must produce the same terms, or a query would mean different things on each.
 */
object SearchTokenizer {
    /** Shorter query terms are dropped: a one- or two-letter prefix matches most of the index. */
    const val MINIMUM_PREFIX_LENGTH = 3

    private fun isWordChar(cp: Int): Boolean = Character.isLetter(cp) || when (Character.getType(cp).toByte()) {
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
        else -> false
    }

    fun words(text: String): List<String> {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase()
        val out = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        while (i < normalized.length) {
            val cp = normalized.codePointAt(i)
            if (isWordChar(cp)) current.appendCodePoint(cp) else if (current.isNotEmpty()) { out += current.toString(); current.clear() }
            i += Character.charCount(cp)
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    fun normalize(text: String): List<String> = words(text).distinct()

    /** Every distinct term with the word offsets it appears at. */
    fun tokenize(text: String): Map<String, List<Int>> {
        val positions = LinkedHashMap<String, MutableList<Int>>()
        words(text).forEachIndexed { offset, word -> positions.getOrPut(word) { mutableListOf() } += offset }
        return positions
    }

    fun queryPrefixes(query: String): List<String> = normalize(query).filter { it.codePointCount(0, it.length) >= MINIMUM_PREFIX_LENGTH }
}

/** Fields indexed separately so a match in one outranks a match in another. */
@Serializable
enum class SearchField(val weight: Double) {
    @SerialName("title") TITLE(3.0),
    @SerialName("heading") HEADING(2.0),
    @SerialName("tag") TAG(2.0),
    @SerialName("content") CONTENT(1.0),
}

/**
 * What the index remembers about a document beside its terms. The text itself is deliberately not
 * kept — only a short preview — or the index would be a second copy of every document.
 */
@Serializable
data class IndexedDocument(
    val id: String,
    val title: String,
    val preview: String,
    val tags: List<String>,
    /** Null at the drive root. */
    val folderId: String? = null,
    val isShared: Boolean = false,
    val modifiedAt: Long,
    val indexedAt: Long,
) {
    companion object {
        const val PREVIEW_LIMIT = 240
    }
}

@Serializable
data class SearchPosting(val documentId: String, val field: SearchField, val frequency: Int)

/** Owner, folder (a set: a folder means everything beneath it), modified range, tags. */
@Serializable
data class SearchFilters(
    val ownership: Ownership = Ownership.ANY,
    val folderIds: Set<String>? = null,
    val modifiedAfter: Long? = null,
    val modifiedBefore: Long? = null,
    val tags: Set<String> = emptySet(),
) {
    @Serializable
    enum class Ownership(val title: String) {
        @SerialName("any") ANY("Anyone"), @SerialName("mine") MINE("Me"), @SerialName("shared") SHARED("Shared with me")
    }

    val isActive: Boolean get() = this != unfiltered

    fun matches(document: IndexedDocument): Boolean {
        when (ownership) {
            Ownership.ANY -> {}
            Ownership.MINE -> if (document.isShared) return false
            Ownership.SHARED -> if (!document.isShared) return false
        }
        // A root document has no folder, so it is in scope only when no folder is chosen.
        folderIds?.let { ids -> if (document.folderId == null || document.folderId !in ids) return false }
        modifiedAfter?.let { if (document.modifiedAt < it) return false }
        modifiedBefore?.let { if (document.modifiedAt > it) return false }
        if (tags.isNotEmpty()) {
            val documentTags = document.tags.map { it.lowercase() }.toSet()
            if (!tags.all { it.lowercase() in documentTags }) return false
        }
        return true
    }

    companion object {
        val unfiltered = SearchFilters()
    }
}

data class SearchResult(
    val documentId: String,
    val title: String,
    val preview: String,
    /** Which fields matched, so a result can say why it is one. */
    val matchedFields: Set<SearchField>,
    val score: Double,
    val modifiedAt: Long,
    val isShared: Boolean,
    /** Matched on its name only — the index has not read it yet. */
    val nameOnly: Boolean = false,
)

/**
 * The inverted index behind local search. Query terms are *prefixes* combined with AND — "mod bud"
 * finds a document with a word starting "mod" and one starting "bud" — the web engine's rule.
 * Pure, with no I/O; [SearchIndexStore] persists it.
 */
@Serializable
class SearchIndex(
    private val documents: MutableMap<String, IndexedDocument> = LinkedHashMap(),
    /** term → the documents and fields it occurs in. */
    private val postings: MutableMap<String, MutableList<SearchPosting>> = HashMap(),
) {
    @kotlinx.serialization.Transient
    private var sortedTerms: List<String>? = null

    val isEmpty: Boolean get() = documents.isEmpty()
    val documentCount: Int get() = documents.size
    val documentIds: Set<String> get() = documents.keys.toSet()

    /** Lowercased, so `Swift` and `swift` are one tag and the list does not change between launches. */
    val allTags: List<String>
        get() = documents.values.flatMap { it.tags }.map { it.lowercase() }.distinct().sortedWith(Collator.getInstance())

    fun document(id: String): IndexedDocument? = documents[id]

    /** Re-indexing is remove-then-add, so a term the document lost stops matching it. */
    fun index(document: IndexedDocument, fields: Map<SearchField, String>) {
        remove(document.id)
        documents[document.id] = document
        for ((field, text) in fields) {
            for ((term, positions) in SearchTokenizer.tokenize(text)) {
                postings.getOrPut(term) { mutableListOf() } += SearchPosting(document.id, field, positions.size)
            }
        }
        sortedTerms = null
    }

    fun remove(documentId: String) {
        if (documents.remove(documentId) == null) return
        val iterator = postings.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            entry.value.removeAll { it.documentId == documentId }
            if (entry.value.isEmpty()) iterator.remove()
        }
        sortedTerms = null
    }

    fun removeAll() {
        documents.clear()
        postings.clear()
        sortedTerms = null
    }

    fun search(query: String, filters: SearchFilters = SearchFilters.unfiltered, limit: Int = 50): List<SearchResult> {
        val prefixes = SearchTokenizer.queryPrefixes(query)
        if (prefixes.isEmpty()) return emptyList()
        val scores = HashMap<String, Double>()
        val matched = HashMap<String, MutableSet<SearchField>>()
        var candidates: Set<String>? = null
        for (prefix in prefixes) {
            val here = HashSet<String>()
            for (term in terms(prefix)) {
                for (posting in postings[term].orEmpty()) {
                    here += posting.documentId
                    scores[posting.documentId] = (scores[posting.documentId] ?: 0.0) + posting.frequency * posting.field.weight
                    matched.getOrPut(posting.documentId) { mutableSetOf() } += posting.field
                }
            }
            // AND across query terms.
            candidates = candidates?.intersect(here) ?: here
            if (candidates.isEmpty()) return emptyList()
        }
        return candidates.orEmpty().mapNotNull { id ->
            val doc = documents[id]?.takeIf(filters::matches) ?: return@mapNotNull null
            SearchResult(id, doc.title, doc.preview, matched[id].orEmpty(), scores[id] ?: 0.0, doc.modifiedAt, doc.isShared)
        }
            // Ties break on recency: two equally relevant documents are not equally interesting.
            .sortedWith(compareByDescending<SearchResult> { it.score }.thenByDescending { it.modifiedAt })
            .take(limit)
    }

    /** Documents matching the filters alone, for browsing by tag or folder with nothing typed. */
    fun browse(filters: SearchFilters, limit: Int = 50): List<SearchResult> =
        documents.values.filter(filters::matches).sortedByDescending { it.modifiedAt }.take(limit)
            .map { SearchResult(it.id, it.title, it.preview, emptySet(), 0.0, it.modifiedAt, it.isShared) }

    /** The indexed terms starting with `prefix`, by binary search over the sorted terms. */
    private fun terms(prefix: String): List<String> {
        val sorted = sortedTerms ?: postings.keys.sorted().also { sortedTerms = it }
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < prefix) lo = mid + 1 else hi = mid
        }
        var end = lo
        while (end < sorted.size && sorted[end].startsWith(prefix)) end++
        return sorted.subList(lo, end)
    }
}

/**
 * The four indexable fields, read straight out of the tree: a heading is a heading node. Code
 * blocks are skipped — a search for "class" means the prose — and front matter is read for the title
 * and tags rather than indexed as prose. Alt text counts: a described picture is searchable.
 */
data class DocumentSearchContent(val title: String, val headings: List<String>, val tags: List<String>, val content: String, val preview: String) {

    /** Headings are also content on purpose: a heading about the query outranks a passing mention. */
    val indexedFields: Map<SearchField, String>
        get() = mapOf(
            SearchField.TITLE to title,
            SearchField.HEADING to headings.joinToString(" "),
            SearchField.TAG to tags.joinToString(" "),
            SearchField.CONTENT to content,
        )

    companion object {
        fun extract(document: DocNode, fileName: String): DocumentSearchContent {
            val headings = ArrayList<String>()
            val words = ArrayList<String>()
            var frontMatter: String? = null
            var first = true
            fun collect(node: DocNode) {
                val wasFirst = first
                first = false
                if (DocumentNodeType.isCodeBlock(node.type)) {
                    if (wasFirst && node.isFrontMatter) frontMatter = text(node)
                    return
                }
                if (DocumentNodeType.isHeading(node.type)) text(node).trim().takeIf { it.isNotEmpty() }?.let { headings += it }
                if (node.type in DocumentNodeType.textBlocks) {
                    text(node).takeIf { it.isNotEmpty() }?.let { words += it }
                    return
                }
                node.children.forEach(::collect)
            }
            document.children.forEach(::collect)
            val meta = FrontMatter.read(frontMatter ?: "")
            val content = words.joinToString(" ")
            return DocumentSearchContent(meta.title ?: fileName, headings, meta.tags, content, preview(content))
        }

        /**
         * A text block's runs are one string — "he**ll**o" is the word "hello", not three — with a
         * picture's alt text standing apart as words of its own; blocks are separated by a space.
         */
        private fun text(node: DocNode): String {
            node.text?.let { return it }
            if (node.type == DocumentNodeType.IMAGE) return node.attr("alt")?.stringValue?.let { " $it " } ?: ""
            if (node.type in DocumentNodeType.textBlocks && !DocumentNodeType.isCodeBlock(node.type)) return node.children.joinToString("", transform = ::text).replace(WHITESPACE, " ").trim()
            return node.children.map(::text).filter { it.isNotEmpty() }.joinToString(" ")
        }

        private val WHITESPACE = Regex("\\s+")

        /** Cut at a word boundary so the preview does not end mid-word. */
        private fun preview(content: String): String {
            if (content.length <= IndexedDocument.PREVIEW_LIMIT) return content
            val cut = content.substring(0, IndexedDocument.PREVIEW_LIMIT)
            val space = cut.lastIndexOf(' ')
            return if (space < 0) "$cut…" else cut.substring(0, space) + "…"
        }
    }
}

/** A named query with its filters, kept encrypted beside the index — a query says what someone is looking for. */
@Serializable
data class SavedSearch(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val query: String,
    val filters: SearchFilters = SearchFilters.unfiltered,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis(),
) {
    /** The filters in words, so a saved search is legible without being opened. */
    val summary: String
        get() {
            val parts = ArrayList<String>()
            if (query.isNotEmpty()) parts += "\"$query\""
            if (filters.ownership != SearchFilters.Ownership.ANY) parts += filters.ownership.title
            if (filters.folderIds != null) parts += "in a folder"
            if (filters.tags.isNotEmpty()) parts += filters.tags.sorted().joinToString(" ") { "#$it" }
            if (filters.modifiedAfter != null || filters.modifiedBefore != null) parts += "by date"
            return parts.joinToString(" · ").ifEmpty { "Everything" }
        }
}
