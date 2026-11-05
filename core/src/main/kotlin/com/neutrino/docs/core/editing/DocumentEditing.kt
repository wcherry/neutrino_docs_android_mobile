package com.neutrino.docs.core.editing

import com.neutrino.docs.core.document.AttributedText
import com.neutrino.docs.core.document.DocRange
import com.neutrino.docs.core.document.DocumentAtom
import com.neutrino.docs.core.document.DocumentBlock
import com.neutrino.docs.core.document.DocumentBuilder
import com.neutrino.docs.core.document.DocumentMarkType
import com.neutrino.docs.core.document.DocumentMarks
import com.neutrino.docs.core.document.DocumentNodeType
import com.neutrino.docs.core.document.DocumentOutline
import com.neutrino.docs.core.document.DocumentProjection
import com.neutrino.docs.core.document.RunAttributes
import com.neutrino.docs.core.document.TextLines
import com.neutrino.docs.core.document.paragraphNode
import com.neutrino.shared.core.ooxml.DocMark
import com.neutrino.shared.core.ooxml.DocNode
import com.neutrino.shared.core.ooxml.DocValue
import com.neutrino.shared.core.ooxml.docInt
import com.neutrino.shared.core.ooxml.docString
import com.neutrino.shared.core.ooxml.intValue
import com.neutrino.shared.core.ooxml.stringValue

/**
 * A snapshot of the editor: the text it holds and the selection. Every command is a pure function
 * from one of these to another, which keeps the whole style bar testable without an `EditText`.
 */
class EditorDocumentState(
    val text: AttributedText,
    selection: DocRange,
    /**
     * The marks the next characters typed will carry when they are not the ones under the caret —
     * what "press bold, then type" leaves behind. A caret has no text to mark, so the intent is held
     * here until there is.
     */
    val pendingMarks: DocumentMarks? = null,
) {
    /** Clamped, so every command downstream can assume the range is valid. */
    val selection: DocRange = run {
        val location = selection.location.coerceIn(0, text.length)
        DocRange(location, selection.length.coerceIn(0, text.length - location))
    }

    val string: String get() = text.string
    val length: Int get() = text.length
    val isCaret: Boolean get() = selection.length == 0
    val document: DocNode get() = DocumentBuilder.document(text)

    val selectedLines: List<DocRange> get() = TextLines.covering(text, selection)
    val caretLine: DocRange get() = TextLines.covering(text, DocRange(selection.location, 0)).first()
    val caretBlock: DocumentBlock get() = TextLines.block(caretLine, text)

    /**
     * The marks in force at the selection: a selection is bold when every character is; a caret
     * reads the character *before* it — the run typing will join — unless [pendingMarks] says otherwise.
     */
    val activeMarks: DocumentMarks
        get() {
            if (isCaret && pendingMarks != null) return pendingMarks
            if (text.length == 0) return DocumentMarks.none
            if (selection.length > 0) {
                val distinct = text.runs(selection).map { it.second.marks ?: DocumentMarks.none }.distinct()
                return distinct.singleOrNull() ?: DocumentMarks.none
            }
            val index = (selection.location - 1).coerceIn(0, text.length - 1)
            return text.marks(index) ?: DocumentMarks.none
        }

    /** One past the largest level id anywhere, so a container created now cannot merge into an existing one. */
    val nextLevelId: Int
        get() = (text.runs().flatMap { r -> r.second.block.levels.map { it.id } }.maxOrNull() ?: 0) + 1

    fun with(text: AttributedText = this.text, selection: DocRange = this.selection, pendingMarks: DocumentMarks? = this.pendingMarks) =
        EditorDocumentState(text, selection, pendingMarks)

    companion object {
        fun of(doc: DocNode, selection: DocRange = DocRange(0, 0)) = EditorDocumentState(DocumentProjection.project(doc), selection)
    }
}

/** The style bar's alignments: TipTap's `textAlign`. Left is the absence of the attribute. */
enum class DocumentAlignment(val title: String) {
    LEFT("Align Left"), CENTER("Center"), RIGHT("Align Right"), JUSTIFY("Justify");

    val attributeValue: DocValue? get() = if (this == LEFT) null else docString(name.lowercase())

    companion object {
        fun named(name: String?): DocumentAlignment = entries.firstOrNull { it.name.lowercase() == name } ?: LEFT
    }
}

