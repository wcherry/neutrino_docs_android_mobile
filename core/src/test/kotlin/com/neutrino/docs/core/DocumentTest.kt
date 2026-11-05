package com.neutrino.docs.core

import com.neutrino.docs.core.document.DocRange
import com.neutrino.docs.core.document.DocumentBuilder
import com.neutrino.docs.core.document.DocumentDecoration
import com.neutrino.docs.core.document.DocumentMarks
import com.neutrino.docs.core.document.DocumentOutline
import com.neutrino.docs.core.document.DocumentProjection
import com.neutrino.docs.core.document.FrontMatter
import com.neutrino.docs.core.document.documentText
import com.neutrino.docs.core.editing.CodeFolding
import com.neutrino.docs.core.editing.DocumentAlignment
import com.neutrino.docs.core.editing.DocumentEditing
import com.neutrino.docs.core.editing.DocumentEditingCommand
import com.neutrino.docs.core.editing.DocumentInputRules
import com.neutrino.docs.core.editing.EditorDocumentState
import com.neutrino.docs.core.editing.EditorImageSelection
import com.neutrino.docs.core.editing.ImageAlignment
import com.neutrino.docs.core.editing.ImageAttributes
import com.neutrino.shared.core.ooxml.DocMark
import com.neutrino.shared.core.ooxml.DocNode
import com.neutrino.shared.core.ooxml.docInt
import com.neutrino.shared.core.ooxml.docNull
import com.neutrino.shared.core.ooxml.docString
import com.neutrino.shared.core.ooxml.intValue
import com.neutrino.shared.core.ooxml.stringValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun t(s: String, vararg marks: String) = DocNode.text(s, marks.map { DocMark(it) }.takeIf { it.isNotEmpty() })
internal fun p(vararg c: DocNode, attrs: Map<String, com.neutrino.shared.core.ooxml.DocValue>? = null) =
    DocNode("paragraph", attrs = attrs, content = c.toList().takeIf { it.isNotEmpty() })
internal fun h(level: Int, text: String) = DocNode("heading", attrs = mapOf("level" to docInt(level)), content = listOf(t(text)))
internal fun li(vararg c: DocNode) = DocNode("listItem", content = c.toList())
internal fun ul(vararg items: DocNode) = DocNode("bulletList", content = items.toList())
internal fun ol(vararg items: DocNode) = DocNode("orderedList", content = items.toList())
internal fun doc(vararg blocks: DocNode) = DocNode("doc", content = blocks.toList())

/** The projection and the builder are inverses: every guarantee in the editor rests on it. */
class ProjectionTest {

    private fun roundTrip(d: DocNode) = DocumentBuilder.document(DocumentProjection.project(d))

    @Test
    fun aDocumentWithOneOfEverythingSurvivesTheEditor() {
        val image = DocNode("image", attrs = mapOf("src" to docString("neutrino-drive:f1"), "alt" to docString("A cat")))
        val d = doc(
            h(1, "Title"),
            p(t("plain "), t("bold", "bold"), t(" and "), DocNode.text("link", listOf(DocMark("link", mapOf("href" to docString("https://x")))))),
            p(t("before"), DocNode("hardBreak"), t("after")),
            ul(li(p(t("one")), ul(li(p(t("nested"))))), li(p(t("two")))),
            ol(li(p(t("first")))),
            DocNode("blockquote", content = listOf(p(t("quoted")), ul(li(p(t("in a quote")))))),
            DocNode("codeBlock", attrs = mapOf("language" to docString("kotlin")), content = listOf(t("val x = 1\nval y = 2"))),
            DocNode("horizontalRule"),
            p(t("an "), image, t(" inline")),
            DocNode("table", content = listOf(DocNode("tableRow", content = listOf(
                DocNode("tableCell", attrs = mapOf("colspan" to docInt(1), "colwidth" to docNull), content = listOf(p(t("cell")))),
            )))),
            DocNode("sheetEmbed", attrs = mapOf("spreadsheetId" to docString("s1"))),
            DocNode("mysteryNode", attrs = mapOf("x" to docInt(1)), content = listOf(p(t("kept")))),
            p(t("tracked", "trackedInsertion")),
            p(),
            p(t("end")),
        )
        assertEquals(d, roundTrip(d))
    }

    @Test
    fun twoAdjacentListsStayTwoLists() {
        val d = doc(ul(li(p(t("a")))), ul(li(p(t("b")))))
        assertEquals(d, roundTrip(d))
    }

