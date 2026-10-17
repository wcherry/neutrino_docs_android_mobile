package com.neutrino.docs.core.document

import com.neutrino.shared.core.ooxml.DocMark
import com.neutrino.shared.core.ooxml.DocNode
import com.neutrino.shared.core.ooxml.DocValue

// The document tree is the shared library's `DocNode` — the web editor's ProseMirror (TipTap) JSON,
// the very tree the `.docx` mapping reads and writes — so there is no app-side copy of it to
// translate to and from. What lives here is what the *editor* needs on top: the vocabulary, and the
// three attributes every character of the projected text carries.

/** A range of the projected text, in UTF-16 offsets — `NSRange` on iOS, and what `EditText` speaks. */
data class DocRange(val location: Int, val length: Int) {
    val end: Int get() = location + length
    val isEmpty: Boolean get() = length == 0

    companion object {
        fun of(start: Int, end: Int) = DocRange(start, end - start)
    }
}

/**
 * Where one line of text sits in the document tree.
 *
 * `levels` runs outermost to innermost and ends with the node holding the line's inline content —
 * a bullet inside a quote is `[blockquote, bulletList, listItem, paragraph]`. The builder regroups
 * lines by their common prefix, which is what rebuilds the nesting.
 */
data class DocumentBlock(val levels: List<Level>) {

    data class Level(
        /** The node type, spelled as the document spells it. */
        val type: String,
        /** The node's attributes, held verbatim so web-only settings survive a save. */
        val attrs: Map<String, DocValue>? = null,
        /**
         * Distinguishes two adjacent instances of the same container: without it two consecutive
         * bulleted lists would share a prefix and be rebuilt as one.
         */
        val id: Int,
        /** The item's position in its ordered list, for drawing the number. Never written back. */
        val ordinal: Int? = null,
    ) {
        /** The same *instance* of a container — what grouping turns on. Attributes are not compared. */
        fun isSameInstance(other: Level) = type == other.type && id == other.id
    }

    /** The node holding the inline content — the line's own block. */
    val leaf: Level get() = levels.lastOrNull() ?: Level(DocumentNodeType.PARAGRAPH, id = 0)
    val leafType: String get() = leaf.type
    val attrs: Map<String, DocValue>? get() = leaf.attrs

    /** How deeply the line is nested in containers. */
    val containerDepth: Int get() = maxOf(0, levels.size - 1)

    /** The innermost list this line is an item of, when it is the first block of one. */
    val enclosingList: Level?
        get() {
            val listIndex = levels.size - 3
            if (listIndex < 0) return null
            return levels[listIndex].takeIf { DocumentNodeType.isList(it.type) }
        }

    /** The list item this line belongs to directly, if any. */
    val listItem: Level?
        get() {
            if (levels.size < 2) return null
            return levels[levels.size - 2].takeIf { DocumentNodeType.isListItem(it.type) }
        }

    /** The innermost list container, at any depth. */
    val innermostList: Level? get() = levels.lastOrNull { DocumentNodeType.isList(it.type) }

    /** How many lists enclose the line — its indent level in a list. */
    val listDepth: Int get() = levels.dropLast(1).count { DocumentNodeType.isList(it.type) }

    fun isInside(predicate: (Level) -> Boolean): Boolean = levels.dropLast(1).any(predicate)

    /** Replaces the leaf, keeping the line where it is in the tree — how a paragraph becomes a heading inside its list item. */
    fun replacingLeaf(type: String, attrs: Map<String, DocValue>?): DocumentBlock {
        if (levels.isEmpty()) return DocumentBlock(listOf(Level(type, attrs, 0)))
        return DocumentBlock(levels.dropLast(1) + Level(type, attrs, levels.last().id))
    }

    /** Sets or clears one attribute of the leaf — alignment, a heading's level. */
    fun settingLeafAttribute(name: String, value: DocValue?): DocumentBlock {
        val attributes = (attrs ?: emptyMap()).toMutableMap()
        if (value != null) attributes[name] = value else attributes.remove(name)
        return replacingLeaf(leafType, attributes.takeIf { it.isNotEmpty() })
    }

    /** Inserts containers directly above the leaf — how a paragraph enters a list or a quote. */
    fun wrapping(containers: List<Level>): DocumentBlock {
        if (levels.isEmpty()) return DocumentBlock(containers)
        return DocumentBlock(levels.dropLast(1) + containers + levels.last())
    }

    /**
     * Removes the innermost container matching `predicate`. `depth` is how many levels it occupies:
     * a quote is one, a list two, because an item with no list around it is not a valid shape.
     */
    fun unwrapping(depth: Int = 1, predicate: (Level) -> Boolean): DocumentBlock {
        if (levels.isEmpty()) return this
        val containers = levels.dropLast(1)
        val index = containers.indexOfLast(predicate)
        if (index < 0) return this
        val end = minOf(index + depth, containers.size)
        return DocumentBlock(containers.take(index) + containers.drop(end) + levels.last())
    }

    override fun toString() = "DocumentBlock(" + levels.joinToString(" > ") { "${it.type}#${it.id}" } + ")"

    companion object {
        val defaultParagraph = DocumentBlock(listOf(Level(DocumentNodeType.PARAGRAPH, id = 0)))
    }
}