/** The style bar's actions. Each is a pure transform, pinned down in tests. */
enum class DocumentEditingCommand(val title: String) {
    BOLD("Bold"), ITALIC("Italic"), STRIKETHROUGH("Strikethrough"), CODE("Code"),
    LINK("Link"), IMAGE("Image"), QUOTE("Quote"), CODE_BLOCK("Code Block"), TABLE("Table"),
    CHECKBOX("Checkbox"), BULLET_LIST("Bulleted List"), NUMBERED_LIST("Numbered List"),
    BODY("Body"), HEADING1("Heading 1"), HEADING2("Heading 2"), HEADING3("Heading 3");

    /** The Ctrl-key for the commands that have one. */
    val keyboardShortcut: Char?
        get() = when (this) {
            BOLD -> 'b'; ITALIC -> 'i'; CODE -> 'e'; LINK -> 'k'
            BODY -> '0'; HEADING1 -> '1'; HEADING2 -> '2'; HEADING3 -> '3'
            else -> null
        }

    val markType: String?
        get() = when (this) {
            BOLD -> DocumentMarkType.BOLD
            ITALIC -> DocumentMarkType.ITALIC
            STRIKETHROUGH -> DocumentMarkType.STRIKE
            CODE -> DocumentMarkType.CODE
            else -> null
        }

    /**
     * Whether the command is in force — what the bar draws lit. Asked through the same predicates
     * the transform uses, because a lit button promises tapping it takes the formatting off.
     */
    fun isActive(state: EditorDocumentState): Boolean = when (this) {
        BOLD, ITALIC, STRIKETHROUGH, CODE -> DocumentEditing.allCharacters(state, markType!!)
        LINK -> DocumentEditing.allCharacters(state, DocumentMarkType.LINK)
        QUOTE -> DocumentEditing.allLinesQuoted(state)
        CODE_BLOCK -> DocumentEditing.allLinesAreCodeBlocks(state)
        BULLET_LIST -> DocumentEditing.allLines(state, DocumentNodeType.BULLET_LIST)
        NUMBERED_LIST -> DocumentEditing.allLines(state, DocumentNodeType.ORDERED_LIST)
        CHECKBOX -> DocumentEditing.allLines(state, DocumentNodeType.TASK_LIST)
        BODY, HEADING1, HEADING2, HEADING3 -> paragraphStyle(DocumentOutline.headingLevel(state.caretBlock)) == this
        IMAGE, TABLE -> false
    }

    /** Commands that need something asked for — a URL, a picture — are no-ops; the bar routes those to a sheet. */
    fun apply(state: EditorDocumentState): EditorDocumentState = when (this) {
        BODY -> DocumentEditing.setHeading(state, 0)
        HEADING1 -> DocumentEditing.setHeading(state, 1)
        HEADING2 -> DocumentEditing.setHeading(state, 2)
        HEADING3 -> DocumentEditing.setHeading(state, 3)
        BOLD, ITALIC, STRIKETHROUGH, CODE -> DocumentEditing.toggleMark(state, markType!!)
        QUOTE -> DocumentEditing.toggleQuote(state)
        CODE_BLOCK -> DocumentEditing.toggleCodeBlock(state)
        TABLE -> DocumentEditing.insertTable(state)
        CHECKBOX -> DocumentEditing.toggleList(state, DocumentNodeType.TASK_LIST)
        BULLET_LIST -> DocumentEditing.toggleList(state, DocumentNodeType.BULLET_LIST)
        NUMBERED_LIST -> DocumentEditing.toggleList(state, DocumentNodeType.ORDERED_LIST)
        LINK, IMAGE -> state
    }

    companion object {
        /** The paragraph styles, in the order the style bar lists them. */
        val paragraphStyles = listOf(BODY, HEADING1, HEADING2, HEADING3)

        /** H4 and deeper are closer to an H3 than to body text. */
        fun paragraphStyle(headingLevel: Int): DocumentEditingCommand = when {
            headingLevel <= 0 -> BODY
            headingLevel == 1 -> HEADING1
            headingLevel == 2 -> HEADING2
            else -> HEADING3
        }
    }
}

/**
 * The transforms behind the commands. Each rewrites the block or mark attributes the affected
 * characters carry, then re-derives the text from the document those attributes now describe —
 * a whole-document pass, because a structural edit can change how *other* lines group.
 */
object DocumentEditing {

    /** Rebuilds the document from the edited attributes and projects it again. */
    fun reproject(text: AttributedText, selection: DocRange): EditorDocumentState =
        EditorDocumentState(DocumentProjection.project(DocumentBuilder.document(text)), selection)

    /** The separator ending a line carries its block too, so it is rewritten with the content. */
    internal fun lineWithSeparator(line: DocRange, text: CharSequence): DocRange =
        if (line.end < text.length && text[line.end] == '\n') DocRange(line.location, line.length + 1) else line

