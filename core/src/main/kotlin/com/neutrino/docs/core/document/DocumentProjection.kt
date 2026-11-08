package com.neutrino.docs.core.document

import com.neutrino.shared.core.ooxml.DocNode
import com.neutrino.shared.core.ooxml.intValue

/**
 * Flattens a document into the text the editor holds. [DocumentBuilder] reads it back, and the two
 * are required to be inverses — a document that survives `project` then `document` unchanged is
 * the property every other guarantee in the editor rests on.
 *
 * One line per block, blocks separated by `\n`, the separator belonging to the block it follows.
 * Two characters mean something of their own: [LINE_BREAK] is a break *inside* a block (a
 * `hardBreak`, or a newline in a code block — neither may split the line, since a line is a block),
 * and [ATOM] stands for an atomic node and always carries it.
 */
object DocumentProjection {

    /** Separates blocks. The only character the builder splits on. */
    const val BLOCK_SEPARATOR = "\n"

    /** A break within a block. */
    const val LINE_BREAK = " "

    /** Stands in for an atomic node — the object replacement character. */
    const val ATOM = "￼"

    enum class Kind { CONTAINER, TEXT_BLOCK, ATOM }

    /**
     * What a node is, falling back to its shape for a type this app has never heard of: an unknown
     * node wrapping paragraphs is a container and its children still appear, rather than the whole
     * subtree vanishing.
     */
    fun kind(node: DocNode): Kind {
        if (node.type in DocumentNodeType.textBlocks) return Kind.TEXT_BLOCK
        if (node.type in DocumentNodeType.containers) return Kind.CONTAINER
        if (node.type in DocumentNodeType.atomics) return Kind.ATOM
        val children = node.children
        if (children.isEmpty()) return Kind.ATOM
        val holdsInline = children.any { it.type == DocumentNodeType.TEXT || it.type in DocumentNodeType.inlineAtomics }
        return if (holdsInline) Kind.TEXT_BLOCK else Kind.CONTAINER
    }

    /** Projects a `doc` node, or any node holding blocks. */
    fun project(doc: DocNode): AttributedText {
        val projector = Projector()
        projector.appendBlocks(doc.children, emptyList(), null)
        return projector.finish()
    }

    /** Ids only have to be unique within one projection: they tell two adjacent lists from one. */
    private class Projector {
        private val output = AttributedText.Builder()
        private var nextId = 0
        private fun makeId() = ++nextId

        fun appendBlocks(nodes: List<DocNode>, ancestry: List<DocumentBlock.Level>, ordinalStart: Int?) {
            nodes.forEachIndexed { index, node ->
                val ordinal = ordinalStart?.let { it + index }
                when (kind(node)) {
                    Kind.CONTAINER -> {
                        val level = DocumentBlock.Level(node.type, node.attrs, makeId(), ordinal)
                        val childOrdinals = if (DocumentNodeType.isOrderedList(node.type)) node.attr("start")?.intValue ?: 1 else null
                        appendBlocks(node.children, ancestry + level, childOrdinals)
                    }
                    Kind.TEXT_BLOCK -> {
                        val block = DocumentBlock(ancestry + DocumentBlock.Level(node.type, node.attrs, makeId(), ordinal))
                        appendLine(node, block)
                    }
                    Kind.ATOM -> {
                        // An atomic block still occupies a line, so it can be selected, deleted and typed after.
                        val block = DocumentBlock(ancestry + DocumentBlock.Level(node.type, node.attrs, makeId(), ordinal))
                        output.append(ATOM, RunAttributes(block, atom = DocumentAtom(node)))
                        appendSeparator(block)
                    }
                }
            }
        }

        private fun appendLine(node: DocNode, block: DocumentBlock) {
            if (DocumentNodeType.isCodeBlock(block.leafType)) {
                // A code block's newlines live inside its text node: breaks within the block.
                val code = node.children.mapNotNull { it.text }.joinToString("").replace("\n", LINE_BREAK)
                output.append(code, RunAttributes(block))
            } else {
                appendInline(node.children, block)
            }
            appendSeparator(block)
        }

        /** The `\n` ending a line carries the line's block, so an empty block has a character to hang on. */
        private fun appendSeparator(block: DocumentBlock) {
            output.append(BLOCK_SEPARATOR, RunAttributes(block))
        }

        private fun appendInline(nodes: List<DocNode>, block: DocumentBlock) {
            for (node in nodes) {
                val marks = node.marks?.takeIf { it.isNotEmpty() }?.let(::DocumentMarks)
                when {
                    node.type == DocumentNodeType.TEXT ->
                        // A newline inside a text node would split the line, so it becomes a break within it.
                        output.append((node.text ?: "").replace("\n", LINE_BREAK), RunAttributes(block, marks))
                    DocumentNodeType.isHardBreak(node.type) -> output.append(LINE_BREAK, RunAttributes(block, marks))
                    else -> output.append(ATOM, RunAttributes(block, marks, DocumentAtom(node)))
                }
            }
        }

        /** Drops the separator after the final block: a document does not end with a blank line unless it ends with an empty block. */
        fun finish(): AttributedText {
            if (output.length > 0 && output.string.endsWith(BLOCK_SEPARATOR)) {
                val lastBlock = output.inheritedAttributes(output.length)!!.block
                output.delete(DocRange(output.length - 1, 1))
                // An empty last block now has no character of its own: it reads its block from the
                // separator before it. That separator belongs to a line that takes its block from
                // its own first character, so it can carry the empty block's instead — which is what
                // keeps a new list item or the paragraph after a final heading from becoming a copy
                // of the line above. (Not when that line is empty too: it needs its separator.)
                val s = output.string
                if (s.endsWith(BLOCK_SEPARATOR) && s.length >= 2 && s[s.length - 2] != '\n') {
                    output.setBlock(DocRange(s.length - 1, 1), lastBlock)
                } else {
                    // Nothing before it can: the text is the one empty block, or follows another empty one.
                    output.trailingBlock = lastBlock
                }
            }
            return output.build()
        }
    }
}

