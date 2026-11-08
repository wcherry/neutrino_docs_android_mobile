package com.neutrino.docs.ui.editor

import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Editable
import android.text.Layout
import android.text.Spanned
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.text.style.ReplacementSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import com.neutrino.docs.core.document.AttributedText
import com.neutrino.docs.core.document.DocRange
import com.neutrino.docs.core.document.DocumentBlock
import com.neutrino.docs.core.document.DocumentDecoration
import com.neutrino.docs.core.document.DocumentMarkType
import com.neutrino.docs.core.document.DocumentMarks
import com.neutrino.docs.core.document.DocumentNodeType
import com.neutrino.docs.core.document.TextLines
import com.neutrino.docs.core.editing.ImageAlignment
import com.neutrino.docs.core.editing.ImageAttributes
import com.neutrino.shared.core.ooxml.intValue
import com.neutrino.shared.core.ooxml.stringValue
import kotlin.math.roundToInt

/** Every span the renderer adds: presentation only, cleared and re-derived, never read back. */
interface PresentationSpan

/** The colours the editor draws with, resolved from the Compose theme so dark mode is right. */
data class EditorPalette(
    val text: Int,
    val secondary: Int,
    val link: Int,
    val code: Int,
    val codeBackground: Int,
    val rule: Int,
    val highlight: Int,
    val placeholder: Int,
    val findMatch: Int,
    val findCurrent: Int,
)

/** How the document looks, as the iOS `DocumentStyle` describes it, in pixels. */
class EditorStyle(
    val density: Float,
    /** The body size in pixels. */
    val bodySize: Float,
    val typeface: Typeface,
    val palette: EditorPalette,
) {
    val indentWidth = 22 * density
    val markerWidth = 20 * density
    val gutterWidth = 16 * density

    fun headingScale(level: Int): Float = when (level) {
        1 -> 1.75f
        2 -> 1.5f
        3 -> 1.32f
        4 -> 1.2f
        5 -> 1.12f
        else -> 1.06f
    }
}

/**
 * Lays the presentation spans over the editor's text, line by line, from the document attributes
 * the [AttributedText] carries. The analogue of the iOS projection's styling and decorations.
 */
class EditorRenderer(private val images: EditorImageStore, private val columnWidth: () -> Int) {

    var style: EditorStyle? = null
    /** Line indices folded away, which draw as nothing. */
    var hiddenLines: Set<Int> = emptySet()

    /** Re-derives every span in the lines `range` touches (all of them when null). */
    fun render(editable: Editable, text: AttributedText, range: DocRange? = null) {
        val style = style ?: return
        if (editable.length != text.length) return
        val lines = TextLines.ranges(text)
        val decorations = DocumentDecoration.of(text).groupBy { it.line }
        val first = range?.let { TextLines.lineIndex(text, it.location) } ?: 0
        val last = range?.let { TextLines.lineIndex(text, it.end) } ?: (lines.size - 1)
        val from = lines[first].location
        val to = minOf(editable.length, DocumentEditingRanges.withSeparator(lines[last], text.string).end)
        // Spans that merely touch the range from outside (a paragraph span ending at `from`) stay.
        for (span in editable.getSpans(from, to, PresentationSpan::class.java)) {
            val s = editable.getSpanStart(span)
            val e = editable.getSpanEnd(span)
            if (e > from && s < to || s == e && s in from..to || (s >= from && e <= to)) editable.removeSpan(span)
        }
        for (index in first..last) renderLine(editable, text, lines[index], index, decorations[index].orEmpty(), style)
    }