    @Test
    fun twoAdjacentIdenticalPicturesAreTwoNodes() {
        val image = DocNode("image", attrs = mapOf("src" to docString("neutrino-drive:f1")))
        val d = doc(p(image, image))
        assertEquals(d, roundTrip(d))
    }

    @Test
    fun anEmptyDocumentIsOneEmptyParagraph() {
        assertEquals("", DocumentProjection.project(doc()).string)
        assertEquals(doc(p()), DocumentBuilder.document(DocumentProjection.project(doc())))
    }

    @Test
    fun theTextHoldsNoSyntax() {
        val text = DocumentProjection.project(doc(h(2, "Heading"), ul(li(p(t("item")))), DocNode("blockquote", content = listOf(p(t("q"))))))
        assertEquals("Heading\nitem\nq", text.string)
    }

    @Test
    fun documentTextIsOneLinePerBlock() {
        assertEquals("a\nb\nc", doc(p(t("a")), ul(li(p(t("b")))), p(t("c"))).documentText)
    }

    @Test
    fun aCodeBlocksNewlinesAreBreaksWithinTheBlock() {
        val text = DocumentProjection.project(doc(DocNode("codeBlock", content = listOf(t("a\nb")))))
        assertEquals("a b", text.string)
    }
}

class EditingTest {

    private fun state(d: DocNode, start: Int, length: Int = 0) = EditorDocumentState.of(d, DocRange(start, length))

    @Test
    fun boldGoesOnAndComesOff() {
        val on = DocumentEditingCommand.BOLD.apply(state(doc(p(t("hello world"))), 0, 5))
        assertEquals(doc(p(t("hello", "bold"), t(" world"))), on.document)
        assertTrue(DocumentEditingCommand.BOLD.isActive(on))
        val off = DocumentEditingCommand.BOLD.apply(on)
        assertEquals(doc(p(t("hello world"))), off.document)
    }

    @Test
    fun aMarkIsAddedBesideAnUnknownOneWithoutLosingIt() {
        val s = state(doc(p(t("x", "trackedInsertion"))), 0, 1)
        assertEquals(listOf("trackedInsertion", "italic"), DocumentEditingCommand.ITALIC.apply(s).document.children[0].children[0].marks!!.map { it.type })
    }

    @Test
    fun headingIsASetAndChoosingItAgainGoesBackToBody() {
        val s = state(doc(p(t("Title"), attrs = mapOf("textAlign" to docString("center")))), 2)
        val h2 = DocumentEditingCommand.HEADING2.apply(s)
        val node = h2.document.children[0]
        assertEquals("heading", node.type)
        assertEquals(2, node.attr("level")?.intValue)
        assertEquals("center", node.attr("textAlign")?.stringValue)
        assertTrue(DocumentEditingCommand.HEADING2.isActive(h2))
        val back = DocumentEditingCommand.HEADING2.apply(h2)
        assertEquals("paragraph", back.document.children[0].type)
        assertEquals("center", back.document.children[0].attr("textAlign")?.stringValue)
    }

    @Test
    fun alignmentIsTheNodesAttributeAndLeftIsItsAbsence() {
        val s = state(doc(p(t("x"))), 0)
        val centred = DocumentEditing.setAlignment(s, DocumentAlignment.CENTER)
        assertEquals("center", centred.document.children[0].attr("textAlign")?.stringValue)
        assertNull(DocumentEditing.setAlignment(centred, DocumentAlignment.LEFT).document.children[0].attrs)
    }

    @Test
    fun aListGoesOnTwoLinesAsOneListAndComesOff() {
        val s = state(doc(p(t("a")), p(t("b"))), 0, 3)
        val listed = DocumentEditingCommand.BULLET_LIST.apply(s)
        assertEquals(doc(ul(li(p(t("a"))), li(p(t("b"))))), listed.document)
        assertTrue(DocumentEditingCommand.BULLET_LIST.isActive(listed))
        // A different kind replaces rather than nests.
        assertEquals(doc(ol(li(p(t("a"))), li(p(t("b"))))), DocumentEditingCommand.NUMBERED_LIST.apply(listed).document)
        assertEquals(doc(p(t("a")), p(t("b"))), DocumentEditingCommand.BULLET_LIST.apply(listed).document)
    }

