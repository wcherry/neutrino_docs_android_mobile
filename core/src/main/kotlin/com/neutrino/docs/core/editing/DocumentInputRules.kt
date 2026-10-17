package com.neutrino.docs.core.editing

import com.neutrino.docs.core.document.AttributedText
import com.neutrino.docs.core.document.DocRange
import com.neutrino.docs.core.document.DocumentBlock
import com.neutrino.docs.core.document.DocumentNodeType
import com.neutrino.docs.core.document.DocumentProjection
import com.neutrino.docs.core.document.RunAttributes
import com.neutrino.shared.core.ooxml.DocValue
import com.neutrino.shared.core.ooxml.docInt
import com.neutrino.shared.core.ooxml.intValue
import com.neutrino.docs.core.document.DocumentMarks
import com.neutrino.docs.core.document.TextLines

/**
 * The as-you-type behaviours: auto bullets and numbering, closing brackets, and the typing
 * shortcuts. Typing `# ` still makes a heading — but the characters are *consumed* and the line's
 * block becomes a heading; there is no `#` left to conceal, because the document is a tree.
 *
 * Each rule is a pure function from a state plus the text about to be inserted to a replacement
 * state, or null for "let the text view do its normal thing".
 */
object DocumentInputRules {

    data class Options(
        val continueLists: Boolean = true,
        val closeBrackets: Boolean = true,
        /** Whether `# `, `- `, `> ` and friends convert the line as they are typed. */
        val typingShortcuts: Boolean = true,
    )

    /**
     * Return. A plain paragraph needs no help — the new line inherits its block. The cases that do:
     * a new list item must be a new *item*, a heading should not beget another heading, and Return
     * in a code block is a break within it.
     */
    fun newline(state: EditorDocumentState, options: Options = Options()): EditorDocumentState? {
        val block = state.caretBlock
        val line = state.caretLine
        if (DocumentNodeType.isCodeBlock(block.leafType)) return insert(DocumentProjection.LINE_BREAK, state)
        if (!options.continueLists) return null

        // Return on an empty item ends the list, which is how every editor lets you stop.
        if (block.innermostList != null && line.length == 0) return DocumentEditing.indent(state, outdent = true)

        block.innermostList?.let { list ->
            val itemType = block.listItem?.type ?: DocumentNodeType.LIST_ITEM
            val fresh = block.unwrapping(depth = 2) { it.isSameInstance(list) }
                .wrapping(listOf(list, DocumentBlock.Level(itemType, id = state.nextLevelId)))
            return insertLine(state, fresh)
        }

        // A heading is a title, and what follows a title is prose.
        if (DocumentNodeType.isHeading(block.leafType)) {
            val alignment = block.attrs?.get("textAlign")
            return insertLine(state, block.replacingLeaf(DocumentNodeType.PARAGRAPH, alignment?.let { mapOf("textAlign" to it) }))
        }
        return null
    }

    /** The line-opening shortcuts. Only from a plain paragraph: `# ` in a heading is somebody writing a hash. */
    private fun shortcut(marker: String, block: DocumentBlock, nextId: Int): DocumentBlock? {
        if (block.leafType != DocumentNodeType.PARAGRAPH) return null
        val alignment = block.attrs?.get("textAlign")
        fun heading(level: Int) = block.replacingLeaf(DocumentNodeType.HEADING, buildMap<String, DocValue> {
            put("level", docInt(level))
            alignment?.let { put("textAlign", it) }
        })
        fun list(type: String, item: String) = block.wrapping(listOf(DocumentBlock.Level(type, id = nextId), DocumentBlock.Level(item, id = nextId + 1)))
        return when (marker) {
            "#" -> heading(1)
            "##" -> heading(2)
            "###" -> heading(3)
            "####" -> heading(4)
            "#####" -> heading(5)
            "######" -> heading(6)
            "-", "*", "+" -> list(DocumentNodeType.BULLET_LIST, DocumentNodeType.LIST_ITEM)
            ">" -> block.wrapping(listOf(DocumentBlock.Level(DocumentNodeType.BLOCKQUOTE, id = nextId)))
            "[]", "[ ]", "[x]" -> list(DocumentNodeType.TASK_LIST, DocumentNodeType.TASK_ITEM)
            else -> {
                // `1. `, `2) ` — an ordered list starting at the number typed.
                val delimiter = marker.lastOrNull()
                if (delimiter != '.' && delimiter != ')') return null
                val digits = marker.dropLast(1)
                if (digits.isEmpty() || !digits.all { it.isDigit() }) return null
                val start = digits.toIntOrNull() ?: return null
                val level = DocumentBlock.Level(DocumentNodeType.ORDERED_LIST, if (start != 1) mapOf("start" to docInt(start)) else null, nextId)
                block.wrapping(listOf(level, DocumentBlock.Level(DocumentNodeType.LIST_ITEM, id = nextId + 1)))
            }
        }
    }