    /**
     * The characters that carry a line's block. An empty *last* line has none of its own and reads
     * its block from the separator before it — which can carry it, provided the line that separator
     * ends takes its own block from its first character, i.e. is not empty too.
     */
    internal fun blockCarrier(line: DocRange, text: CharSequence): DocRange? {
        val range = lineWithSeparator(line, text)
        if (range.length > 0) return range
        if (line.location >= 2 && text[line.location - 1] == '\n' && text[line.location - 2] != '\n') return DocRange(line.location - 1, 1)
        return null
    }

    /** Gives `line` `block`, through its [carrier][blockCarrier] or, for a blockless last line, the text's trailing block. */
    internal fun setLineBlock(builder: AttributedText.Builder, line: DocRange, text: CharSequence, block: DocumentBlock) {
        val carrier = blockCarrier(line, text)
        if (carrier != null) builder.setBlock(carrier, block)
        else if (line.length == 0 && line.location == text.length) builder.trailingBlock = block
    }

    /** Applies `transform` to the block of every line the selection touches. */
    fun mapBlocks(state: EditorDocumentState, transform: (DocumentBlock, Int) -> DocumentBlock): EditorDocumentState {
        val builder = state.text.toBuilder()
        state.selectedLines.forEachIndexed { index, line ->
            val updated = transform(TextLines.block(line, state.text), index)
            setLineBlock(builder, line, state.string, updated)
        }
        return reproject(builder.build(), state.selection)
    }

    fun selectedBlocks(state: EditorDocumentState): List<DocumentBlock> = state.selectedLines.map { TextLines.block(it, state.text) }

    // ── Paragraph styles ────────────────────────────────────────────────────────────────────────

    /** A *set*, not a toggle: choosing the level a line already has takes it back to body. Alignment survives. */
    fun setHeading(state: EditorDocumentState, level: Int): EditorDocumentState {
        val blocks = selectedBlocks(state)
        val already = level > 0 && blocks.isNotEmpty() && blocks.all {
            DocumentNodeType.isHeading(it.leafType) && (it.attrs?.get("level")?.intValue ?: 1) == level
        }
        val target = if (already) 0 else level
        return mapBlocks(state) { block, _ ->
            val alignment = block.attrs?.get("textAlign")
            if (target <= 0) {
                block.replacingLeaf(DocumentNodeType.PARAGRAPH, alignment?.let { mapOf("textAlign" to it) })
            } else {
                block.replacingLeaf(DocumentNodeType.HEADING, buildMap {
                    put("level", docInt(target))
                    alignment?.let { put("textAlign", it) }
                })
            }
        }
    }

    fun setAlignment(state: EditorDocumentState, alignment: DocumentAlignment): EditorDocumentState =
        mapBlocks(state) { block, _ -> block.settingLeafAttribute("textAlign", alignment.attributeValue) }

    fun alignment(block: DocumentBlock): DocumentAlignment = DocumentAlignment.named(block.attrs?.get("textAlign")?.stringValue)

    // ── Inline marks ────────────────────────────────────────────────────────────────────────────

    /** Adds a mark to the selection, or takes it off when every character has it. A caret is unchanged. */
    fun toggleMark(state: EditorDocumentState, type: String, attrs: Map<String, DocValue>? = null): EditorDocumentState {
        if (state.selection.length == 0) return state
        val everywhere = allCharacters(state, type)
        val builder = state.text.toBuilder().update(state.selection) { a ->
            val current = a.marks ?: DocumentMarks.none
            val updated = if (everywhere) current.removing(type) else current.adding(DocMark(type, attrs))
            a.copy(marks = updated.takeIf { !it.isEmpty })
        }
        return reproject(builder.build(), state.selection)
    }

    fun allCharacters(state: EditorDocumentState, type: String): Boolean {
        if (state.selection.length == 0) return state.activeMarks.has(type)
        return state.text.runs(state.selection).all { it.second.marks?.has(type) == true }
    }

    fun setLink(state: EditorDocumentState, href: String): EditorDocumentState {
        if (state.selection.length == 0 || href.isEmpty()) return state
        val link = DocMark(DocumentMarkType.LINK, mapOf("href" to docString(href)))
        val builder = state.text.toBuilder().update(state.selection) { a -> a.copy(marks = (a.marks ?: DocumentMarks.none).adding(link)) }
        return reproject(builder.build(), state.selection)
    }

    fun removeLink(state: EditorDocumentState): EditorDocumentState {
        if (state.selection.length == 0) return state
        val builder = state.text.toBuilder().update(state.selection) { a ->
            a.copy(marks = a.marks?.removing(DocumentMarkType.LINK)?.takeIf { !it.isEmpty })
        }
        return reproject(builder.build(), state.selection)
    }