    @Test
    fun aQuoteWrapsAndUnwraps() {
        val s = state(doc(p(t("a")), p(t("b"))), 0, 3)
        val quoted = DocumentEditingCommand.QUOTE.apply(s)
        assertEquals(doc(DocNode("blockquote", content = listOf(p(t("a")), p(t("b"))))), quoted.document)
        assertEquals(doc(p(t("a")), p(t("b"))), DocumentEditingCommand.QUOTE.apply(quoted).document)
    }

    @Test
    fun indentNestsUnderTheItemAboveAndOutdentLiftsOut() {
        val s = state(doc(ul(li(p(t("a"))), li(p(t("b"))))), 3)
        val nested = DocumentEditing.indent(s)
        assertEquals(doc(ul(li(p(t("a")), ul(li(p(t("b"))))))), nested.document)
        assertEquals(doc(ul(li(p(t("a"))), li(p(t("b"))))), DocumentEditing.indent(nested, outdent = true).document)
    }

    @Test
    fun indentOutsideAListDoesNothing() {
        val s = state(doc(p(t("a"))), 0)
        assertEquals(doc(p(t("a"))), DocumentEditing.indent(s).document)
    }

    @Test
    fun aLinkGoesOnTheSelection() {
        val linked = DocumentEditing.setLink(state(doc(p(t("see this"))), 4, 4), "https://example.com")
        val run = linked.document.children[0].children[1]
        assertEquals("this", run.text)
        assertEquals("https://example.com", run.mark("link")?.attr("href")?.stringValue)
        assertEquals(doc(p(t("see this"))), DocumentEditing.removeLink(linked).document)
    }

    @Test
    fun aTableGoesOnALineOfItsOwn() {
        val out = DocumentEditing.insertTable(state(doc(p(t("above"))), 5))
        assertEquals(listOf("paragraph", "table"), out.document.children.map { it.type })
        assertEquals("Column 1", out.text.substring(out.selection))
    }

    @Test
    fun aTableReplacesAnEmptyLine() {
        val out = DocumentEditing.insertTable(state(doc(p(t("above")), p()), 6))
        assertEquals(listOf("paragraph", "table"), out.document.children.map { it.type })
    }

    @Test
    fun aPictureIsInsertedAndItsPropertiesRewriteOnlyIt() {
        val node = EditorImageSelection.node("neutrino-drive:f1", "A cat", null, ImageAttributes.none)
        val inserted = DocumentEditing.insertAtom(node, state(doc(p(t("ab"))), 1))
        assertEquals(doc(p(t("a"), node, t("b"))), inserted.document)
        val range = DocRange(1, 1)
        val selection = EditorImageSelection(range, inserted.text.atom(1)!!.node)
        val edited = selection.node(selection.descriptor.copy(attributes = selection.attributes.copy(width = "50%", alignment = ImageAlignment.CENTER)))
        val replaced = DocumentEditing.replaceAtom(inserted, range, edited)
        assertEquals("50%", replaced.document.children[0].children[1].attr("width")?.stringValue)
        assertEquals("center", replaced.document.children[0].children[1].attr("alignment")?.stringValue)
        assertEquals(doc(p(t("ab"))), DocumentEditing.removeAtom(replaced, range).document)
    }

    @Test
    fun imageAttributesKeepWhatTheyDoNotUnderstand() {
        val attrs = ImageAttributes.of(mapOf("src" to docString("x"), "width" to docString("300px"), "futureThing" to docInt(3), "nothing" to docNull))
        assertEquals(docInt(3), attrs.proseMirrorAttributes["futureThing"])
        assertEquals(docNull, attrs.proseMirrorAttributes["nothing"])
        assertEquals(150f, attrs.copy(width = "50%").drawnWidth(1000f, 300f))
        assertEquals(300f, attrs.drawnWidth(1000f, 1000f))
        assertEquals("brightness(80%) saturate(120%)", ImageAttributes.Filter.css(0.8, 1.0, 1.2))
        assertEquals(ImageAttributes.Filter(0.8, 1.0, 1.2), ImageAttributes.Filter.parse("brightness(80%) saturate(120%)"))
        assertEquals(2f, ImageAttributes.Border.parse("2px dashed #333")!!.width)
        assertNull(ImageAttributes.Border.parse("none"))
    }

    @Test
    fun aPendingBoldIsActiveAtTheCaret() {
        val s = state(doc(p(t("x"))), 1).with(pendingMarks = DocumentMarks(listOf(DocMark("bold"))))
        assertTrue(DocumentEditingCommand.BOLD.isActive(s))
        val typed = DocumentInputRules.insert("y", s)!!
        assertEquals(doc(p(t("x"), t("y", "bold"))), typed.document)
    }
}

