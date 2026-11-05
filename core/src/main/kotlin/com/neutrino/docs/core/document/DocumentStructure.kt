package com.neutrino.docs.core.document

import com.neutrino.shared.core.ooxml.DocNode
import com.neutrino.shared.core.ooxml.boolValue
import com.neutrino.shared.core.ooxml.intValue
import com.neutrino.shared.core.ooxml.stringValue
import java.text.Normalizer

/** Lines of the projected text: content ranges between block separators. */
object TextLines {

    /** The content range of every line, in order. */
    fun ranges(text: AttributedText): List<DocRange> = ranges(text.string)

    fun ranges(string: String): List<DocRange> {
        val result = ArrayList<DocRange>()
        var start = 0
        while (start <= string.length) {
            val separator = string.indexOf(DocumentProjection.BLOCK_SEPARATOR, start)
            val end = if (separator < 0) string.length else separator
            result += DocRange.of(start, end)
            if (separator < 0) break
            start = separator + 1
        }
        return result
    }

    /**
     * The lines `range` touches. A caret on a boundary belongs to the line it opens; a selection that
     * merely ends where a line starts has not reached into it.
     */
    fun covering(text: AttributedText, range: DocRange): List<DocRange> {
        val result = ranges(text).filter { content ->
            if (range.length == 0) range.location >= content.location && range.location <= content.end
            else range.location < content.end + 1 && range.end > content.location
        }
        return result.ifEmpty { listOf(DocRange(0, 0)) }
    }

    /** The block a line belongs to, a bare paragraph for an empty document. */
    fun block(line: DocRange, text: AttributedText): DocumentBlock {
        if (line.length == 0 && line.location == text.length) text.trailingBlock?.let { return it }
        if (text.length == 0) return DocumentBlock.defaultParagraph
        return text.block(minOf(line.location, text.length - 1)) ?: DocumentBlock.defaultParagraph
    }

    /** The index of the line containing `offset`. */
    fun lineIndex(text: AttributedText, offset: Int): Int {
        var line = 0
        val s = text.string
        val limit = minOf(offset, s.length)
        for (i in 0 until limit) if (s[i] == '\n') line++
        return line
    }
}

/**
 * A document's metadata block: flat `key: value` pairs, kept as a leading `yaml` code block (the
 * web's spelling). Only `title` and `tags` have a meaning, which the search index reads. A small
 * subset on purpose — scalars, inline lists, dash lists — not a YAML engine.
 */
data class FrontMatter(val entries: List<Entry>) {

    data class Entry(val key: String, val values: List<String>) {
        val scalar: String? get() = values.singleOrNull()
    }

    val isEmpty: Boolean get() = entries.isEmpty()

    operator fun get(key: String): List<String>? = entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.values

    val title: String? get() = get("title")?.firstOrNull()?.takeIf { it.isNotEmpty() }
    val tags: List<String> get() = get("tags") ?: emptyList()

    companion object {
        val empty = FrontMatter(emptyList())

        fun read(yaml: String): FrontMatter {
            val entries = ArrayList<Entry>()
            var pendingKey: String? = null
            var pendingValues = ArrayList<String>()
            fun flush() {
                pendingKey?.let { entries += Entry(it, pendingValues) }
                pendingKey = null
                pendingValues = ArrayList()
            }
            for (raw in yaml.split("\n")) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                // A dash item belongs to the key above it.
                if (line.startsWith("- ") && pendingKey != null) {
                    pendingValues += unquote(line.substring(2))
                    continue
                }
                val colon = line.indexOf(':')
                if (colon < 0) { flush(); continue }
                flush()
                val key = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim()
                if (key.isEmpty()) continue
                if (value.isEmpty()) { pendingKey = key; pendingValues = ArrayList(); continue }
                entries += Entry(key, values(value))
            }
            flush()
            return FrontMatter(entries)
        }

        private fun values(raw: String): List<String> {
            if (raw.startsWith("[") && raw.endsWith("]")) {
                val inner = raw.substring(1, raw.length - 1)
                return if (inner.isBlank()) emptyList() else splitOnCommas(inner)
            }
            // `tags: a, b` reads as a list, unless quoted.
            if (!isQuoted(raw) && ',' in raw) return splitOnCommas(raw)
            return listOf(unquote(raw))
        }

        private fun splitOnCommas(s: String) = s.split(",").map { unquote(it.trim()) }.filter { it.isNotEmpty() }

        private fun isQuoted(s: String) = s.length >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))

        private fun unquote(s: String) = if (isQuoted(s)) s.substring(1, s.length - 1) else s
    }
}

/** The attribute marking the code block that holds front matter. */
const val FRONT_MATTER_ATTRIBUTE = "neutrinoFrontMatter"

val DocNode.isFrontMatter: Boolean
    get() {
        if (!DocumentNodeType.isCodeBlock(type)) return false
        if (attr(FRONT_MATTER_ATTRIBUTE)?.boolValue == true) return true
        return attr("language")?.stringValue == "yaml"
    }

/** The document's front matter: its leading block, when it is one. */
val DocNode.frontMatter: FrontMatter
    get() {
        val first = children.firstOrNull() ?: return FrontMatter.empty
        return if (first.isFrontMatter) FrontMatter.read(first.plainText) else FrontMatter.empty
    }

/**
 * The heading tree behind the Outline: navigation, collapsing, search. Built from the projected
 * text, because that is where both halves exist — which blocks are headings, and where each begins.
 */