    private fun renderLine(editable: Editable, text: AttributedText, line: DocRange, index: Int, decorations: List<DocumentDecoration>, style: EditorStyle) {
        val paragraph = DocumentEditingRanges.withSeparator(line, text.string)
        val block = TextLines.block(line, text)

        if (index in hiddenLines) {
            if (paragraph.length > 0) editable.setSpan(HiddenSpan(), paragraph.location, paragraph.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return
        }

        // Paragraph-level: indentation and markers, spacing, alignment.
        if (paragraph.length > 0) {
            val quoteDepth = block.levels.dropLast(1).count { it.type == DocumentNodeType.BLOCKQUOTE }
            val marker = decorations.firstOrNull { it.kind is DocumentDecoration.Kind.Bullet || it.kind is DocumentDecoration.Kind.OrderedMarker || it.kind is DocumentDecoration.Kind.Checkbox }?.kind
            val isCode = DocumentNodeType.isCodeBlock(block.leafType)
            val spacing = style.bodySize * (if (DocumentNodeType.isHeading(block.leafType)) 0.4f else 0.25f)
            editable.setSpan(BlockSpan(style, block.containerDepth, block.listItem != null, marker, quoteDepth, isCode, spacing.roundToInt()),
                paragraph.location, paragraph.end, Spanned.SPAN_PARAGRAPH)
            when (block.attrs?.get("textAlign")?.stringValue) {
                "center" -> Layout.Alignment.ALIGN_CENTER
                "right" -> Layout.Alignment.ALIGN_OPPOSITE
                else -> null
            }?.let { editable.setSpan(AlignSpan(it), paragraph.location, paragraph.end, Spanned.SPAN_PARAGRAPH) }
        }
        if (line.length == 0) return

        // Block-level character styling.
        if (DocumentNodeType.isHeading(block.leafType)) {
            val level = block.attrs?.get("level")?.intValue ?: 1
            editable.setSpan(ScaleSpan(style.headingScale(level)), line.location, line.end, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
            editable.setSpan(BoldSpan(), line.location, line.end, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
        }
        if (DocumentNodeType.isCodeBlock(block.leafType)) {
            editable.setSpan(MonoSpan(), line.location, line.end, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
            editable.setSpan(ColorSpan(style.palette.code), line.location, line.end, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
        }

        // Marks and atoms, run by run.
        val onlyAtom = line.length == 1 && text.atom(line.location) != null
        for ((r, attrs) in text.runs(line)) {
            attrs.marks?.let { applyMarks(editable, r, it, style) }
            val atom = attrs.atom ?: continue
            for (offset in r.location until r.end) {
                val node = atom.node
                val span: ReplacementSpan = when {
                    node.type == DocumentNodeType.IMAGE -> PictureSpan(images, node.attr("src")?.stringValue ?: "", ImageAttributes.of(node.attrs),
                        node.attr("alt")?.stringValue ?: "", isBlock = onlyAtom, style, columnWidth)
                    DocumentNodeType.isRule(node.type) -> RuleSpan(style, columnWidth)
                    else -> ChipSpan(atomLabel(node.type, node.attrs), style, isBlock = onlyAtom)
                }
                editable.setSpan(span, offset, offset + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun applyMarks(editable: Editable, r: DocRange, marks: DocumentMarks, style: EditorStyle) {
        fun set(span: Any) = editable.setSpan(span, r.location, r.end, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
        for (mark in marks.marks) {
            when (mark.type) {
                DocumentMarkType.BOLD -> set(BoldSpan())
                DocumentMarkType.ITALIC -> set(ItalicSpan())
                DocumentMarkType.UNDERLINE -> set(UnderSpan())
                DocumentMarkType.STRIKE -> set(StrikeSpan())
                DocumentMarkType.CODE -> {
                    set(MonoSpan())
                    set(ColorSpan(style.palette.code))
                    set(BackSpan(style.palette.codeBackground))
                }
                DocumentMarkType.LINK -> {
                    set(ColorSpan(style.palette.link))
                    set(UnderSpan())
                }
                DocumentMarkType.HIGHLIGHT -> set(BackSpan(mark.attr("color")?.stringValue?.let(ImageAttributes::color) ?: style.palette.highlight))
                DocumentMarkType.SUPERSCRIPT -> set(ShiftSpan(0.35f))
                DocumentMarkType.SUBSCRIPT -> set(ShiftSpan(-0.2f))
                DocumentMarkType.TEXT_STYLE -> {
                    mark.attr("color")?.stringValue?.let(ImageAttributes::color)?.takeIf { it != ImageAttributes.CURRENT_COLOR }?.let { set(ColorSpan(it)) }
                    mark.attr("fontSize")?.let { v -> (v.intValue?.toFloat() ?: v.stringValue?.takeWhile { it.isDigit() || it == '.' }?.toFloatOrNull()) }
                        ?.let { pt -> set(SizeSpan((pt * style.density).roundToInt())) }
                }
                // A mark this app does not model — a tracked change, a comment anchor — travels with the text and draws nothing.
            }
        }
    }

    companion object {
        fun atomLabel(type: String, attrs: Map<String, com.neutrino.shared.core.ooxml.DocValue>?): String = when (type) {
            "tableOfContents" -> "Table of contents"
            "sectionBreak", "pageBreak", "page_break" -> "Page break"
            "sheetEmbed" -> "Sheet: " + (attrs?.get("title")?.stringValue ?: "Embedded sheet")
            "diagramEmbed" -> "Diagram: " + (attrs?.get("title")?.stringValue ?: "Embedded diagram")
            "footnote" -> "note"
            "docField" -> "{{" + (attrs?.get("code")?.stringValue?.let { if (it == "custom") attrs["arg"]?.stringValue ?: it else it } ?: "field") + "}}"
            "crossRef" -> "reference"
            else -> type
        }
    }
}

object DocumentEditingRanges {
    fun withSeparator(line: DocRange, s: String): DocRange = if (line.end < s.length && s[line.end] == '\n') DocRange(line.location, line.length + 1) else line
}

// ── Character spans ─────────────────────────────────────────────────────────────────────────────

class BoldSpan : StyleSpan(Typeface.BOLD), PresentationSpan
class ItalicSpan : StyleSpan(Typeface.ITALIC), PresentationSpan
class UnderSpan : UnderlineSpan(), PresentationSpan
class StrikeSpan : StrikethroughSpan(), PresentationSpan
class ColorSpan(color: Int) : ForegroundColorSpan(color), PresentationSpan
class BackSpan(color: Int) : BackgroundColorSpan(color), PresentationSpan
class SizeSpan(px: Int) : AbsoluteSizeSpan(px), PresentationSpan

class ScaleSpan(private val scale: Float) : MetricAffectingSpan(), PresentationSpan {
    override fun updateDrawState(tp: TextPaint) { tp.textSize *= scale }
    override fun updateMeasureState(tp: TextPaint) { tp.textSize *= scale }
}

class MonoSpan : MetricAffectingSpan(), PresentationSpan {
    override fun updateDrawState(tp: TextPaint) = apply(tp)
    override fun updateMeasureState(tp: TextPaint) = apply(tp)
    private fun apply(tp: TextPaint) {
        val style = tp.typeface?.style ?: Typeface.NORMAL
        tp.typeface = Typeface.create(Typeface.MONOSPACE, style)
    }
}

/** Superscript and subscript: smaller, and moved off the baseline. */
class ShiftSpan(private val fraction: Float) : MetricAffectingSpan(), PresentationSpan {
    override fun updateDrawState(tp: TextPaint) {
        tp.baselineShift -= (tp.textSize * fraction).toInt()
        tp.textSize *= 0.75f
    }
    override fun updateMeasureState(tp: TextPaint) {
        tp.baselineShift -= (tp.textSize * fraction).toInt()
        tp.textSize *= 0.75f
    }
}

/** Highlights from Find, kept apart from the presentation so re-rendering does not clear them. */
class FindSpan(color: Int) : BackgroundColorSpan(color)

class AlignSpan(alignment: Layout.Alignment) : AlignmentSpan.Standard(alignment), PresentationSpan

// ── The paragraph span ──────────────────────────────────────────────────────────────────────────

/**
 * Indentation for nesting, the list marker drawn in its own column, quote bars, the code panel and
 * the space after a paragraph — what the iOS editor draws beside its lines, in one paragraph span.
 * A bullet is drawn because the block says the line is a list item: there is no `- ` in the text.
 */
class BlockSpan(
    private val style: EditorStyle,
    private val depth: Int,
    private val isListItem: Boolean,
    private val marker: DocumentDecoration.Kind?,
    private val quoteDepth: Int,
    private val isCode: Boolean,
    private val spacingAfter: Int,
) : LeadingMarginSpan, LineBackgroundSpan, LineHeightSpan, PresentationSpan {

    /** Where the text starts: a list item's text clears its marker column. */
    val margin: Int get() = (style.indentWidth * depth).roundToInt()

    val checkbox: DocumentDecoration.Kind.Checkbox? get() = marker as? DocumentDecoration.Kind.Checkbox

    override fun getLeadingMargin(first: Boolean): Int = margin

    override fun drawLeadingMargin(c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int, text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout?) {
        val spanned = text as? Spanned ?: return
        val paragraphStart = spanned.getSpanStart(this)
        val saved = Paint(p)
        // Quote bars, one per level, on every line of the paragraph.
        if (quoteDepth > 0) {
            p.color = style.palette.secondary
            p.style = Paint.Style.FILL
            for (i in 0 until quoteDepth) {
                val bx = x + style.indentWidth * i + style.indentWidth * 0.3f
                c.drawRect(bx, top.toFloat(), bx + 3 * style.density, bottom.toFloat(), p)
            }
        }
        if (first && start == paragraphStart && marker != null) {
            val markerX = x + margin - style.markerWidth
            p.color = style.palette.secondary
            p.textSize = style.bodySize
            when (marker) {
                is DocumentDecoration.Kind.Bullet -> {
                    val r = style.bodySize * 0.16f
                    p.style = if (marker.depth % 2 == 0) Paint.Style.FILL else Paint.Style.STROKE
                    p.strokeWidth = style.density * 1.2f
                    c.drawCircle(markerX + style.markerWidth * 0.4f, baseline - style.bodySize * 0.32f, r, p)
                }
                is DocumentDecoration.Kind.OrderedMarker -> {
                    p.style = Paint.Style.FILL
                    p.textAlign = Paint.Align.RIGHT
                    c.drawText(marker.label, markerX + style.markerWidth - style.density * 3, baseline.toFloat(), p)
                }
                is DocumentDecoration.Kind.Checkbox -> {
                    val size = style.bodySize * 0.8f
                    val left = markerX
                    val boxTop = baseline - size
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = style.density * 1.5f
                    c.drawRoundRect(RectF(left, boxTop, left + size, boxTop + size), style.density * 3, style.density * 3, p)
                    if (marker.checked) {
                        p.color = style.palette.link
                        c.drawLine(left + size * 0.2f, boxTop + size * 0.55f, left + size * 0.42f, boxTop + size * 0.78f, p)
                        c.drawLine(left + size * 0.42f, boxTop + size * 0.78f, left + size * 0.82f, boxTop + size * 0.25f, p)
                    }
                }
                else -> {}
            }
        }
        p.set(saved)
    }

    override fun drawBackground(c: Canvas, p: Paint, left: Int, right: Int, top: Int, baseline: Int, bottom: Int, text: CharSequence, start: Int, end: Int, lineNumber: Int) {
        if (!isCode) return
        val color = p.color
        p.color = style.palette.codeBackground
        c.drawRect((left + margin).toFloat() - style.density * 4, top.toFloat(), right.toFloat(), bottom.toFloat(), p)
        p.color = color
    }

    override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
        val spanned = text as? Spanned ?: return
        // Only the paragraph's last line gets the space after it.
        if (end == spanned.getSpanEnd(this)) {
            fm.descent += spacingAfter
            fm.bottom += spacingAfter
        }
    }
}

// ── Atoms ───────────────────────────────────────────────────────────────────────────────────────

/** A folded line: no width, no height. */
class HiddenSpan : ReplacementSpan(), PresentationSpan {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        fm?.let { it.ascent = 0; it.descent = 0; it.top = 0; it.bottom = 0 }
        return 0
    }
    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {}
}

/** A horizontal rule: the one character it projects to drawn as a line across the column. */
class RuleSpan(private val es: EditorStyle, private val columnWidth: () -> Int) : ReplacementSpan(), PresentationSpan {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int = maxOf(1, columnWidth() - (es.density * 8).toInt())
    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val p = Paint(paint).apply { color = es.palette.rule; strokeWidth = es.density }
        val mid = (top + bottom) / 2f
        canvas.drawLine(x, mid, x + columnWidth() - es.density * 8, mid, p)
    }
}

/** A node this app shows but does not edit — an embed, a field, the table of contents — as a labelled chip. */
class ChipSpan(private val label: String, private val es: EditorStyle, private val isBlock: Boolean) : ReplacementSpan(), PresentationSpan {
    private fun textPaint(paint: Paint) = Paint(paint).apply { textSize = paint.textSize * 0.85f; color = es.palette.secondary }
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
        (textPaint(paint).measureText(label) + es.density * 12).roundToInt()
    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val p = textPaint(paint)
        val w = p.measureText(label) + es.density * 12
        val bg = Paint(paint).apply { color = es.palette.codeBackground }
        canvas.drawRoundRect(RectF(x, y + paint.ascent(), x + w, y + paint.descent()), es.density * 5, es.density * 5, bg)
        canvas.drawText(label, x + es.density * 6, y.toFloat() - es.density, p)
    }
}

/**
 * A picture. A line that is nothing but a picture draws it at the column's width (or the width the
 * document gives), its height capped at 260dp unless the width was given — a cap for photographs
 * nobody has sized, not a rule — with border, shadow, tint and caption. A picture among words stays
 * a labelled chip: a paragraph cannot reflow around something the height of a photograph.
 */
class PictureSpan(
    private val images: EditorImageStore,
    val source: String,
    private val attributes: ImageAttributes,
    private val alt: String,
    val isBlock: Boolean,
    private val es: EditorStyle,
    private val columnWidth: () -> Int,
) : ReplacementSpan(), PresentationSpan {

    private val maxHeight get() = 260 * es.density
    private val loadingHeight get() = 120 * es.density
    private val captionGap get() = 6 * es.density

    /** The picture's frame within the line, computed by [getSize] and used by [draw]. */
    private var frame = RectF()

    private fun captionPaint(paint: Paint) = TextPaint(paint).apply { textSize = es.bodySize * 0.8f; color = es.palette.secondary }

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val available = maxOf(1, columnWidth() - (es.density * 4).toInt()).toFloat()
        val bitmap = images.bitmap(source)
        val natural = bitmap?.width?.toFloat()?.coerceAtLeast(1f) ?: available
        var width = attributes.drawnWidth(natural, available)
        var height = if (bitmap != null) width * bitmap.height / bitmap.width.coerceAtLeast(1) else loadingHeight
        if (attributes.width == null && height > maxHeight && bitmap != null) {
            height = maxHeight
            width = height * bitmap.width / bitmap.height.coerceAtLeast(1)
        }
        val captionHeight = if (attributes.caption.isNotEmpty()) (captionGap + captionPaint(paint).textSize * 1.3f) else 0f
        // Beside text, a picture sits in the line like a large glyph, as on iOS; alone, it takes the line and its alignment.
        val left = if (!isBlock) 0f else when (attributes.alignment.placement) {
            ImageAlignment.CENTER -> (available - width) / 2
            ImageAlignment.RIGHT -> available - width
            // No alignment is drawn left, matching the web's CSS.
            else -> 0f
        }
        frame = RectF(left, 0f, left + width, height)
        fm?.let {
            val total = (height + captionHeight).roundToInt()
            it.ascent = -total
            it.top = -total
            it.descent = (es.density * 6).toInt()
            it.bottom = it.descent
        }
        return if (isBlock) available.roundToInt() else (width + es.density * 2).roundToInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val captionHeight = if (attributes.caption.isNotEmpty()) captionGap + captionPaint(paint).textSize * 1.3f else 0f
        val pictureTop = y - (frame.height() + captionHeight)
        val rect = RectF(x + frame.left, pictureTop, x + frame.right, pictureTop + frame.height())
        val bitmap = images.bitmap(source)
        if (attributes.shadow.blur > 0 && bitmap != null) {
            val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                setShadowLayer(attributes.shadow.blur * es.density / 2, 0f, attributes.shadow.offsetY * es.density, ((attributes.shadow.opacity * 255).toInt() shl 24))
            }
            canvas.drawRoundRect(rect, es.density * 4, es.density * 4, shadow)
        }
        if (bitmap != null) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            attributes.appliedFilter?.let { p.colorFilter = ColorMatrixColorFilter(matrix(it)) }
            canvas.drawBitmap(bitmap, Rect(0, 0, bitmap.width, bitmap.height), rect, p)
        } else {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = es.palette.codeBackground }
            canvas.drawRoundRect(rect, es.density * 6, es.density * 6, p)
            val label = if (images.hasFailed(source)) "Image unavailable" + if (alt.isNotEmpty()) " — $alt" else "" else "Loading image…"
            val tp = captionPaint(paint).apply { textAlign = Paint.Align.CENTER }
            canvas.drawText(label, rect.centerX(), rect.centerY(), tp)
        }
        attributes.strokedBorder?.let { border ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.style = Paint.Style.STROKE
                strokeWidth = border.width * es.density
                color = if (border.color == ImageAttributes.CURRENT_COLOR) es.palette.text else border.color
                when (border.style) {
                    ImageAttributes.Border.Style.DASHED -> pathEffect = DashPathEffect(floatArrayOf(6 * es.density, 4 * es.density), 0f)
                    ImageAttributes.Border.Style.DOTTED -> pathEffect = DashPathEffect(floatArrayOf(1 * es.density, 3 * es.density), 0f)
                    else -> {}
                }
            }
            canvas.drawRect(rect, p)
        }
        if (attributes.caption.isNotEmpty()) {
            val tp = captionPaint(paint).apply { textAlign = Paint.Align.CENTER }
            canvas.drawText(attributes.caption, rect.centerX(), rect.bottom + captionGap + tp.textSize, tp)
        }
    }

    companion object {
        /** Brightness, contrast and saturation as one colour matrix, as CSS composes them. */
        fun matrix(f: ImageAttributes.Filter): ColorMatrix {
            val m = ColorMatrix().apply { setSaturation(f.saturation.toFloat()) }
            val c = f.contrast.toFloat()
            val b = f.brightness.toFloat()
            val t = (1 - c) * 127.5f
            m.postConcat(ColorMatrix(floatArrayOf(c * b, 0f, 0f, 0f, t, 0f, c * b, 0f, 0f, t, 0f, 0f, c * b, 0f, t, 0f, 0f, 0f, 1f, 0f)))
            return m
        }
    }
}