class InputRulesTest {

    private fun typing(d: DocNode, at: Int) = EditorDocumentState.of(d, DocRange(at, 0))

    /** The text typed so far, then a space, as the editor hands it over. */
    private fun type(marker: String, rest: String = ""): EditorDocumentState? {
        val s = typing(doc(p(t(marker + rest))), marker.length)
        return DocumentInputRules.insert(' ', s)
    }

    @Test
    fun hashesMakeHeadingsAndAreConsumed() {
        val out = type("##", "Title")!!
        assertEquals(doc(h(2, "Title")), out.document)
        assertEquals(0, out.selection.location)
    }

    @Test
    fun aDashMakesABulletAndANumberMakesAnOrderedListFromIt() {
        assertEquals(doc(ul(li(p(t("x"))))), type("-", "x")!!.document)
        val ordered = type("3.", "x")!!.document.children[0]
        assertEquals("orderedList", ordered.type)
        assertEquals(3, ordered.attr("start")?.intValue)
        assertEquals("blockquote", type(">", "x")!!.document.children[0].type)
        assertEquals("taskList", type("[]", "x")!!.document.children[0].type)
    }

    @Test
    fun aHashInAHeadingIsJustAHash() {
        assertNull(DocumentInputRules.insert(' ', typing(doc(h(1, "#x")), 1)))
    }

    @Test
    fun aMarkerOnTheLastEmptyLineStillConverts() {
        val s = typing(doc(p(t("a")), p(t("#"))), 3)
        assertEquals(doc(p(t("a")), h(1, "")).children[1].type, DocumentInputRules.insert(' ', s)!!.document.children[1].type)
    }

    @Test
    fun returnInAListMakesANewItemAndOnAnEmptyItemEndsTheList() {
        val s = typing(doc(ul(li(p(t("one"))))), 3)
        val next = DocumentInputRules.newline(s)!!
        assertEquals(doc(ul(li(p(t("one"))), li(p()))), next.document)
        val ended = DocumentInputRules.newline(next)!!
        assertEquals(doc(ul(li(p(t("one")))), p()), ended.document)
    }

    @Test
    fun returnMidItemSplitsIt() {
        val split = DocumentInputRules.newline(typing(doc(ul(li(p(t("onetwo"))))), 3))!!
        assertEquals(doc(ul(li(p(t("one"))), li(p(t("two"))))), split.document)
    }

    @Test
    fun returnAfterAHeadingIsProse() {
        val out = DocumentInputRules.newline(typing(doc(h(1, "Title")), 5))!!
        assertEquals(doc(h(1, "Title"), p()).children.map { it.type }, out.document.children.map { it.type })
    }

    @Test
    fun returnInAParagraphIsLeftToTheTextView() {
        assertNull(DocumentInputRules.newline(typing(doc(p(t("a"))), 1)))
    }

    @Test
    fun returnInACodeBlockStaysInTheBlock() {
        val out = DocumentInputRules.newline(typing(doc(DocNode("codeBlock", content = listOf(t("ab")))), 1))!!
        assertEquals(doc(DocNode("codeBlock", content = listOf(t("a\nb")))), out.document)
    }

    @Test
    fun backspaceAtTheStartOfAnItemLiftsItOutAndAHeadingDemotes() {
        assertEquals(doc(p(t("x"))), DocumentInputRules.deleteBackward(typing(doc(ul(li(p(t("x"))))), 0))!!.document)
        assertEquals(doc(p(t("x"))), DocumentInputRules.deleteBackward(typing(doc(h(2, "x")), 0))!!.document)
        assertNull(DocumentInputRules.deleteBackward(typing(doc(p(t("ab"))), 1)))
    }

    @Test
    fun bracketsCloseWrapAndStepOver() {
        val opened = DocumentInputRules.insert('(', typing(doc(p(t("a"))), 1))!!
        assertEquals("a()", opened.string)
        assertEquals(2, opened.selection.location)
        val stepped = DocumentInputRules.insert(')', opened)!!
        assertEquals("a()", stepped.string)
        assertEquals(3, stepped.selection.location)
        val wrapped = DocumentInputRules.insert('[', EditorDocumentState.of(doc(p(t("word"))), DocRange(0, 4)))!!
        assertEquals("[word]", wrapped.string)
        // Not before a word.
        assertNull(DocumentInputRules.insert('(', typing(doc(p(t("foo"))), 0)))
        // Backspace between an auto pair removes both.
        assertEquals("a", DocumentInputRules.deleteBackward(opened)!!.string)
    }