    // ── What the selection already is ───────────────────────────────────────────────────────────

    fun allLinesQuoted(state: EditorDocumentState): Boolean {
        val blocks = selectedBlocks(state)
        return blocks.isNotEmpty() && blocks.all { b -> b.isInside { it.type == DocumentNodeType.BLOCKQUOTE } }
    }

    fun allLinesAreCodeBlocks(state: EditorDocumentState): Boolean {
        val blocks = selectedBlocks(state)
        return blocks.isNotEmpty() && blocks.all { DocumentNodeType.isCodeBlock(it.leafType) }
    }

    /** The innermost list decides it: a bullet nested in a numbered list is a bullet. */
    fun allLines(state: EditorDocumentState, listType: String): Boolean {
        val blocks = selectedBlocks(state)
        return blocks.isNotEmpty() && blocks.all { it.innermostList?.type == listType }
    }

    // ── Containers ──────────────────────────────────────────────────────────────────────────────

    fun toggleQuote(state: EditorDocumentState): EditorDocumentState {
        val allQuoted = allLinesQuoted(state)
        val id = state.nextLevelId
        return mapBlocks(state) { block, _ ->
            if (allQuoted) block.unwrapping { it.type == DocumentNodeType.BLOCKQUOTE }
            // One id for the whole selection, so the lines form one quote rather than one each.
            else block.wrapping(listOf(DocumentBlock.Level(DocumentNodeType.BLOCKQUOTE, id = id)))
        }
    }

    /** Puts the lines in a list, or takes them out; a list of another type is replaced, not nested. */
    fun toggleList(state: EditorDocumentState, listType: String): EditorDocumentState {
        val itemType = if (listType == DocumentNodeType.TASK_LIST) DocumentNodeType.TASK_ITEM else DocumentNodeType.LIST_ITEM
        val allInThisList = allLines(state, listType)
        val listId = state.nextLevelId
        var nextItemId = listId + 1
        return mapBlocks(state) { block, _ ->
            val stripped = if (block.innermostList != null) block.unwrapping(depth = 2) { DocumentNodeType.isList(it.type) } else block
            if (allInThisList) return@mapBlocks stripped
            val item = DocumentBlock.Level(itemType, id = nextItemId++)
            stripped.wrapping(listOf(DocumentBlock.Level(listType, id = listId), item))
        }
    }

    fun toggleCodeBlock(state: EditorDocumentState): EditorDocumentState {
        val allCode = allLinesAreCodeBlocks(state)
        return mapBlocks(state) { block, _ ->
            block.replacingLeaf(if (allCode) DocumentNodeType.PARAGRAPH else DocumentNodeType.CODE_BLOCK, null)
        }
    }

    /**
     * Tab / Shift-Tab. Indent nests an item under the item above it — its previous sibling, so the
     * line joins that item's sub-list rather than becoming an empty bullet holding one. Outdent lifts
     * a nested item out to be a sibling of its parent; a top-level item leaves the list. Outside a
     * list there is nothing to indent to: a document's shape is its tree, not its leading spaces.
     * (The iOS transform nests a line inside its own item, and outdents into its parent's item.)
     */
    fun indent(state: EditorDocumentState, outdent: Boolean = false): EditorDocumentState {
        val allLines = TextLines.ranges(state.text)
        val selected = state.selectedLines.toSet()
        val selectedItems = state.selectedLines.mapNotNull { TextLines.block(it, state.text).listItem?.id }.toSet()
        val listId = state.nextLevelId
        var nextItemId = listId + 1
        val builder = state.text.toBuilder()
        allLines.forEachIndexed { lineIndex, line ->
            if (line !in selected) return@forEachIndexed
            val block = TextLines.block(line, state.text)
            val levels = block.levels
            val k = levels.indexOfLast { DocumentNodeType.isList(it.type) }.takeIf { it >= 0 && it < levels.size - 1 } ?: return@forEachIndexed
            val item = levels.getOrNull(k + 1)?.takeIf { DocumentNodeType.isListItem(it.type) } ?: return@forEachIndexed
            val updated: DocumentBlock = if (outdent) {
                val parentItem = levels.getOrNull(k - 1)?.takeIf { DocumentNodeType.isListItem(it.type) }
                if (parentItem != null) DocumentBlock(levels.take(k - 1) + levels.drop(k + 1))
                else block.unwrapping(depth = 2) { DocumentNodeType.isList(it.type) }
            } else {
                // The nearest earlier item of the same list that is not itself being indented.
                val previous = (lineIndex - 1 downTo 0).asSequence()
                    .map { TextLines.block(allLines[it], state.text).levels }
                    .takeWhile { it.size > k + 1 && it[k].isSameInstance(levels[k]) }
                    .map { it[k + 1] }
                    .firstOrNull { it.id != item.id && it.id !in selectedItems && DocumentNodeType.isListItem(it.type) }
                    ?: return@forEachIndexed
                DocumentBlock(levels.take(k + 1) + previous + DocumentBlock.Level(levels[k].type, id = listId) +
                    DocumentBlock.Level(item.type, id = nextItemId++) + levels.drop(k + 2))
            }
            setLineBlock(builder, line, state.string, updated)
        }
        return reproject(builder.build(), state.selection)
    }

