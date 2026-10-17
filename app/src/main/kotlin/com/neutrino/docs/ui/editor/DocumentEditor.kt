package com.neutrino.docs.ui.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.Editable
import android.text.InputType
import android.text.Spanned
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.appcompat.widget.AppCompatEditText
import com.neutrino.docs.core.document.AttributedText
import com.neutrino.docs.core.document.DocRange
import com.neutrino.docs.core.document.DocumentBuilder
import com.neutrino.docs.core.document.DocumentDecoration
import com.neutrino.docs.core.document.DocumentMarks
import com.neutrino.docs.core.document.DocumentNodeType
import com.neutrino.docs.core.document.DocumentOutline
import com.neutrino.docs.core.document.DocumentProjection
import com.neutrino.docs.core.document.TextLines
import com.neutrino.docs.core.editing.CodeFolding
import com.neutrino.docs.core.editing.DocumentAlignment
import com.neutrino.docs.core.editing.DocumentEditing
import com.neutrino.docs.core.editing.DocumentEditingCommand
import com.neutrino.docs.core.editing.DocumentInputRules
import com.neutrino.docs.core.editing.EditorDocumentState
import com.neutrino.docs.core.editing.EditorImageDescriptor
import com.neutrino.docs.core.editing.EditorImageSelection
import com.neutrino.docs.core.editing.FoldableRegion
import com.neutrino.docs.core.editing.ImageAttributes
import com.neutrino.shared.core.ooxml.DocMark
import com.neutrino.shared.core.ooxml.DocNode
import com.neutrino.shared.core.ooxml.boolValue
import com.neutrino.shared.core.ooxml.docBool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the style bar shows about the selection, read in one pass. */
data class SelectionStatus(
    val headingLevel: Int = 0,
    val alignment: DocumentAlignment = DocumentAlignment.LEFT,
    val active: Set<DocumentEditingCommand> = emptySet(),
    val hasSelection: Boolean = false,
) {
    val paragraphStyle: DocumentEditingCommand get() = DocumentEditingCommand.paragraphStyle(headingLevel)
}

/**
 * The editor's model and everything that edits it.
 *
 * The [AttributedText] here is the document — the `EditText` shows it, with presentation spans the
 * renderer derives. A keystroke the text view applied is mirrored into the model with the
 * attributes the new characters inherit; a typing rule, a command, undo or replace computes a new
 * state with the pure core transforms and writes it back. Every command reads the model, never the
 * screen, so nothing can be applied against a stale document. Main-thread confined.
 */
class EditorController(private val scope: CoroutineScope, val images: EditorImageStore) {

    var view: DocumentEditText? = null
        private set
    val renderer = EditorRenderer(images) { view?.columnWidth ?: 1000 }

    var inputRules = DocumentInputRules.Options()
    var isEditable = true
        set(value) {
            field = value
            view?.let { it.isEnabled = true; it.showSoftInputOnFocus = value; it.isFocusableInTouchMode = true; it.isCursorVisible = value }
        }

    var text: AttributedText = AttributedText.empty
        private set
    private var selection = DocRange(0, 0)
    /** A toggle pressed at a caret: what the next characters carry. Forgotten when the caret moves. */
    private var pendingMarks: DocumentMarks? = null

    private val _status = MutableStateFlow(SelectionStatus())
    val status: StateFlow<SelectionStatus> = _status

    /** The document as the editor last produced it — rebuilt a beat after typing, at once after a command. */
    private val _document = MutableStateFlow(DocNode(DocumentNodeType.DOC, content = emptyList()))
    val document: StateFlow<DocNode> = _document

    val isEditing = MutableStateFlow(false)
    val canUndo = MutableStateFlow(false)
    val canRedo = MutableStateFlow(false)
    val foldable = MutableStateFlow<List<FoldableRegion>>(emptyList())
    val folded = MutableStateFlow<Set<Int>>(emptySet())
    val selectedImage = MutableStateFlow<EditorImageSelection?>(null)

    private data class Snapshot(val text: AttributedText, val selection: DocRange)
    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    private var lastTypingAt = 0L
    private var lastTypingEnd = -1
    private var publishJob: Job? = null

