package com.neutrino.docs.core.document

/**
 * The editor's text: a string whose every character carries [RunAttributes] — the platform-free
 * counterpart of the `NSAttributedString` the iOS editor holds. The projection writes one, the
 * builder reads one, and every editing command is a function from one to another, which is what
 * keeps them testable on the JVM without an `EditText` in the loop.
 *
 * Immutable; edits go through a [Builder]. Runs are contiguous, non-empty and merged when adjacent
 * runs carry equal attributes, so two texts holding the same characters and attributes are equal.
 */
class AttributedText private constructor(
    val string: String,
    /** Run lengths and attributes, covering [string] exactly. */
    private val runLengths: IntArray,
    private val runAttributes: Array<RunAttributes>,
    /**
     * The block of an empty last line that no character can carry: the text is empty, or the line
     * before it is empty too. (Otherwise the separator before an empty last line carries its block —
     * see [hasBlocklessLastLine].) Null whenever there is no such line, so equal texts stay equal.
     */
    val trailingBlock: DocumentBlock? = null,
) {
    /** Start offset of each run, for binary search. */
    private val runStarts: IntArray = IntArray(runLengths.size).also { starts ->
        var at = 0
        for (i in runLengths.indices) {
            starts[i] = at
            at += runLengths[i]
        }
    }

    val length: Int get() = string.length
    val runCount: Int get() = runLengths.size

    private fun runIndex(at: Int): Int {
        var lo = 0
        var hi = runStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (runStarts[mid] <= at) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** The attributes of the character at `index`, or null for an empty text. */
    fun attributes(index: Int): RunAttributes? {
        if (runLengths.isEmpty()) return null
        return runAttributes[runIndex(index.coerceIn(0, length - 1))]
    }

    fun block(index: Int): DocumentBlock? = attributes(index)?.block
    fun marks(index: Int): DocumentMarks? = attributes(index)?.marks
    fun atom(index: Int): DocumentAtom? = attributes(index)?.atom

    /** The runs overlapping `range`, clipped to it, in order. */
    fun runs(range: DocRange = DocRange(0, length)): List<Pair<DocRange, RunAttributes>> {
        if (runLengths.isEmpty() || range.length <= 0) return emptyList()
        val out = ArrayList<Pair<DocRange, RunAttributes>>()
        var i = runIndex(range.location)
        while (i < runLengths.size && runStarts[i] < range.end) {
            val start = maxOf(runStarts[i], range.location)
            val end = minOf(runStarts[i] + runLengths[i], range.end)
            if (end > start) out += DocRange.of(start, end) to runAttributes[i]
            i++
        }
        return out
    }

    /** Whether the last line is empty with no separator before it free to carry its block. */
    val hasBlocklessLastLine: Boolean get() = blocklessLastLine(string)

    fun substring(range: DocRange): String = string.substring(range.location, range.end)

    /** The characters and attributes of `range`. */
    fun sub(range: DocRange): AttributedText = Builder().apply { for ((r, a) in runs(range)) append(substring(r), a) }.build()

    fun toBuilder(): Builder = Builder(this)

    override fun equals(other: Any?): Boolean {
        if (other !is AttributedText) return false
        return string == other.string && runLengths.contentEquals(other.runLengths) && runAttributes.contentEquals(other.runAttributes) &&
            trailingBlock == other.trailingBlock
    }

    override fun hashCode(): Int = string.hashCode() * 31 + runLengths.contentHashCode()

    override fun toString(): String = "AttributedText(" + runs().joinToString { (r, a) -> "\"${substring(r)}\"@${a.block.leafType}" } + ")"

    companion object {
        val empty = AttributedText("", IntArray(0), emptyArray())

        fun of(string: String, attributes: RunAttributes): AttributedText = Builder().apply { append(string, attributes) }.build()

        internal fun blocklessLastLine(s: CharSequence): Boolean =
            s.isEmpty() || s[s.length - 1] == '\n' && (s.length == 1 || s[s.length - 2] == '\n')
    }

    /** A mutable copy, edited in place and frozen with [build]. */
    class Builder() {
        private val text = StringBuilder()
        private val lengths = ArrayList<Int>()
        private val attrs = ArrayList<RunAttributes>()
        /** Kept by [build] only while the last line is [blockless][hasBlocklessLastLine]. */
        var trailingBlock: DocumentBlock? = null

        constructor(source: AttributedText) : this() {
            trailingBlock = source.trailingBlock
            text.append(source.string)
            source.runLengths.forEach { lengths += it }
            attrs += source.runAttributes
        }

        val length: Int get() = text.length
        val string: String get() = text.toString()

        fun append(string: String, attributes: RunAttributes): Builder {
            if (string.isEmpty()) return this
            text.append(string)
            if (attrs.isNotEmpty() && attrs.last() == attributes) lengths[lengths.lastIndex] = lengths.last() + string.length
            else { lengths += string.length; attrs += attributes }
            return this
        }

        fun append(other: AttributedText): Builder {
            for ((r, a) in other.runs()) append(other.substring(r), a)
            return this
        }

        /** Splits so a run boundary falls at `offset`; returns the index of the run starting there. */
        private fun split(offset: Int): Int {
            var at = 0
            for (i in lengths.indices) {
                if (at == offset) return i
                val end = at + lengths[i]
                if (offset < end) {
                    val head = offset - at
                    lengths[i] = head
                    lengths.add(i + 1, end - offset)
                    attrs.add(i + 1, attrs[i])
                    return i + 1
                }
                at = end
            }
            return lengths.size
        }

        private fun attributesBefore(offset: Int): RunAttributes? {
            var at = 0
            for (i in lengths.indices) {
                at += lengths[i]
                if (offset <= at) return attrs[i]
            }
            return attrs.lastOrNull()
        }

        /** Replaces `range` with `string`, which carries `attributes`. */
        fun replace(range: DocRange, string: String, attributes: RunAttributes): Builder =
            replace(range, AttributedText.of(string, attributes))

        /** Replaces `range` with `replacement`'s characters and attributes. */
        fun replace(range: DocRange, replacement: AttributedText): Builder {
            val start = range.location.coerceIn(0, text.length)
            val end = range.end.coerceIn(start, text.length)
            val first = split(start)
            val last = split(end)
            for (i in last - 1 downTo first) { lengths.removeAt(i); attrs.removeAt(i) }
            text.replace(start, end, replacement.string)
            var index = first
            for ((r, a) in replacement.runs()) {
                lengths.add(index, r.length)
                attrs.add(index, a)
                index++
            }
            normalize()
            return this
        }

        fun insert(at: Int, inserted: AttributedText): Builder = replace(DocRange(at, 0), inserted)

        fun delete(range: DocRange): Builder = replace(range, AttributedText.empty)

        /** Rewrites the attributes of every character in `range`. */
        fun update(range: DocRange, transform: (RunAttributes) -> RunAttributes): Builder {
            val start = range.location.coerceIn(0, text.length)
            val end = range.end.coerceIn(start, text.length)
            if (end == start) return this
            val first = split(start)
            val last = split(end)
            for (i in first until last) attrs[i] = transform(attrs[i])
            normalize()
            return this
        }

        fun setBlock(range: DocRange, block: DocumentBlock) = update(range) { it.copy(block = block) }
        fun setAtom(range: DocRange, atom: DocumentAtom?) = update(range) { it.copy(atom = atom) }

        /** The attributes the character before `offset` carries — what a plain insertion there inherits. */
        fun inheritedAttributes(offset: Int): RunAttributes? = attributesBefore(offset)

        private fun normalize() {
            var i = 0
            while (i < lengths.size) {
                if (lengths[i] == 0) { lengths.removeAt(i); attrs.removeAt(i); continue }
                if (i > 0 && attrs[i - 1] == attrs[i]) {
                    lengths[i - 1] = lengths[i - 1] + lengths[i]
                    lengths.removeAt(i); attrs.removeAt(i)
                    continue
                }
                i++
            }
        }

        fun build(): AttributedText {
            normalize()
            val string = text.toString()
            return AttributedText(string, lengths.toIntArray(), attrs.toTypedArray(), trailingBlock.takeIf { blocklessLastLine(string) })
        }
    }
}