    // ── Inserting content ───────────────────────────────────────────────────────────────────────

    /** A 2×2 starter table, the smallest one that shows a header row. */
    fun insertTable(state: EditorDocumentState): EditorDocumentState {
        fun cell(text: String, type: String) = DocNode(type, content = listOf(paragraphNode(if (text.isEmpty()) emptyList() else listOf(DocNode.text(text)))))
        val table = DocNode("table", content = listOf(
            DocNode("tableRow", content = listOf(cell("Column 1", "tableHeader"), cell("Column 2", "tableHeader"))),
            DocNode("tableRow", content = listOf(cell("", "tableCell"), cell("", "tableCell"))),
        ))
        return insertBlocks(listOf(table), state)
    }

    /**
     * Splices whole blocks in at the caret's line, their level ids lifted clear of those in use so a
     * spliced list cannot merge into a neighbouring one. An empty line is replaced; anything else is
     * pushed down.
     */
    fun insertBlocks(nodes: List<DocNode>, state: EditorDocumentState): EditorDocumentState {
        val offset = state.nextLevelId
        val fragment = DocumentProjection.project(DocNode(DocumentNodeType.DOC, content = nodes)).let { projected ->
            AttributedText.Builder().apply {
                for ((r, a) in projected.runs()) {
                    val lifted = DocumentBlock(a.block.levels.map { it.copy(id = it.id + offset) })
                    append(projected.substring(r), a.copy(block = lifted))
                }
            }.build()
        }
        val line = state.caretLine
        val builder = state.text.toBuilder()
        val insertAt: Int
        val toInsert: AttributedText
        if (line.length == 0) {
            // The fragment's last line takes over the empty line's separator.
            insertAt = line.location
            toInsert = fragment
        } else {
            insertAt = line.end
            val attrs = state.text.attributes(line.location.coerceAtMost(state.length - 1)) ?: RunAttributes(DocumentBlock.defaultParagraph)
            toInsert = AttributedText.Builder().append("\n", RunAttributes(attrs.block)).append(fragment).build()
        }
        builder.insert(minOf(insertAt, builder.length), toInsert)
        val text = builder.build()
        // The caret lands in the first cell, ready to be typed over.
        val caret = minOf(insertAt + if (line.length == 0) 0 else 1, text.length)
        return reproject(text, DocRange(caret, minOf(8, maxOf(0, text.length - caret))))
    }

    /** Inserts an atomic inline node — a picture — at the selection. */
    fun insertAtom(node: DocNode, state: EditorDocumentState): EditorDocumentState {
        val anchor = state.selection.location.coerceIn(0, maxOf(0, state.length - 1))
        val base = state.text.attributes(anchor) ?: RunAttributes(DocumentBlock(listOf(DocumentBlock.Level(DocumentNodeType.PARAGRAPH, id = 1))))
        // A block atom (a rule) is not a paragraph to put a picture in.
        val block = if (base.block.leafType in DocumentNodeType.atomics) base.block.replacingLeaf(DocumentNodeType.PARAGRAPH, null) else base.block
        val builder = state.text.toBuilder().replace(state.selection, DocumentProjection.ATOM, RunAttributes(block, null, DocumentAtom(node)))
        return reproject(builder.build(), DocRange(state.selection.location + 1, 0))
    }

    /** Replaces the atom at `range` with an edited copy — how the image panel applies a change. */
    fun replaceAtom(state: EditorDocumentState, range: DocRange, node: DocNode): EditorDocumentState {
        if (range.location < 0 || range.end > state.length || range.length != 1) return state
        val builder = state.text.toBuilder().setAtom(range, DocumentAtom(node))
        return reproject(builder.build(), state.selection)
    }

    fun removeAtom(state: EditorDocumentState, range: DocRange): EditorDocumentState {
        if (range.location < 0 || range.end > state.length) return state
        return reproject(state.text.toBuilder().delete(range).build(), DocRange(range.location, 0))
    }
}