    init {
        images.onLoad = { src -> rerenderPictures(src) }
    }

    fun attach(view: DocumentEditText) {
        this.view = view
        view.controller = this
        view.applyModel(text, selection)
        renderer.render(view.editableText, text)
        isEditable = isEditable
    }

    fun detach() {
        view?.controller = null
        view = null
    }

    /** Replaces the whole document — a load, a restored version. Not an edit: it has no undo. */
    fun load(doc: DocNode) {
        text = DocumentProjection.project(doc)
        selection = DocRange(0, 0)
        undoStack.clear(); redoStack.clear()
        folded.value = emptySet()
        renderer.hiddenLines = emptySet()
        updateUndoState()
        _document.value = DocumentBuilder.document(text)
        view?.let {
            it.applyModel(text, selection)
            renderer.render(it.editableText, text)
        }
        refreshChrome()
    }

    private val state: EditorDocumentState get() = EditorDocumentState(text, selection, pendingMarks)

    // ── From the text view ──────────────────────────────────────────────────────────────────────

    fun onSelectionChanged(start: Int, end: Int) {
        val updated = DocRange.of(minOf(start, end), maxOf(start, end))
        if (updated != selection) pendingMarks = null
        selection = updated
        refreshStatus()
    }

    /**
     * The text view changed `[start, start + before)` into `inserted`. A typing rule gets first say
     * — Return in a list, `# `, a bracket, Backspace at a line's start — and otherwise the edit is
     * mirrored into the model, the new characters taking the attributes typing there implies.
     */
    fun onUserEdit(editStart: Int, editBefore: Int, editInserted: String) {
        // A keyboard composing a word rewrites the whole word for every key: "her" → "hero" arrives
        // as three characters replaced by four. Reduced to the one character it adds (or removes), so
        // the typing rules see the keystroke a hardware keyboard would have sent.
        var start = editStart
        var before = editBefore
        var inserted = editInserted
        if (before > 0 && start + before <= text.length) {
            val old = text.string.substring(start, start + before)
            if (inserted.length == before + 1 && inserted.startsWith(old)) {
                start += before; before = 0; inserted = inserted.substring(old.length)
            } else if (inserted.length == before - 1 && old.startsWith(inserted)) {
                start += inserted.length; before = 1; inserted = ""
            }
        }
        if (!isEditable) {
            // A read-only document: whatever the keyboard did is put back.
            view?.applyModel(text, selection)
            view?.let { renderer.render(it.editableText, text) }
            return
        }
        val pre = EditorDocumentState(text, DocRange(start, 0), pendingMarks)
        val rule: EditorDocumentState? = when {
            before == 0 && inserted.length == 1 && inserted[0] == '\n' -> DocumentInputRules.newline(pre, inputRules)
            before == 0 && inserted.length == 1 -> DocumentInputRules.insert(inserted[0], pre, inputRules)
            before == 1 && inserted.isEmpty() -> DocumentInputRules.deleteBackward(EditorDocumentState(text, DocRange(start + 1, 0)), inputRules)
            else -> null
        }
        if (rule != null) {
            pushUndo(force = true)
            write(rule)
            return
        }
        val attributes = DocumentInputRules.typingAttributes(text, start, pendingMarks)
        val typing = before <= 1 && inserted.length <= 1 && inserted != "\n"
        pushUndo(force = !(typing && start == lastTypingEnd && System.currentTimeMillis() - lastTypingAt < 1500 && inserted != " "))
        lastTypingAt = System.currentTimeMillis()
        lastTypingEnd = start + inserted.length
        text = text.toBuilder().replace(DocRange(start, before), inserted, attributes).build()
        if (inserted.isNotEmpty()) pendingMarks = null
        view?.let { renderer.render(it.editableText, text, DocRange(start, inserted.length)) }
        // A new line can change the folding and the outline; an ordinary keystroke cannot.
        if ('\n' in inserted || before > 0) refreshFolding()
        schedulePublish()
        refreshStatus()
    }

    /** Backspace with the caret at the very start: there is nothing for the text view to delete. */
    fun backspaceAtStart(): Boolean {
        if (!isEditable || selection != DocRange(0, 0)) return false
        val rule = DocumentInputRules.deleteBackward(state, inputRules) ?: return false
        pushUndo(force = true)
        write(rule)
        return true
    }