data class DocumentOutline(val headings: List<Heading>) {

    data class Heading(
        /** Position among all headings; unique even when two headings share a title. */
        val id: Int,
        val level: Int,
        val title: String,
        val line: Int,
        /** Offset of the heading's line in the editor's text, for moving the caret there. */
        val characterOffset: Int,
        val childIds: List<Int> = emptyList(),
        val parentId: Int? = null,
    )

    val isEmpty: Boolean get() = headings.isEmpty()

    fun heading(id: Int): Heading? = headings.firstOrNull { it.id == id }

    /**
     * Case- and accent-insensitive search over titles, keeping each match's ancestors so a deep
     * match stays reachable through its parents.
     */
    fun filtered(query: String): List<Heading> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return headings
        val needle = fold(trimmed)
        val keep = HashSet<Int>()
        for (heading in headings) {
            if (!fold(heading.title).contains(needle)) continue
            keep += heading.id
            var parent = heading.parentId
            while (parent != null) {
                keep += parent
                parent = heading(parent)?.parentId
            }
        }
        return headings.filter { it.id in keep }
    }

    /** Every heading beneath `id`, at any depth — what a collapsed section hides. */
    fun descendantIds(id: Int): List<Int> {
        val heading = heading(id) ?: return emptyList()
        return heading.childIds.flatMap { listOf(it) + descendantIds(it) }
    }

    fun visibleHeadings(collapsed: Set<Int>): List<Heading> {
        val hidden = collapsed.flatMap(::descendantIds).toSet()
        return headings.filter { it.id !in hidden }
    }

    companion object {
        val empty = DocumentOutline(emptyList())

        private fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

        fun of(text: AttributedText): DocumentOutline {
            if (text.length == 0) return empty
            val flat = ArrayList<Heading>()
            TextLines.ranges(text).forEachIndexed { index, line ->
                val level = headingLevel(TextLines.block(line, text))
                if (level > 0) flat += Heading(flat.size, level, text.substring(line).trim(), index, line.location)
            }
            return DocumentOutline(link(flat))
        }

        fun of(document: DocNode): DocumentOutline = of(DocumentProjection.project(document))

        fun headingLevel(block: DocumentBlock): Int =
            if (DocumentNodeType.isHeading(block.leafType)) block.attrs?.get("level")?.intValue ?: 1 else 0

        /** Parents by a level stack, so a skipped level (an H4 straight under an H2) still nests. */
        private fun link(flat: List<Heading>): List<Heading> {
            val headings = flat.toMutableList()
            val children = HashMap<Int, MutableList<Int>>()
            val stack = ArrayList<Int>()
            for (index in headings.indices) {
                while (stack.isNotEmpty() && headings[stack.last()].level >= headings[index].level) stack.removeAt(stack.lastIndex)
                stack.lastOrNull()?.let { parent ->
                    headings[index] = headings[index].copy(parentId = headings[parent].id)
                    children.getOrPut(parent) { mutableListOf() } += headings[index].id
                }
                stack += index
            }
            return headings.mapIndexed { i, h -> h.copy(childIds = children[i].orEmpty()) }
        }
    }
}

/**
 * Something drawn beside a line that is not part of its text: a bullet is drawn because the line's
 * block says it is a list item — there is no `- ` to show, because the document is a tree.
 */
data class DocumentDecoration(val line: Int, val kind: Kind) {
    sealed class Kind {
        data class Bullet(val depth: Int) : Kind()
        data class OrderedMarker(val label: String, val depth: Int) : Kind()
        data class Checkbox(val checked: Boolean, val depth: Int) : Kind()
        /** One bar per level of quoting. */
        data class QuoteBar(val depth: Int) : Kind()
        object CodeBlock : Kind()
        object ThematicBreak : Kind()
    }

    companion object {
        /** Every decoration the text calls for, in line order. A marker belongs to an item's first line only. */
        fun of(text: AttributedText): List<DocumentDecoration> {
            val result = ArrayList<DocumentDecoration>()
            var previousItemId: Int? = null
            TextLines.ranges(text).forEachIndexed { index, line ->
                val block = TextLines.block(line, text)
                val quoteDepth = block.levels.dropLast(1).count { it.type == DocumentNodeType.BLOCKQUOTE }
                if (quoteDepth > 0) result += DocumentDecoration(index, Kind.QuoteBar(quoteDepth))
                if (DocumentNodeType.isCodeBlock(block.leafType)) result += DocumentDecoration(index, Kind.CodeBlock)
                if (DocumentNodeType.isRule(block.leafType)) result += DocumentDecoration(index, Kind.ThematicBreak)

                val item = block.listItem
                val list = block.enclosingList
                if (item != null && list != null) {
                    if (previousItemId != item.id) {
                        val depth = maxOf(0, block.listDepth - 1)
                        result += when {
                            DocumentNodeType.isOrderedList(list.type) ->
                                DocumentDecoration(index, Kind.OrderedMarker("${item.ordinal ?: (list.attrs?.get("start")?.intValue ?: 1)}.", depth))
                            DocumentNodeType.isTaskList(list.type) ->
                                DocumentDecoration(index, Kind.Checkbox(item.attrs?.get("checked")?.boolValue ?: false, depth))
                            else -> DocumentDecoration(index, Kind.Bullet(depth))
                        }
                    }
                    previousItemId = item.id
                } else {
                    previousItemId = null
                }
            }
            return result
        }
    }
}
