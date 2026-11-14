package com.neutrino.docs.core.editing

import com.neutrino.docs.core.document.AttributedText
import com.neutrino.docs.core.document.DocRange
import com.neutrino.docs.core.document.DocumentNodeType
import com.neutrino.shared.core.ooxml.DocNode
import com.neutrino.shared.core.ooxml.DocValue
import com.neutrino.shared.core.ooxml.docString
import com.neutrino.shared.core.ooxml.stringValue
import kotlin.math.roundToInt

/**
 * The `neutrino-drive:<fileId>` reference a document stores in place of a picture — the same
 * spelling as the web's `driveImageRef`. A Drive file is end-to-end encrypted, so its download URL
 * serves ciphertext no image view could draw: a reference is resolved by downloading and decrypting.
 * `http(s):` and `data:` sources from older documents are left exactly as they are and still drawn.
 */
object DriveImageReference {
    const val SCHEME = "neutrino-drive:"

    fun source(fileId: String) = SCHEME + fileId

    fun fileId(source: String): String? {
        if (!source.startsWith(SCHEME)) return null
        return source.removePrefix(SCHEME).trim().ifEmpty { null }
    }

    fun isReference(source: String) = fileId(source) != null

    /**
     * The source of every picture in [text], in document order, each once — what has to be fetched
     * before the document can be drawn somewhere that cannot wait for it, such as a printed page.
     */
    fun sources(text: AttributedText): List<String> {
        val found = LinkedHashSet<String>()
        for ((_, attributes) in text.runs()) {
            val node = attributes.atom?.node ?: continue
            if (node.type != DocumentNodeType.IMAGE) continue
            node.attr("src")?.stringValue?.takeIf { it.isNotEmpty() }?.let { found += it }
        }
        return found.toList()
    }
}

/** Where a picture sits in its column — the web's `alignment` attribute. */
enum class ImageAlignment(val raw: String, val label: String) {
    NONE("none", "Default"), LEFT("left", "Left"), CENTER("center", "Centre"), RIGHT("right", "Right"),
    FLOAT_LEFT("float-left", "Float Left"), FLOAT_RIGHT("float-right", "Float Right");

    /** A picture here is a line of its own, so a float is drawn as the ordinary alignment on its side. */
    val isFloat: Boolean get() = this == FLOAT_LEFT || this == FLOAT_RIGHT

    val placement: ImageAlignment get() = when (this) {
        FLOAT_LEFT -> LEFT
        FLOAT_RIGHT -> RIGHT
        else -> this
    }

    companion object {
        fun named(raw: String?): ImageAlignment = entries.firstOrNull { it.raw == raw?.trim()?.lowercase() } ?: NONE
    }
}

/** The web's four shadow presets, with its values, so a document looks the same in both. */
enum class ImageShadow(val raw: String, val label: String, val offsetY: Float, val blur: Float, val opacity: Float) {
    NONE("none", "None", 0f, 0f, 0f), SM("sm", "Small", 1f, 4f, 0.22f), MD("md", "Medium", 4f, 10f, 0.28f), LG("lg", "Large", 10f, 28f, 0.38f);

    companion object {
        fun named(raw: String?): ImageShadow = entries.firstOrNull { it.raw == raw?.trim()?.lowercase() } ?: NONE
    }
}

/**
 * How a picture is laid out and painted — the web's `AdvancedImageExtension` attributes, exactly:
 * `width`, `alignment`, `caption`, `border`, `shadow`, `imageFilter`. Anything else the node carries
 * rides through in [other], so a later web release can add an attribute without phones deleting it.
 */
