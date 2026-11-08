package com.neutrino.docs.ui.editor

import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.TextPaint
import com.neutrino.docs.core.document.AttributedText
import com.neutrino.docs.core.editing.DriveImageReference
import com.neutrino.shared.android.print.NeutrinoPrintJob
import com.neutrino.shared.android.print.TextPageSource

/**
 * Turns the document into the [NeutrinoPrintJob] the shared printing code paginates and sends to
 * the printer — the Android counterpart of iOS Docs' `DocumentPrint`.
 *
 * Only what is about this app's documents lives here: the same [EditorRenderer] the editor styles
 * its text with, run over a fresh copy of the text with a paper palette, and the pictures fetched
 * first. A bullet, a list number, a quote bar or a code panel is a span on screen, so it is a span on
 * paper. The pages, the running header, page numbers and the print dialog are the shared library's.
 *
 * A printed document is the whole document: folds are opened.
 */
object DocumentPrint {

    /** Body text on paper, in points. The editor's size suits a phone at arm's length, not a page. */
    const val BODY_POINTS = 11f

    /**
     * The editor's colours for paper: always the light theme's, whatever the phone is set to —
     * Android resolves a colour when its span is made, so a dark theme would print white on white.
     */
    val paperPalette = EditorPalette(
        text = 0xFF1C1B1F.toInt(),
        secondary = 0xFF5F5F66.toInt(),
        link = 0xFF0B57D0.toInt(),
        code = 0xFFAF52DE.toInt(),
        codeBackground = 0xFFF0F0F4.toInt(),
        rule = 0xFFC7C7CC.toInt(),
        highlight = 0x59FFCC00,
        placeholder = 0xFFF0F0F4.toInt(),
        findMatch = 0,
        findCurrent = 0,
    )

    /** The print job for [text] — the editor's text as it stands, unsaved edits included — once its pictures are in hand. */
    suspend fun job(title: String, text: AttributedText, typeface: Typeface, images: EditorImageStore): NeutrinoPrintJob {
        images.preload(DriveImageReference.sources(text))
        return layOut(title, text, typeface, images)
    }

    /** The job, drawing whatever pictures [images] already holds. */
    fun layOut(title: String, text: AttributedText, typeface: Typeface, images: EditorImageStore): NeutrinoPrintJob {
        val styled = SpannableStringBuilder(text.string)
        lateinit var source: TextPageSource
        // A picture or a rule sizes itself to the column, which is known once the paper is.
        val renderer = EditorRenderer(images) { maxOf(1, source.layoutWidth) }
        // One point per density-independent pixel: the iOS editor's measurements are points, and
        // the shared layout is in points, so an indent is the same 22pt on paper from either phone.
        renderer.style = EditorStyle(density = 1f, bodySize = BODY_POINTS, typeface = typeface, palette = paperPalette)
        renderer.render(styled, text)

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = BODY_POINTS
            this.typeface = typeface
            color = paperPalette.text
        }
        source = TextPageSource(styled, paint)
        return NeutrinoPrintJob(name = title, source = source)
    }
}
