package com.neutrino.docs.core

import com.neutrino.docs.core.document.AttributedText
import com.neutrino.docs.core.document.DocRange
import com.neutrino.docs.core.document.DocumentBuilder
import com.neutrino.docs.core.editing.DocumentInputRules
import com.neutrino.docs.core.editing.EditorDocumentState
import com.neutrino.shared.core.ooxml.DocNode
import org.junit.Assert.assertEquals
import org.junit.Test

/** Keystrokes one at a time, the way the editor feeds them: a rule first, else a plain insert. */
class TypingTest {
    private fun type(keys: String, start: EditorDocumentState = EditorDocumentState(AttributedText.empty, DocRange(0, 0))): EditorDocumentState {
        var state = start
        for (c in keys) {
            val at = EditorDocumentState(state.text, state.selection, null)
            state = (if (c == '\n') DocumentInputRules.newline(at) else DocumentInputRules.insert(c, at))
                ?: DocumentInputRules.insert(c.toString(), at)!!
        }
        return state
    }

    @Test fun headingOnTheFirstLineOfAnEmptyDocument() {
        assertEquals(doc(h(1, "Trip")), DocumentBuilder.document(type("# Trip").text))
    }

    @Test fun anOrderedListAfterLeavingABulletList() {
        val s = type("- a\nb\n\n1. first\nsecond")
        assertEquals(doc(ul(li(p(t("a"))), li(p(t("b")))), ol(li(p(t("first"))), li(p(t("second"))))),
            DocumentBuilder.document(s.text).let { d -> d.copy(content = d.content!!.map { it.copy(attrs = null) }) })
    }

    @Test fun aHeadingAfterABlankLineLeavesTheBlankLineAlone() {
        assertEquals(doc(p(t("intro")), p(), h(2, "Part")), DocumentBuilder.document(type("intro\n\n## Part").text))
    }

    @Test fun aListInAnEmptyDocumentThenBackToBody() {
        assertEquals(doc(ul(li(p(t("x")))), p(t("after"))), DocumentBuilder.document(type("- x\n\nafter").text).let { d -> d.copy(content = d.content!!.map { it.copy(attrs = null) }) })
    }

    @Test fun theEmptyHeadingSurvivesAReprojection() {
        val s = type("# ")
        assertEquals(doc(DocNode("heading", attrs = mapOf("level" to com.neutrino.shared.core.ooxml.docInt(1)))), DocumentBuilder.document(s.text))
        assertEquals(s.text, com.neutrino.docs.core.document.DocumentProjection.project(DocumentBuilder.document(s.text)))
    }
}
