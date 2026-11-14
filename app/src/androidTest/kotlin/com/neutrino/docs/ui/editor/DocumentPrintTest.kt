package com.neutrino.docs.ui.editor

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.text.Spanned
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.neutrino.docs.core.document.AttributedText
import com.neutrino.docs.core.document.DocumentProjection
import com.neutrino.docs.core.editing.DriveImageReference
import com.neutrino.shared.android.print.NeutrinoPrintJob
import com.neutrino.shared.android.print.PrintPageRenderer
import com.neutrino.shared.android.print.TextPageSource
import com.neutrino.shared.core.ooxml.DocNode
import com.neutrino.shared.core.ooxml.docString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Printing a document: what lands on paper, and that its pictures are in hand first. Pagination,
 * header and page numbers are the shared library's and tested there.
 */
@RunWith(AndroidJUnit4::class)
class DocumentPrintTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun text(s: String) = DocNode.text(s)
    private fun paragraph(s: String) = DocNode("paragraph", content = listOf(text(s)))
    private fun doc(vararg c: DocNode): AttributedText = DocumentProjection.project(DocNode("doc", content = c.toList()))
    private fun image(src: String) = DocNode("image", attrs = mapOf("src" to docString(src)))

    private fun list(type: String, vararg items: String) =
        DocNode(type, content = items.map { DocNode("listItem", content = listOf(paragraph(it))) })

    private fun store(resolve: suspend (String) -> ByteArray = { error("no drive") }) =
        EditorImageStore(CoroutineScope(Dispatchers.Main), resolve, OkHttpClient(), 2048)

    /** Lays out and writes on the main thread, as the print framework does. */
    private fun <T> onMain(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }

    private fun pdf(job: NeutrinoPrintJob): File = onMain {
        File(context.cacheDir, "doc-print-${System.nanoTime()}.pdf").also { file ->
            file.outputStream().use { PrintPageRenderer(job).writePdf(it) }
        }
    }

    private fun pages(file: File): Int =
        PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { it.pageCount }

    private fun render(file: File): Bitmap =
        PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
            r.openPage(0).use { page ->
                Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.WHITE)
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                }
            }
        }

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun styledText(job: NeutrinoPrintJob): Spanned = (job.source as TextPageSource).text as Spanned

    // ── The job ──────────────────────────────────────────────────────────────────────────────

    @Test fun theJobIsNamedAndHeadedAfterTheDocument() {
        val job = onMain { DocumentPrint.job("Board minutes", doc(paragraph("Hello")), Typeface.DEFAULT, store()) }
        assertEquals("Board minutes", job.name)
        assertEquals("Board minutes", job.header)
        assertEquals(1, pages(pdf(job)))
    }

    @Test fun aLongDocumentRunsOntoSeveralPages() {
        val body = (1..120).map { paragraph("Paragraph $it. " + "Lorem ipsum dolor sit amet. ".repeat(6)) }
        val job = onMain { DocumentPrint.job("Long", doc(*body.toTypedArray()), Typeface.DEFAULT, store()) }
        assertTrue(pages(pdf(job)) > 1)
    }

    /** The editor's spans style the paper copy: list items get the paragraph span that draws their markers. */
    @Test fun listsAreStyledWithTheEditorsSpans() {
        val job = onMain { DocumentPrint.job("List", doc(list("orderedList", "First", "Second")), Typeface.DEFAULT, store()) }
        val styled = styledText(job)
        val blocks = styled.getSpans(0, styled.length, BlockSpan::class.java)
        assertEquals(2, blocks.size)
        assertTrue("list text clears its marker column", blocks.all { it.margin > 0 })
    }

    @Test fun bodyTextIsElevenPointWhateverTheEditorSize() {
        val job = onMain { DocumentPrint.job("a", doc(paragraph("Body")), Typeface.DEFAULT, store()) }
        assertEquals(DocumentPrint.BODY_POINTS, (job.source as TextPageSource).paint.textSize, 0f)
    }

    /** The paper palette, not the phone's theme: text prints dark. */
    @Test fun textPrintsDark() {
        val job = onMain { DocumentPrint.job("a", doc(paragraph("W".repeat(40))), Typeface.DEFAULT_BOLD, store()) }
        val page = render(pdf(job))
        var darkest = 255
        for (y in 60 until 120) for (x in 54 until 400) darkest = minOf(darkest, Color.red(page.getPixel(x, y)))
        assertTrue("darkest ink $darkest", darkest < 80)
    }

    // ── Pictures ─────────────────────────────────────────────────────────────────────────────

    @Test fun picturesAreFetchedBeforeThePagesAreDrawn() {
        val asked = mutableListOf<String>()
        val bytes = png()
        val images = store { asked += it; bytes }
        val text = doc(image(DriveImageReference.source("img-1")), paragraph("after"))

        onMain { DocumentPrint.job("a", text, Typeface.DEFAULT, images) }

        assertEquals(listOf("img-1"), asked)
        assertNotNull(onMain { images.bitmap(DriveImageReference.source("img-1")) })
    }

    @Test fun aPictureThatCannotBeFetchedIsMarkedFailedRatherThanLeftLoading() {
        val images = store { throw java.io.IOException("offline") }
        val source = DriveImageReference.source("gone")
        onMain { images.preload(listOf(source)) }
        assertTrue(onMain { images.hasFailed(source) })
    }
}