    /** One typed character: the shortcuts first (a space completes `## `), then bracket closing. */
    fun insert(character: Char, state: EditorDocumentState, options: Options = Options()): EditorDocumentState? {
        if (options.typingShortcuts && character == ' ' && state.isCaret) applyShortcut(state)?.let { return it }
        // Three backticks open a code block, which has no trailing space to wait for.
        if (options.typingShortcuts && character == '`' && state.isCaret) applyCodeFence(state)?.let { return it }
        return closeBracket(character, state, options)
    }

    private fun applyShortcut(state: EditorDocumentState): EditorDocumentState? {
        val line = state.caretLine
        val typed = DocRange(line.location, state.selection.location - line.location)
        if (typed.length <= 0 || typed.length > 6) return null
        val converted = shortcut(state.text.substring(typed), state.caretBlock, state.nextLevelId) ?: return null
        return retag(state, line, typed, converted)
    }

    private fun applyCodeFence(state: EditorDocumentState): EditorDocumentState? {
        val line = state.caretLine
        val typed = DocRange(line.location, state.selection.location - line.location)
        if (typed.length != 2 || state.text.substring(typed) != "``") return null
        val block = state.caretBlock
        if (block.leafType != DocumentNodeType.PARAGRAPH) return null
        if (line.length - typed.length <= 0 && line.end >= state.length) return null
        return retag(state, line, typed, block.replacingLeaf(DocumentNodeType.CODE_BLOCK, null))
    }

    /** The marker goes; the line becomes what it was reaching for. */
    private fun retag(state: EditorDocumentState, line: DocRange, typed: DocRange, block: DocumentBlock): EditorDocumentState {
        val builder = state.text.toBuilder().delete(typed)
        val remaining = DocumentEditing.lineWithSeparator(DocRange(line.location, maxOf(0, line.length - typed.length)), builder.string)
        // A marker alone on the last line leaves no character of its own to carry the block.
        if (remaining.length > 0) builder.setBlock(remaining, block)
        else DocumentEditing.setLineBlock(builder, remaining, builder.string, block)
        return DocumentEditing.reproject(builder.build(), DocRange(line.location, 0))
    }

    // ── Brackets ────────────────────────────────────────────────────────────────────────────────

    /** Pairs that auto-close. Quotes are left alone: apostrophes in prose would be mangled. */
    val bracketPairs = mapOf('(' to ')', '[' to ']', '{' to '}', '`' to '`')

