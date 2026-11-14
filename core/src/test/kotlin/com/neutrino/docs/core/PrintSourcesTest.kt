package com.neutrino.docs.core

import com.neutrino.docs.core.document.DocumentProjection
import com.neutrino.docs.core.editing.DriveImageReference
import com.neutrino.shared.core.ooxml.DocNode
import com.neutrino.shared.core.ooxml.docString
import org.junit.Assert.assertEquals
import org.junit.Test

/** The pictures a printed document has to fetch before its pages can be drawn. */
class PrintSourcesTest {

    private fun image(src: String) = DocNode("image", attrs = mapOf("src" to docString(src)))
    private fun doc(vararg c: DocNode) = DocumentProjection.project(DocNode("doc", content = c.toList()))

    @Test fun picturesAreListedInDocumentOrder() {
        val text = doc(image("https://example.com/a.png"), p(t("between")), image(DriveImageReference.source("b")))
        assertEquals(listOf("https://example.com/a.png", "neutrino-drive:b"), DriveImageReference.sources(text))
    }

    @Test fun aPictureUsedTwiceIsFetchedOnce() {
        val text = doc(image("neutrino-drive:x"), p(t("and again")), image("neutrino-drive:x"))
        assertEquals(listOf("neutrino-drive:x"), DriveImageReference.sources(text))
    }

    @Test fun aDocumentWithoutPicturesNeedsNothing() {
        assertEquals(emptyList<String>(), DriveImageReference.sources(doc(p(t("Just words")))))
    }
}