    // ── Commands ────────────────────────────────────────────────────────────────────────────────

    /** A mark at a caret is a statement about what comes next; everything else works on the selection's lines. */
    fun apply(command: DocumentEditingCommand) {
        if (!isEditable) return
        val markType = command.markType
        if (markType != null && selection.isEmpty) {
            val current = state.activeMarks
            pendingMarks = if (current.has(markType)) current.removing(markType) else current.adding(DocMark(markType))
            refreshStatus()
            return
        }
        commit(command.apply(state))
    }

    fun setAlignment(alignment: DocumentAlignment) = commit(DocumentEditing.setAlignment(state, alignment))

    fun setLink(href: String) {
        val trimmed = href.trim()
        commit(if (trimmed.isEmpty()) DocumentEditing.removeLink(state) else DocumentEditing.setLink(state, trimmed))
    }

    fun indent(outdent: Boolean = false) = commit(DocumentEditing.indent(state, outdent))

    fun insertImage(alt: String, source: String) =
        commit(DocumentEditing.insertAtom(EditorImageSelection.node(source, alt, null, ImageAttributes.none), state))

    fun selectImage(offset: Int) {
        val atom = text.atom(offset)?.takeIf { it.node.type == DocumentNodeType.IMAGE } ?: return
        selectedImage.value = EditorImageSelection(DocRange(offset, 1), atom.node)
    }

    /** Checked against the document as it is now: a sync or an undo could have moved the picture from under the panel. */
    private fun stillHolds(selection: EditorImageSelection) =
        selection.range.end <= text.length && text.atom(selection.range.location)?.node == selection.node

    fun updateSelectedImage(image: EditorImageDescriptor) {
        val sel = selectedImage.value ?: return
        if (stillHolds(sel)) commit(DocumentEditing.replaceAtom(state, sel.range, sel.node(image)))
        selectedImage.value = null
    }

    fun removeSelectedImage() {
        val sel = selectedImage.value ?: return
        if (stillHolds(sel)) commit(DocumentEditing.removeAtom(state, sel.range))
        selectedImage.value = null
    }

    /** A checkbox tapped: the task item's own `checked`. */
    fun toggleTask(line: Int) {
        if (!isEditable) return
        val lines = TextLines.ranges(text)
        val range = lines.getOrNull(line) ?: return
        val block = TextLines.block(range, text)
        val itemIndex = block.levels.size - 2
        val item = block.levels.getOrNull(itemIndex)?.takeIf { DocumentNodeType.isListItem(it.type) } ?: return
        val checked = item.attrs?.get("checked")?.boolValue ?: false
        val updatedItem = item.copy(attrs = (item.attrs ?: emptyMap()) + ("checked" to docBool(!checked)))
        val builder = text.toBuilder()
        // Every line of the item carries it.
        lines.forEach { l ->
            val b = TextLines.block(l, text)
            if (b.levels.getOrNull(itemIndex)?.isSameInstance(item) == true) {
                val levels = b.levels.toMutableList().also { it[itemIndex] = updatedItem }
                DocumentEditingRanges.withSeparator(l, text.string).takeIf { it.length > 0 }?.let { builder.setBlock(it, com.neutrino.docs.core.document.DocumentBlock(levels)) }
            }
        }
        commit(DocumentEditing.reproject(builder.build(), selection))
    }

    /** Replaces `range` with `replacement`, which takes the attributes of the text it replaces. */
    fun replace(range: DocRange, replacement: String) {
        if (!isEditable || range.end > text.length) return
        val attrs = text.attributes(range.location)?.copy(atom = null) ?: DocumentInputRules.typingAttributes(text, range.location, null)
        commit(EditorDocumentState(text.toBuilder().replace(range, replacement, attrs).build(), DocRange(range.location + replacement.length, 0)))
    }

