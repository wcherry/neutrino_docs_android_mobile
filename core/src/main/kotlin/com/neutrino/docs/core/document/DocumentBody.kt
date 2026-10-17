package com.neutrino.docs.core.document

import com.neutrino.shared.core.ooxml.DocModel
import com.neutrino.shared.core.ooxml.DocNode
import com.neutrino.shared.core.ooxml.DocValue
import com.neutrino.shared.core.ooxml.DocxCodec
import com.neutrino.shared.core.ooxml.stringValue
import java.util.logging.Logger

/**
 * A document as this app works with it: the tree the editor edits, plus the layout block the web
 * keeps beside it. The layout travels with the body through every path an edit takes — the editing
 * session, the offline queue, a named version — so a document saved from a phone keeps the header,
 * footer, watermark and theme it was given on the web.
 */
data class DocumentBody(val doc: DocNode, val layout: DocValue? = null) {

    val blocks: List<DocNode> get() = doc.children

    /** Every image the document points at, in document order — what has to be resolved before saving. */
    val imageSources: List<String>
        get() {
            val out = ArrayList<String>()
            val seen = HashSet<String>()
            fun walk(node: DocNode) {
                if (node.type == DocumentNodeType.IMAGE) {
                    val src = node.attr("src")?.stringValue
                    if (!src.isNullOrEmpty() && seen.add(src)) out += src
                }
                node.children.forEach(::walk)
            }
            walk(doc)
            return out
        }

    companion object {
        val empty = DocumentBody(DocNode(DocumentNodeType.DOC, content = emptyList()))
    }
}

/**
 * The one place the stored wire format is entered and left. A Neutrino document is a real Word
 * document (issue #127); decode just after decrypting, encode just before encrypting, and nowhere
 * else in the app knows the body is not the tree.
 *
 * A stored body that is not a `.docx` is read as plain text, one paragraph per line: kept, never
 * guessed at, and saved back as a real document.
 */
object DocumentCodec {

    private val log = Logger.getLogger("DocumentCodec")

    fun decode(stored: ByteArray): DocumentBody {
        if (stored.isEmpty()) return DocumentBody.empty
        if (!DocxCodec.isDocument(stored)) {
            log.info("body is not a .docx; reading it as plain text")
            return DocumentBody(plainText(stored.decodeToString()))
        }
        return try {
            val model = DocxCodec.decode(stored)
            DocumentBody(model.doc, model.meta)
        } catch (e: Exception) {
            // A package this app cannot read is not one to offer to overwrite; the editor shows it
            // empty and the save path refuses to write over content it never loaded.
            log.warning("decode failed: ${e.javaClass.simpleName}")
            DocumentBody.empty
        }
    }

    private fun plainText(stored: String): DocNode =
        DocNode(DocumentNodeType.DOC, content = stored.split("\n").map { line -> paragraphNode(if (line.isEmpty()) emptyList() else listOf(DocNode.text(line))) })

    /**
     * The stored `.docx`. `title` is what a `{{title}}` field resolves to — the file name without
     * `.docx`. `images` is what the caller could resolve; anything else is written as a placeholder.
     */
    fun encode(body: DocumentBody, title: String, images: Map<String, ByteArray> = emptyMap()): ByteArray = try {
        DocxCodec.encode(DocModel(body.doc, body.layout), title, images)
    } catch (e: Exception) {
        log.warning("encode failed, writing an empty document: ${e.javaClass.simpleName}")
        DocxCodec.emptyDocument(title)
    }
}