    private fun closeBracket(character: Char, state: EditorDocumentState, options: Options): EditorDocumentState? {
        if (!options.closeBrackets) return null
        val text = state.string
        // Typing a closer that is already there steps over it.
        if (character in bracketPairs.values && state.isCaret && state.selection.location < text.length && text[state.selection.location] == character) {
            return state.with(selection = DocRange(state.selection.location + 1, 0), pendingMarks = null)
        }
        val closer = bracketPairs[character] ?: return null

        // With a selection, the selection is wrapped rather than replaced.
        if (!state.isCaret) {
            val attributes = (state.text.attributes(state.selection.location) ?: RunAttributes(DocumentBlock.defaultParagraph)).copy(atom = null)
            val builder = state.text.toBuilder()
                .insert(state.selection.end, AttributedText.of(closer.toString(), attributes))
                .insert(state.selection.location, AttributedText.of(character.toString(), attributes))
            return EditorDocumentState(builder.build(), DocRange(state.selection.location + 1, state.selection.length))
        }
        // A backtick right after a backtick is a fence being typed.
        if (character == '`' && state.selection.location > 0 && text[state.selection.location - 1] == '`') return null
        // Not before a word: `(` typed before "foo" should not make "()foo".
        if (state.selection.location < text.length && text[state.selection.location].isLetterOrDigit()) return null

        val inserted = insert("$character$closer", state) ?: return null
        return EditorDocumentState(inserted.text, DocRange(state.selection.location + 1, 0))
    }

    // ── Backspace ───────────────────────────────────────────────────────────────────────────────

    /**
     * Backspace with the caret at the start of a line. In a list it lifts the item out rather than
     * joining it to the line above; in a quote it unquotes; on a heading it demotes. Between an
     * auto-inserted pair it removes both.
     */
    fun deleteBackward(state: EditorDocumentState, options: Options = Options()): EditorDocumentState? {
        if (!state.isCaret) return null
        val block = state.caretBlock
        if (state.selection.location == state.caretLine.location) {
            if (block.innermostList != null) return DocumentEditing.indent(state, outdent = true)
            if (block.isInside { it.type == DocumentNodeType.BLOCKQUOTE }) return DocumentEditing.toggleQuote(state)
            if (DocumentNodeType.isHeading(block.leafType)) return DocumentEditing.setHeading(state, 0)
        }
        val at = state.selection.location
        if (!options.closeBrackets || at <= 0 || at >= state.length) return null
        val before = state.string[at - 1]
        val after = state.string[at]
        if (bracketPairs[before] != after) return null
        return EditorDocumentState(state.text.toBuilder().delete(DocRange(at - 1, 2)).build(), DocRange(at - 1, 0))
    }

    // ── Insertion helpers ───────────────────────────────────────────────────────────────────────

    /** Inserts plain text at the selection, inheriting the attributes already there. */
    fun insert(string: String, state: EditorDocumentState): EditorDocumentState? {
        if (string.isEmpty()) return null
        val attributes = typingAttributes(state.text, state.selection.location, state.pendingMarks)
        val builder = state.text.toBuilder().replace(state.selection, string, attributes)
        return EditorDocumentState(builder.build(), DocRange(state.selection.location + string.length, 0))
    }

    /** Starts a new line carrying `block`; the rest of the line moves onto it, so Return mid-item splits the item. */
    private fun insertLine(state: EditorDocumentState, block: DocumentBlock): EditorDocumentState {
        val line = state.caretLine
        val tail = DocRange(state.selection.location, maxOf(0, line.end - state.selection.location))
        val base = typingAttributes(state.text, state.selection.location, null)
        val builder = state.text.toBuilder().replace(state.selection, "\n", base.copy(block = block, atom = null))
        if (tail.length > 0) builder.setBlock(DocRange(tail.location + 1, tail.length), block)
        return DocumentEditing.reproject(builder.build(), DocRange(state.selection.location + 1, 0))
    }