    /** Replaces every range, last first so earlier offsets stay true; one undo step. */
    fun replaceAll(ranges: List<DocRange>, replacement: String) {
        if (!isEditable || ranges.isEmpty()) return
        val builder = text.toBuilder()
        for (r in ranges.sortedByDescending { it.location }) {
            val attrs = text.attributes(r.location)?.copy(atom = null) ?: continue
            builder.replace(r, replacement, attrs)
        }
        commit(EditorDocumentState(builder.build(), DocRange(0, 0)))
    }

    private fun commit(next: EditorDocumentState) {
        if (next.text == text && next.selection == selection) return
        pushUndo(force = true)
        write(next)
    }

    /** Writes a computed state into the model and the text view, keeping the keyboard up. */
    private fun write(next: EditorDocumentState) {
        text = next.text
        selection = next.selection
        pendingMarks = null
        view?.let {
            it.applyModel(text, selection)
            renderer.render(it.editableText, text)
        }
        publishNow()
        refreshChrome()
    }

    // ── Undo ────────────────────────────────────────────────────────────────────────────────────

    private fun pushUndo(force: Boolean) {
        if (!force && undoStack.isNotEmpty()) return
        undoStack.addLast(Snapshot(text, selection))
        if (undoStack.size > 200) undoStack.removeFirst()
        redoStack.clear()
        lastTypingEnd = -1
        updateUndoState()
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(Snapshot(text, selection))
        write(EditorDocumentState(previous.text, previous.selection))
        updateUndoState()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(Snapshot(text, selection))
        write(EditorDocumentState(next.text, next.selection))
        updateUndoState()
    }

    private fun updateUndoState() {
        canUndo.value = undoStack.isNotEmpty()
        canRedo.value = redoStack.isNotEmpty()
    }

    // ── Find ────────────────────────────────────────────────────────────────────────────────────

    /** Case-insensitive matches of `query` in the text the reader sees. */
    fun find(query: String): List<DocRange> {
        if (query.isEmpty()) return emptyList()
        val out = ArrayList<DocRange>()
        var from = 0
        val s = text.string
        while (true) {
            val at = s.indexOf(query, from, ignoreCase = true)
            if (at < 0) break
            out += DocRange(at, query.length)
            from = at + maxOf(1, query.length)
        }
        return out
    }