    @Test
    fun threeBackticksOpenACodeBlock() {
        val out = DocumentInputRules.insert('`', typing(doc(p(t("``code"))), 2))!!
        assertEquals(doc(DocNode("codeBlock", content = listOf(t("code")))), out.document)
    }

    @Test
    fun typingAtTheStartOfALineTakesThatLinesBlockNotThePreviousOnes() {
        val text = DocumentProjection.project(doc(p(t("a")), h(1, "b")))
        val attrs = DocumentInputRules.typingAttributes(text, 2, null)
        assertEquals("heading", attrs.block.leafType)
        assertNull(attrs.marks)
        val afterBold = DocumentInputRules.typingAttributes(DocumentProjection.project(doc(p(t("x", "bold")))), 1, null)
        assertEquals(listOf("bold"), afterBold.marks!!.marks.map { it.type })
    }

    @Test
    fun typingAfterARuleIsAParagraphNotAnotherRule() {
        val text = DocumentProjection.project(doc(DocNode("horizontalRule")))
        assertEquals("paragraph", DocumentInputRules.typingAttributes(text, 1, null).block.leafType)
    }
}

class StructureTest {

    @Test
    fun theOutlineNestsSearchesAndCollapses() {
        val text = DocumentProjection.project(doc(h(1, "Intro"), p(t("x")), h(2, "Détails"), h(4, "Deep"), h(1, "End"), DocNode("codeBlock", content = listOf(t("# not a heading")))))
        val outline = DocumentOutline.of(text)
        assertEquals(listOf("Intro", "Détails", "Deep", "End"), outline.headings.map { it.title })
        assertEquals(0, outline.headings[1].parentId)
        assertEquals(1, outline.headings[2].parentId)
        assertEquals(listOf(1, 2), outline.descendantIds(0))
        assertEquals(listOf("Intro", "Détails"), outline.filtered("details").map { it.title })
        assertEquals(listOf("Intro", "End"), outline.visibleHeadings(setOf(0)).map { it.title })
        assertEquals(8, outline.headings[1].characterOffset)
    }

    @Test
    fun foldingFindsSectionsAndListRuns() {
        val text = DocumentProjection.project(doc(h(1, "A"), p(t("a")), ul(li(p(t("1"))), li(p(t("2")))), h(1, "B"), p(t("b"))))
        val regions = CodeFolding.regions(text)
        assertEquals(listOf(0 to 3, 2 to 3, 4 to 5), regions.map { it.startLine to it.endLine })
        assertEquals(setOf(1, 2, 3), CodeFolding.hiddenLines(setOf(0), regions))
    }

    @Test
    fun decorationsComeFromTheStructure() {
        val text = DocumentProjection.project(doc(
            ol(li(p(t("a")), p(t("a2"))), li(p(t("b")))),
            DocNode("blockquote", content = listOf(p(t("q")))),
            DocNode("taskList", content = listOf(DocNode("taskItem", attrs = mapOf("checked" to com.neutrino.shared.core.ooxml.docBool(true)), content = listOf(p(t("done")))))),
            DocNode("horizontalRule"),
        ))
        val kinds = DocumentDecoration.of(text).map { it.line to it.kind }
        assertEquals(listOf(
            0 to DocumentDecoration.Kind.OrderedMarker("1.", 0),
            2 to DocumentDecoration.Kind.OrderedMarker("2.", 0),
            3 to DocumentDecoration.Kind.QuoteBar(1),
            4 to DocumentDecoration.Kind.Checkbox(true, 0),
            5 to DocumentDecoration.Kind.ThematicBreak,
        ), kinds)
    }

    @Test
    fun frontMatterReadsTheSubsetDocumentsUse() {
        val fm = FrontMatter.read("title: \"My Doc\"\ntags: [swift, ios]\nauthors:\n  - Ada\n  - Grace\n# comment\nplain: a, b")
        assertEquals("My Doc", fm.title)
        assertEquals(listOf("swift", "ios"), fm.tags)
        assertEquals(listOf("Ada", "Grace"), fm["authors"])
        assertEquals(listOf("a", "b"), fm["plain"])
        assertFalse(fm.isEmpty)
    }
}