    /**
     * The attributes a character typed at `offset` takes: the character before it when that is on
     * the same line, else the line's own block. Never an atom — typing beside a picture is not
     * another picture — and never a block atom's block, since a rule is not a paragraph to type in.
     * `pendingMarks` (a toggle pressed with nothing selected) overrides the inherited marks.
     */
    fun typingAttributes(text: AttributedText, offset: Int, pendingMarks: DocumentMarks?): RunAttributes {
        if (offset == text.length) text.trailingBlock?.let {
            val block = if (it.leafType in DocumentNodeType.atomics) it.replacingLeaf(DocumentNodeType.PARAGRAPH, null) else it
            return RunAttributes(block, pendingMarks?.takeIf { m -> !m.isEmpty }, null)
        }
        if (text.length == 0) return RunAttributes(DocumentBlock.defaultParagraph, pendingMarks?.takeIf { !it.isEmpty })
        val s = text.string
        val inherited = when {
            offset > 0 && s[offset - 1] != '\n' -> text.attributes(offset - 1)!!
            offset < s.length -> text.attributes(offset)!!.copy(marks = null)
            else -> text.attributes(offset - 1)!!.copy(marks = null)
        }
        var block = inherited.block
        if (block.leafType in DocumentNodeType.atomics) block = block.replacingLeaf(DocumentNodeType.PARAGRAPH, null)
        val marks = if (pendingMarks != null) pendingMarks.takeIf { !it.isEmpty } else inherited.marks
        return RunAttributes(block, marks, null)
    }
}

/** A stretch of the document the editor can collapse: a section, or a run of list items. */
data class FoldableRegion(val startLine: Int, val endLine: Int, val kind: Kind, val summary: String) {
    sealed class Kind {
        data class Section(val level: Int) : Kind()
        object CodeBlock : Kind()
        object ListRun : Kind()
    }

    /** Everything after the header line, which stays visible. */
    val hiddenLines: IntRange? get() = if (endLine > startLine) (startLine + 1)..endLine else null
}

/**
 * The foldable regions, read from the block structure rather than from syntax: a `#` inside a code
 * block is text in a code block and nothing about it looks like a heading.
 */
object CodeFolding {

    fun regions(text: AttributedText): List<FoldableRegion> {
        if (text.length == 0) return emptyList()
        val blocks = TextLines.ranges(text).map { TextLines.block(it, text) }
        return (sectionRegions(blocks) + listRegions(blocks)).sortedBy { it.startLine }
    }

    /** A heading folds everything down to the next heading of the same or a shallower level. */
    private fun sectionRegions(blocks: List<DocumentBlock>): List<FoldableRegion> {
        val headings = blocks.withIndex().filter { DocumentNodeType.isHeading(it.value.leafType) }
            .map { it.index to (it.value.attrs?.get("level")?.intValue ?: 1) }
        val regions = ArrayList<FoldableRegion>()
        headings.forEachIndexed { position, (line, level) ->
            val next = headings.drop(position + 1).firstOrNull { it.second <= level }
            val end = next?.let { it.first - 1 } ?: (blocks.size - 1)
            if (end > line) {
                val hidden = end - line
                regions += FoldableRegion(line, end, FoldableRegion.Kind.Section(level), "$hidden ${if (hidden == 1) "line" else "lines"}")
            }
        }
        return regions
    }

    /** A run of lines sharing the same outermost list folds to its first item. */
    private fun listRegions(blocks: List<DocumentBlock>): List<FoldableRegion> {
        val regions = ArrayList<FoldableRegion>()
        var index = 0
        while (index < blocks.size) {
            val list = blocks[index].levels.firstOrNull { DocumentNodeType.isList(it.type) }
            if (list == null) { index++; continue }
            var end = index
            while (end + 1 < blocks.size && blocks[end + 1].levels.any { it.isSameInstance(list) }) end++
            if (end > index) {
                val hidden = end - index
                regions += FoldableRegion(index, end, FoldableRegion.Kind.ListRun, "$hidden ${if (hidden == 1) "item" else "items"}")
            }
            index = end + 1
        }
        return regions
    }

    /** The lines hidden by a set of folded region starts: folding an outer section hides the inner ones too. */
    fun hiddenLines(folded: Set<Int>, regions: List<FoldableRegion>): Set<Int> =
        regions.filter { it.startLine in folded }.flatMap { it.hiddenLines?.toList() ?: emptyList() }.toSet()
}