    fun highlight(matches: List<DocRange>, current: Int, palette: EditorPalette) {
        val v = view ?: return
        val e = v.editableText
        e.getSpans(0, e.length, FindSpan::class.java).forEach { e.removeSpan(it) }
        matches.forEachIndexed { i, r ->
            if (r.end <= e.length) e.setSpan(FindSpan(if (i == current) palette.findCurrent else palette.findMatch), r.location, r.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        matches.getOrNull(current)?.let { moveCaret(it.location, select = it.length) }
    }

    // ── Navigation, folding ─────────────────────────────────────────────────────────────────────

    fun moveCaret(offset: Int, select: Int = 0) {
        val v = view ?: return
        val start = offset.coerceIn(0, text.length)
        val end = (start + select).coerceIn(start, text.length)
        v.setSelection(start, end)
        v.post { v.bringPointIntoView(start) }
    }

    val outline: DocumentOutline get() = DocumentOutline.of(text)

    fun toggleFold(line: Int) {
        folded.value = if (line in folded.value) folded.value - line else folded.value + line
        applyFolds()
    }

    fun foldAll() { folded.value = foldable.value.map { it.startLine }.toSet(); applyFolds() }
    fun unfoldAll() { folded.value = emptySet(); applyFolds() }

    /** Folds are display-only: the folded text stays in the model, so saves and undo see the whole document. */
    private fun applyFolds() {
        val valid = folded.value.filter { s -> foldable.value.any { it.startLine == s } }.toSet()
        if (valid != folded.value) folded.value = valid
        renderer.hiddenLines = CodeFolding.hiddenLines(valid, foldable.value)
        view?.let { renderer.render(it.editableText, text); it.invalidate() }
        // A caret or selection inside folded text would edit what can't be seen: it goes to the end
        // of the nearest line still showing.
        val hidden = renderer.hiddenLines
        val first = TextLines.lineIndex(text, selection.location)
        val last = TextLines.lineIndex(text, selection.end)
        if ((first..last).any { it in hidden }) {
            var line = first
            while (line > 0 && line in hidden) line--
            TextLines.ranges(text).getOrNull(line)?.let { moveCaret(it.end) }
        }
    }

    private fun refreshFolding() {
        val regions = CodeFolding.regions(text)
        if (regions != foldable.value) {
            foldable.value = regions
            // A line number that no longer starts a region is no longer folded.
            if (folded.value.isNotEmpty()) applyFolds()
        }
        view?.invalidate()
    }

    private fun refreshChrome() {
        refreshFolding()
        refreshStatus()
    }

    private fun refreshStatus() {
        val s = state
        _status.value = SelectionStatus(
            headingLevel = DocumentOutline.headingLevel(s.caretBlock),
            alignment = DocumentEditing.alignment(s.caretBlock),
            active = DocumentEditingCommand.entries.filter { it.isActive(s) }.toSet(),
            hasSelection = s.selection.length > 0,
        )
    }

    private fun rerenderPictures(source: String) {
        val v = view ?: return
        val e = v.editableText
        if (e.length != text.length) return
        for (span in e.getSpans(0, e.length, PictureSpan::class.java)) {
            if (span.source != source) continue
            val at = e.getSpanStart(span)
            renderer.render(e, text, DocRange(at, 1))
        }
    }

    private fun schedulePublish() {
        publishJob?.cancel()
        publishJob = scope.launch {
            delay(300)
            publishNow()
        }
    }

    /** Called before a save, so what is saved is what is on screen this instant. */
    fun publishNow() {
        publishJob?.cancel()
        publishJob = null
        _document.value = DocumentBuilder.document(text)
    }

    /** Decorations line by line, for the gutter. */
    fun decorations(): List<DocumentDecoration> = DocumentDecoration.of(text)
}

/**
 * The text view behind the editor. It is a plain `EditText` holding the projected text; what makes
 * it a document editor is the controller it reports every change to, and the gutter it draws fold
 * chevrons in. Hardware-keyboard shortcuts are Ctrl+B/I/E/K, Ctrl+0–3, Ctrl+Z/Shift+Z, Tab/Shift+Tab.
 */
@SuppressLint("ViewConstructor")
class DocumentEditText(context: Context, private val onShortcut: (Char, Boolean) -> Boolean) : AppCompatEditText(context) {

    var controller: EditorController? = null
    private var applying = false
    private var changeStart = 0
    private var changeBefore = 0
    private var changeCount = 0
    private val density = resources.displayMetrics.density
    val gutter = (16 * density).toInt()
    var chevronColor = 0xFF888888.toInt()

    val columnWidth: Int
        get() = (if (width > 0) width else resources.displayMetrics.widthPixels) - totalPaddingLeft - totalPaddingRight

    init {
        background = null
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        isSingleLine = false
        setHorizontallyScrolling(false)
        setPadding(gutter + (8 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (24 * density).toInt())
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                changeStart = start
                changeBefore = count
                changeCount = after
            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable) {
                if (applying) return
                val inserted = s.subSequence(changeStart, (changeStart + changeCount).coerceAtMost(s.length)).toString()
                applying = true
                try {
                    controller?.onUserEdit(changeStart, changeBefore, inserted)
                } finally {
                    applying = false
                }
            }
        })
        setOnFocusChangeListener { _, focused -> controller?.isEditing?.value = focused }
    }