/**
 * Reads the editor's text back into a document — the inverse of [DocumentProjection]. Nothing is
 * inferred from how the text looks: the block and mark attributes are the document.
 */
object DocumentBuilder {

    fun document(text: AttributedText): DocNode {
        val lines = lines(text)
        return DocNode(DocumentNodeType.DOC, content = nodes(lines, 0, lines.size, 0))
    }

    private class Line(val block: DocumentBlock, val node: DocNode)

    private fun lines(text: AttributedText): List<Line> {
        val string = text.string
        val result = ArrayList<Line>()
        var start = 0
        while (start <= string.length) {
            val separator = string.indexOf(DocumentProjection.BLOCK_SEPARATOR, start)
            val end = if (separator < 0) string.length else separator
            val content = DocRange.of(start, end)
            val block = block(content, text)
            result += Line(block, leaf(block, content, text))
            if (separator < 0) break
            start = separator + 1
        }
        return result
    }

    /**
     * A line's block: its own characters carry it, and an empty line still has its separator. Only a
     * document ending in an empty line has nothing to read, and that line takes the block before it.
     */
    private fun block(content: DocRange, text: AttributedText): DocumentBlock {
        if (content.length == 0 && content.location == text.length) text.trailingBlock?.let { return it }
        if (text.length == 0) return DocumentBlock.defaultParagraph
        val block = text.block(minOf(content.location, text.length - 1)) ?: DocumentBlock.defaultParagraph
        // An empty line cannot be an atomic block — a rule always projects to exactly one character —
        // and taking the inherited block at its word would emit a second copy of the node above.
        if (content.length == 0 && block.leafType in DocumentNodeType.atomics) {
            return block.replacingLeaf(DocumentNodeType.PARAGRAPH, null)
        }
        return block
    }

    private fun leaf(block: DocumentBlock, content: DocRange, text: AttributedText): DocNode {
        atomicBlock(content, text, block)?.let { return it }
        val children = if (DocumentNodeType.isCodeBlock(block.leafType)) codeContent(content, text) else inlineNodes(content, text)
        // ProseMirror rejects `content: []` on a node that wants inline content.
        return DocNode(block.leafType, attrs = block.attrs, content = children.takeIf { it.isNotEmpty() })
    }

    /** A line that is exactly the one character its own block's atomic node projected to. */
    private fun atomicBlock(content: DocRange, text: AttributedText, block: DocumentBlock): DocNode? {
        if (content.length != 1) return null
        val atom = text.atom(content.location) ?: return null
        return atom.node.takeIf { it.type == block.leafType }
    }

    private fun codeContent(range: DocRange, text: AttributedText): List<DocNode> {
        if (range.length == 0) return emptyList()
        val code = text.substring(range).replace(DocumentProjection.LINE_BREAK, "\n")
        return if (code.isEmpty()) emptyList() else listOf(DocNode.text(code))
    }

    private fun inlineNodes(range: DocRange, text: AttributedText): List<DocNode> {
        if (range.length == 0) return emptyList()
        val result = ArrayList<DocNode>()
        for ((runRange, attributes) in text.runs(range)) {
            val atom = attributes.atom
            if (atom != null) {
                // One atom per character: a run of two equal atoms is two nodes.
                repeat(runRange.length) { result += atom.node }
                continue
            }
            result += inlineNodes(text.substring(runRange), attributes.marks?.marks.orEmpty())
        }
        return coalesced(result)
    }

    private fun inlineNodes(text: String, marks: List<com.neutrino.shared.core.ooxml.DocMark>): List<DocNode> {
        val result = ArrayList<DocNode>()
        text.split(DocumentProjection.LINE_BREAK).forEachIndexed { index, segment ->
            if (index > 0) result += DocNode(DocumentNodeType.HARD_BREAK)
            if (segment.isNotEmpty()) result += DocNode.text(segment, marks.takeIf { it.isNotEmpty() })
        }
        return result
    }

    /** Neighbouring text nodes with the same marks merged, so a save does not split text differently each time. */
    private fun coalesced(nodes: List<DocNode>): List<DocNode> {
        val result = ArrayList<DocNode>()
        for (node in nodes) {
            val previous = result.lastOrNull()
            if (node.type == DocumentNodeType.TEXT && previous != null && previous.type == DocumentNodeType.TEXT && previous.marks == node.marks) {
                result[result.lastIndex] = previous.copy(text = (previous.text ?: "") + (node.text ?: ""))
            } else {
                result += node
            }
        }
        return result
    }

    /**
     * Regroups the flat lines into the tree their ancestries describe: at each depth, consecutive
     * lines sharing the same container *instance* become its children.
     */
    private fun nodes(lines: List<Line>, from: Int, to: Int, depth: Int): List<DocNode> {
        val result = ArrayList<DocNode>()
        var index = from
        while (index < to) {
            val levels = lines[index].block.levels
            if (depth >= levels.size - 1) {
                result += lines[index].node
                index++
                continue
            }
            val container = levels[depth]
            var end = index + 1
            while (end < to) {
                val other = lines[end].block.levels
                if (other.size - 1 <= depth || !other[depth].isSameInstance(container)) break
                end++
            }
            val children = nodes(lines, index, end, depth + 1)
            result += DocNode(container.type, attrs = container.attrs, content = children.takeIf { it.isNotEmpty() })
            index = end
        }
        return result
    }
}