data class ImageAttributes(
    /** CSS dimension — `300px`, `50%`, `auto` — or null for the picture's own size. */
    val width: String? = null,
    val alignment: ImageAlignment = ImageAlignment.NONE,
    val caption: String = "",
    /** CSS border shorthand, e.g. `2px solid #333`. */
    val border: String? = null,
    val shadow: ImageShadow = ImageShadow.NONE,
    /** CSS filter, e.g. `brightness(80%) contrast(110%)`. */
    val filter: String? = null,
    val other: Map<String, DocValue> = emptyMap(),
) {
    val isEmpty: Boolean
        get() = width == null && alignment == ImageAlignment.NONE && caption.isEmpty() && border == null &&
            shadow == ImageShadow.NONE && filter == null && other.isEmpty()

    /** Only what is set: an unstyled image stays an unstyled node, not six redundant attributes. */
    val proseMirrorAttributes: Map<String, DocValue>
        get() = buildMap {
            width?.takeIf { it.isNotEmpty() }?.let { put("width", docString(it)) }
            if (alignment != ImageAlignment.NONE) put("alignment", docString(alignment.raw))
            if (caption.isNotEmpty()) put("caption", docString(caption))
            border?.takeIf { it.isNotEmpty() }?.let { put("border", docString(it)) }
            if (shadow != ImageShadow.NONE) put("shadow", docString(shadow.raw))
            filter?.takeIf { it.isNotEmpty() }?.let { put("imageFilter", docString(it)) }
            putAll(other)
        }

    /**
     * The width to draw at. `50%` is of the column, not the picture — what somebody dragging the web's
     * slider meant. `auto` and an unreadable value fall back to the natural fit.
     */
    fun drawnWidth(naturalWidth: Float, availableWidth: Float): Float {
        val w = width?.trim()?.lowercase()
        if (w.isNullOrEmpty() || w == "auto") return minOf(naturalWidth, availableWidth)
        val resolved = if (w.endsWith("%")) w.dropLast(1).toDoubleOrNull()?.let { availableWidth * it.toFloat() / 100 } else length(w)
        if (resolved == null || resolved <= 0) return minOf(naturalWidth, availableWidth)
        return minOf(resolved, availableWidth)
    }

    val strokedBorder: Border? get() = Border.parse(border)
    val appliedFilter: Filter? get() = Filter.parse(filter)

    data class Border(val width: Float, val style: Style, val color: Int) {
        enum class Style { SOLID, DASHED, DOTTED, DOUBLE, NONE, HIDDEN }

        companion object {
            /** `2px solid #333`, in any order, as CSS allows. */
            fun parse(css: String?): Border? {
                val value = css?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                var width: Float? = null
                var style: Style? = null
                var color: Int? = null
                for (part in value.split(' ').filter { it.isNotEmpty() }) {
                    Style.entries.firstOrNull { it.name.lowercase() == part.lowercase() }?.let { style = it; continue }
                    length(part)?.let { width = it; continue }
                    color(part)?.let { color = it }
                }
                val resolved = style ?: Style.SOLID
                if (resolved == Style.NONE || resolved == Style.HIDDEN) return null
                val w = width ?: 1f
                if (w <= 0) return null
                return Border(w, resolved, color ?: CURRENT_COLOR)
            }
        }
    }

    /** `brightness(90%) contrast(110%) saturate(120%)` as multipliers, 1 being untouched. */
    data class Filter(val brightness: Double = 1.0, val contrast: Double = 1.0, val saturation: Double = 1.0) {
        val isIdentity: Boolean get() = brightness == 1.0 && contrast == 1.0 && saturation == 1.0

        companion object {
            fun parse(css: String?): Filter? {
                if (css.isNullOrBlank()) return null
                val f = Filter(percentage("brightness", css) ?: 1.0, percentage("contrast", css) ?: 1.0, percentage("saturate", css) ?: 1.0)
                return if (f.isIdentity) null else f
            }

            /** The CSS this describes, omitting components left at 100%, as the web writes it. */
            fun css(brightness: Double, contrast: Double, saturation: Double): String? {
                val parts = ArrayList<String>()
                if (brightness != 1.0) parts += "brightness(${(brightness * 100).roundToInt()}%)"
                if (contrast != 1.0) parts += "contrast(${(contrast * 100).roundToInt()}%)"
                if (saturation != 1.0) parts += "saturate(${(saturation * 100).roundToInt()}%)"
                return parts.joinToString(" ").ifEmpty { null }
            }

            private fun percentage(name: String, css: String): Double? {
                val start = css.indexOf("$name(")
                if (start < 0) return null
                val rest = css.substring(start + name.length + 1)
                val close = rest.indexOf(')')
                if (close < 0) return null
                var value = rest.substring(0, close)
                val isPercent = value.endsWith("%")
                if (isPercent) value = value.dropLast(1)
                val number = value.toDoubleOrNull() ?: return null
                return if (isPercent) number / 100 else number
            }
        }
    }

    /** The width presets the panel offers. */
    enum class WidthPreset(val raw: String, val label: String) { QUARTER("25%", "25%"), HALF("50%", "50%"), THREE_QUARTERS("75%", "75%"), FULL("100%", "100%") }

    companion object {
        val none = ImageAttributes()

        /** What the picture *is* rather than how it is drawn. */
        val identityKeys = setOf("src", "alt", "title")
        val proseMirrorKeys = setOf("width", "alignment", "caption", "border", "shadow", "imageFilter")

        /** A sentinel for "the text colour", resolved at draw time. */
        const val CURRENT_COLOR = 0x00FFFFFF

        val borderPresets = listOf("Thin" to "1px solid #cccccc", "Dark" to "2px solid #333333", "Dashed" to "2px dashed #888888")

        fun of(attrs: Map<String, DocValue>?): ImageAttributes {
            attrs ?: return none
            return ImageAttributes(
                width = attrs["width"]?.stringValue,
                alignment = ImageAlignment.named(attrs["alignment"]?.stringValue),
                caption = attrs["caption"]?.stringValue ?: "",
                border = attrs["border"]?.stringValue,
                shadow = ImageShadow.named(attrs["shadow"]?.stringValue),
                filter = attrs["imageFilter"]?.stringValue,
                other = attrs.filterKeys { it !in identityKeys && it !in proseMirrorKeys },
            )
        }

        fun length(raw: String): Float? {
            var value = raw.lowercase()
            for (unit in listOf("px", "pt")) if (value.endsWith(unit)) value = value.dropLast(unit.length)
            return value.toFloatOrNull()
        }

        /** `#333`, `#334455`, `#33445566`, `rgb()`/`rgba()`, or a common CSS name, as ARGB. */
        fun color(raw: String): Int? {
            val value = raw.trim().lowercase()
            if (value.startsWith("#")) {
                val hex = value.substring(1)
                val digits = when (hex.length) {
                    3, 4 -> hex.map { "$it$it" }
                    6, 8 -> hex.chunked(2)
                    else -> return null
                }
                val channels = digits.map { it.toIntOrNull(16) ?: return null }
                val a = if (channels.size > 3) channels[3] else 255
                return (a shl 24) or (channels[0] shl 16) or (channels[1] shl 8) or channels[2]
            }
            if (value.startsWith("rgb")) {
                val inside = value.substringAfter('(', "").substringBefore(')')
                val parts = inside.split(',', ' ').filter { it.isNotBlank() }.mapNotNull { it.trim().toDoubleOrNull() }
                if (parts.size < 3) return null
                val a = if (parts.size > 3) (parts[3] * 255).roundToInt() else 255
                return (a.coerceIn(0, 255) shl 24) or (parts[0].roundToInt().coerceIn(0, 255) shl 16) or
                    (parts[1].roundToInt().coerceIn(0, 255) shl 8) or parts[2].roundToInt().coerceIn(0, 255)
            }
            return when (value) {
                "black" -> 0xFF000000.toInt()
                "white" -> 0xFFFFFFFF.toInt()
                "gray", "grey" -> 0xFF808080.toInt()
                "red" -> 0xFFFF3B30.toInt()
                "green" -> 0xFF34C759.toInt()
                "blue" -> 0xFF007AFF.toInt()
                "orange" -> 0xFFFF9500.toInt()
                "yellow" -> 0xFFFFCC00.toInt()
                "purple" -> 0xFFAF52DE.toInt()
                "transparent" -> 0
                "currentcolor" -> CURRENT_COLOR
                else -> null
            }
        }
    }
}