    /**
     * Brings the text view's characters in line with the model, replacing only what differs — so the
     * scroll position stays and a long document is not re-laid-out whole for one rule.
     */
    fun applyModel(text: AttributedText, selection: DocRange) {
        val editable = editableText ?: return
        val target = text.string
        val current = editable.toString()
        if (current != target) {
            var prefix = 0
            val max = minOf(current.length, target.length)
            while (prefix < max && current[prefix] == target[prefix]) prefix++
            var suffix = 0
            while (suffix < max - prefix && current[current.length - 1 - suffix] == target[target.length - 1 - suffix]) suffix++
            val wasApplying = applying
            applying = true
            try {
                BaseInputConnection.removeComposingSpans(editable)
                editable.replace(prefix, current.length - suffix, target, prefix, target.length - suffix)
            } finally {
                applying = wasApplying
            }
        }
        val start = selection.location.coerceIn(0, editable.length)
        val end = selection.end.coerceIn(start, editable.length)
        if (selectionStart != start || selectionEnd != end) setSelection(start, end)
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (!applying) controller?.onSelectionChanged(selStart, selEnd)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        // Multi-line: Return is a newline, never an "action".
        outAttrs.imeOptions = outAttrs.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION.inv() or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        return object : InputConnectionWrapper(base, true) {
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0 && selectionStart == 0 && selectionEnd == 0 && controller?.backspaceAtStart() == true) return true
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DEL && selectionStart == 0 && selectionEnd == 0 &&
                    controller?.backspaceAtStart() == true) return true
                return super.sendKeyEvent(event)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val c = controller
        if (c != null && keyCode == KeyEvent.KEYCODE_TAB) {
            c.indent(outdent = event.isShiftPressed)
            return true
        }
        if (c != null && keyCode == KeyEvent.KEYCODE_DEL && selectionStart == 0 && selectionEnd == 0 && c.backspaceAtStart()) return true
        if (event.isCtrlPressed) {
            val ch = event.getUnicodeChar(0).toChar().lowercaseChar().takeIf { it != '\u0000' } ?: KeyEvent.keyCodeToString(keyCode).lastOrNull()?.lowercaseChar()
            if (ch != null && onShortcut(ch, event.isShiftPressed)) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ── Gutter and taps ─────────────────────────────────────────────────────────────────────────

    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * resources.displayMetrics.density }
    private var downX = 0f
    private var downY = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val c = controller ?: return
        val layout = layout ?: return
        val lines = TextLines.ranges(c.text)
        chevronPaint.color = chevronColor
        val folded = c.folded.value
        val hidden = c.renderer.hiddenLines
        for (region in c.foldable.value) {
            if (region.startLine in hidden) continue
            val offset = lines.getOrNull(region.startLine)?.location ?: continue
            if (offset > text?.length ?: 0) continue
            val line = layout.getLineForOffset(offset)
            val cy = totalPaddingTop + (layout.getLineTop(line) + layout.getLineBaseline(line)) / 2f
            val cx = gutter / 2f + 2 * density
            val s = 4 * density
            val path = Path()
            if (region.startLine in folded) {
                path.moveTo(cx - s / 2, cy - s); path.lineTo(cx + s / 2, cy); path.lineTo(cx - s / 2, cy + s)
            } else {
                path.moveTo(cx - s, cy - s / 2); path.lineTo(cx, cy + s / 2); path.lineTo(cx + s, cy - s / 2)
            }
            canvas.drawPath(path, chevronPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val c = controller
        if (c != null && event.actionMasked == MotionEvent.ACTION_DOWN) { downX = event.x; downY = event.y }
        if (c != null && event.actionMasked == MotionEvent.ACTION_UP && kotlin.math.abs(event.x - downX) < 12 * density && kotlin.math.abs(event.y - downY) < 12 * density) {
            val offset = getOffsetForPosition(event.x, event.y).coerceIn(0, c.text.length)
            val line = TextLines.lineIndex(c.text, offset)
            // The gutter: a fold chevron.
            if (event.x < gutter + 4 * density && c.foldable.value.any { it.startLine == line }) {
                c.toggleFold(line)
                return true
            }
            // A checkbox: in the marker column of a task item's first line.
            val decoration = c.decorations().firstOrNull { it.line == line && it.kind is DocumentDecoration.Kind.Checkbox }
            if (decoration != null) {
                val lines = TextLines.ranges(c.text)
                val block = TextLines.block(lines[line], c.text)
                val margin = 22 * density * block.containerDepth
                val x = event.x - totalPaddingLeft
                if (x < margin && x > margin - 28 * density) {
                    c.toggleTask(line)
                    return true
                }
            }
            // A picture: the whole of what an image is on screen, so tapping it opens its properties.
            val atomAt = listOf(offset, offset - 1).firstOrNull { it in 0 until c.text.length && c.text.atom(it)?.node?.type == DocumentNodeType.IMAGE }
            if (atomAt != null) {
                val ok = super.onTouchEvent(event)
                c.selectImage(atomAt)
                return ok
            }
        }
        return super.onTouchEvent(event)
    }
}