/** The inline marks on a run, held whole so a mark this app does not model survives being typed around. */
data class DocumentMarks(val marks: List<DocMark>) {
    val isEmpty: Boolean get() = marks.isEmpty()
    fun has(type: String) = marks.any { it.type == type }
    fun mark(type: String) = marks.firstOrNull { it.type == type }

    /** Replaces any existing mark of the same type: a second link on the same words is a changed link. */
    fun adding(mark: DocMark) = DocumentMarks(marks.filter { it.type != mark.type } + mark)
    fun removing(type: String) = DocumentMarks(marks.filter { it.type != type })

    companion object {
        val none = DocumentMarks(emptyList())
    }
}

/** An atomic node standing in the text as one character — a picture, a rule, an embed — held whole. */
data class DocumentAtom(val node: DocNode) {
    fun setting(name: String, value: DocValue?): DocumentAtom {
        val attrs = (node.attrs ?: emptyMap()).toMutableMap()
        if (value != null) attrs[name] = value else attrs.remove(name)
        return DocumentAtom(node.copy(attrs = attrs.takeIf { it.isNotEmpty() }))
    }
}

/**
 * What every character of the projected text carries: its block (always), its marks (when it has
 * any), and — for the one character an atomic node projects to — the node itself. These are the
 * document; how it looks is derived from them and never read back.
 */
data class RunAttributes(val block: DocumentBlock, val marks: DocumentMarks? = null, val atom: DocumentAtom? = null)

/**
 * The document's vocabulary, as the web app's TipTap extensions spell it. Both spellings are
 * accepted on the way in — ProseMirror's schema writes `bullet_list`, TipTap `bulletList` — and the
 * camelCase one is written back.
 */
object DocumentNodeType {
    /** Nodes that hold other blocks; each becomes a level of ancestry rather than a line. */
    val containers = setOf(
        "blockquote", "bulletList", "bullet_list", "orderedList", "ordered_list", "listItem", "list_item",
        "taskList", "task_list", "taskItem", "task_item", "table", "tableRow", "table_row",
        "tableCell", "table_cell", "tableHeader", "table_header", "columnLayout", "column",
    )

    /** Nodes holding inline content; each becomes one line. */
    val textBlocks = setOf("paragraph", "heading", "codeBlock", "code_block")

    /** Nodes with no content, standing in the text as one character on a line of their own. */
    val atomics = setOf(
        "horizontalRule", "horizontal_rule", "sectionBreak", "pageBreak", "page_break",
        "tableOfContents", "sheetEmbed", "diagramEmbed",
    )

    /** Inline nodes that are not text. */
    val inlineAtomics = setOf("image", "footnote", "crossRef", "hardBreak", "hard_break", "docField")

    fun isList(type: String) = type in setOf("bulletList", "bullet_list", "orderedList", "ordered_list", "taskList", "task_list")
    fun isOrderedList(type: String) = type == "orderedList" || type == "ordered_list"
    fun isTaskList(type: String) = type == "taskList" || type == "task_list"
    fun isListItem(type: String) = type in setOf("listItem", "list_item", "taskItem", "task_item")
    fun isHeading(type: String) = type == HEADING
    fun isCodeBlock(type: String) = type == "codeBlock" || type == "code_block"
    fun isHardBreak(type: String) = type == "hardBreak" || type == "hard_break"
    fun isRule(type: String) = type == "horizontalRule" || type == "horizontal_rule"

    const val PARAGRAPH = "paragraph"
    const val HEADING = "heading"
    const val CODE_BLOCK = "codeBlock"
    const val BULLET_LIST = "bulletList"
    const val ORDERED_LIST = "orderedList"
    const val TASK_LIST = "taskList"
    const val LIST_ITEM = "listItem"
    const val TASK_ITEM = "taskItem"
    const val BLOCKQUOTE = "blockquote"
    const val HORIZONTAL_RULE = "horizontalRule"
    const val HARD_BREAK = "hardBreak"
    const val IMAGE = "image"
    const val TEXT = "text"
    const val DOC = "doc"
}

/** The mark vocabulary, likewise the web app's. */
object DocumentMarkType {
    const val BOLD = "bold"
    const val ITALIC = "italic"
    const val STRIKE = "strike"
    const val CODE = "code"
    const val UNDERLINE = "underline"
    const val LINK = "link"
    const val HIGHLIGHT = "highlight"
    const val SUPERSCRIPT = "superscript"
    const val SUBSCRIPT = "subscript"
    /** Colour, font family and size all ride on this one mark's attributes. */
    const val TEXT_STYLE = "textStyle"
}

/** The paragraph a line break produces: no `content` key when empty — ProseMirror rejects `content: []`. */
fun paragraphNode(content: List<DocNode>): DocNode =
    DocNode(DocumentNodeType.PARAGRAPH, content = content.takeIf { it.isNotEmpty() })

/**
 * What the document says, with nothing around it: every text run, one line per block. The words
 * without the appearance — for the word count. (The shared `plainText` concatenates blocks.)
 */
val DocNode.documentText: String
    get() {
        text?.let { return it }
        val separator = if (type in DocumentNodeType.textBlocks) "" else DocumentProjection.BLOCK_SEPARATOR
        return children.joinToString(separator) { it.documentText }
    }

fun DocNode.hasMark(vararg names: String): Boolean = marks?.any { it.type in names } ?: false