/** A whole image as the panel edits it: where it comes from, what it says, how it is drawn. */
data class EditorImageDescriptor(val alt: String = "", val source: String = "", val title: String? = null, val attributes: ImageAttributes = ImageAttributes.none)

/** The picture the properties panel is open on: the whole node, and where it stands in the text. */
data class EditorImageSelection(val range: DocRange, val node: DocNode) {
    val source: String get() = node.attr("src")?.stringValue ?: ""
    val attributes: ImageAttributes get() = ImageAttributes.of(node.attrs)
    val descriptor: EditorImageDescriptor
        get() = EditorImageDescriptor(node.attr("alt")?.stringValue ?: "", source, node.attr("title")?.stringValue, attributes)

    fun node(image: EditorImageDescriptor): DocNode = node(image.source, image.alt, image.title, image.attributes)

    companion object {
        /** Only the attributes actually set — the web's defaults are null. */
        fun node(source: String, alt: String, title: String?, attributes: ImageAttributes): DocNode {
            val attrs = attributes.proseMirrorAttributes.toMutableMap()
            attrs["src"] = docString(source)
            if (alt.isNotEmpty()) attrs["alt"] = docString(alt)
            if (!title.isNullOrEmpty()) attrs["title"] = docString(title)
            return DocNode(DocumentNodeType.IMAGE, attrs = attrs)
        }
    }
}

/** Naming a picture's Drive file for what its bytes are. */
object EditorImage {
    /** The stem of whatever the source knew, with the extension the stored bytes actually have. */
    fun fileName(suggestion: String?, extension: String): String {
        val stem = suggestion?.substringBeforeLast('.', suggestion)?.trim().orEmpty()
        return (stem.ifEmpty { "Image" }) + "." + extension
    }

    /** What a picture's bytes are, from their leading bytes: MIME type and extension. */
    fun sniff(data: ByteArray): Pair<String, String>? {
        fun at(i: Int) = if (i < data.size) data[i].toInt() and 0xFF else -1
        return when {
            at(0) == 0xFF && at(1) == 0xD8 -> "image/jpeg" to "jpg"
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "image/png" to "png"
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> "image/gif" to "gif"
            at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 && at(8) == 0x57 && at(9) == 0x45 -> "image/webp" to "webp"
            at(0) == 0x42 && at(1) == 0x4D -> "image/bmp" to "bmp"
            data.size > 12 && String(data, 4, 8, Charsets.ISO_8859_1).let { it.startsWith("ftyphei") || it.startsWith("ftypmif") || it.startsWith("ftyphe") } -> "image/heic" to "heic"
            else -> null
        }
    }
}
