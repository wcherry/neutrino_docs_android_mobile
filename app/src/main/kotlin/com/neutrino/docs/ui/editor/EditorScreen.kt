package com.neutrino.docs.ui.editor

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.neutrino.docs.EditorFont
import com.neutrino.docs.core.api.DocsApi
import com.neutrino.docs.core.document.DocumentBody
import com.neutrino.docs.core.document.documentText
import com.neutrino.docs.core.editing.DocumentAlignment
import com.neutrino.docs.core.editing.DocumentEditingCommand
import com.neutrino.docs.core.model.CacheReason
import com.neutrino.docs.core.model.DocItem
import com.neutrino.docs.core.model.DocTextStats
import com.neutrino.docs.core.model.ShareRole
import com.neutrino.docs.graph
import com.neutrino.docs.ui.DocsStyle
import com.neutrino.docs.ui.PageBar
import com.neutrino.docs.ui.Unavailable
import com.neutrino.shared.android.ui.TextInputDialog
import com.neutrino.shared.core.ooxml.DocNode
import com.neutrino.shared.core.ooxml.DocValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the status line says about saving — honestly, including "saved offline". */
sealed class SaveState(val message: String) {
    object Saved : SaveState("Saved")
    object Dirty : SaveState("Unsaved changes")
    object Saving : SaveState("Saving…")
    object Queued : SaveState("Saved offline — will sync")
    class Failed(reason: String) : SaveState("Not saved: $reason")
}

/**
 * The document editor: open, edit, save. There is no edit mode and no preview: the editing surface
 * already shows the document as it reads, and `# ` is how a heading is typed, not how it is shown.
 *
 * Online the document comes from the server, offline from the encrypted cache; either way the
 * session holds its DEK. Typing saves after the user's interval — an autosave online, a queued edit
 * offline. A document neither cached nor reachable cannot be saved, and the editor says so rather
 * than accepting keystrokes it would lose.
 */
@Composable
fun EditorScreen(item: DocItem, onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val online by graph.isOnline.collectAsState()
    val offlineDocs by graph.offline.documents.collectAsState()
    val fontSize by graph.prefs.fontSize.collectAsState()
    val editorFont by graph.prefs.editorFont.collectAsState()
    val autoSave by graph.prefs.autoSaveInterval.collectAsState()

    val controller = remember {
        EditorController(scope, EditorImageStore(scope, { graph.content.attachmentData(it) }, graph.account.http, context.resources.displayMetrics.widthPixels * 2))
    }

    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var dek by remember { mutableStateOf<ByteArray?>(null) }
    var layout by remember { mutableStateOf<DocValue?>(null) }
    var contentVersion by remember { mutableStateOf(item.contentVersion) }
    var loadedDocument by remember { mutableStateOf<DocNode?>(null) }
    var saveState by remember { mutableStateOf<SaveState>(SaveState.Saved) }
    var role by remember { mutableStateOf(ShareRole.OWNER) }
    var reloadTick by remember { mutableIntStateOf(0) }

    var showOutline by remember { mutableStateOf(false) }
    var showVersions by remember { mutableStateOf(false) }
    var showSaveVersion by remember { mutableStateOf(false) }
    var showInsertImage by remember { mutableStateOf(false) }
    var showLink by remember { mutableStateOf(false) }
    var find by remember { mutableStateOf<Boolean?>(null) } // null hidden, false find, true find & replace
    var message by remember { mutableStateOf<String?>(null) }

    val isEditable = role.canEdit && loadError == null
    val isAvailableOffline = offlineDocs.any { it.id == item.id }
    val canUseServer = online && dek != null && !item.isShared
    controller.isEditable = isEditable
    controller.inputRules = graph.prefs.inputRules

    val document by controller.document.collectAsState()
    val selectedImage by controller.selectedImage.collectAsState()

    fun adopt(body: DocumentBody) {
        controller.load(body.doc)
        loadedDocument = controller.document.value
        layout = body.layout
    }

    fun resolveRole(info: com.neutrino.docs.core.model.DocFileInfo?) {
        // Only shared documents have a role worth asking for; an unknown one opens read-only.
        role = if (!item.isShared) ShareRole.OWNER else info?.yourRole ?: ShareRole.VIEWER
    }

    LaunchedEffect(reloadTick) {
        loading = true
        loadError = null
        try {
            val cached = graph.offline.isAvailableOffline(item.id)
            if (!online || cached) {
                val fromCache = runCatching { graph.offline.readPlaintext(item.id) }.getOrNull()
                if (fromCache != null) {
                    adopt(fromCache.first)
                    dek = fromCache.second
                    graph.search.indexDocument(item, fromCache.first.doc)
                    saveState = if (graph.offline.document(item.id)?.pendingEdit == null) SaveState.Saved else SaveState.Queued
                    loading = false
                    // Still refreshed from the server, unless that would replace unsynced work.
                    if (online && graph.offline.document(item.id)?.pendingEdit == null) {
                        runCatching { graph.content.loadContent(item) }.getOrNull()?.let { (body, key) ->
                            adopt(body); dek = key
                            val info = runCatching { graph.content.fileInfo(item.id) }.getOrNull()
                            contentVersion = info?.contentVersion ?: contentVersion
                            resolveRole(info)
                        }
                    }
                    return@LaunchedEffect
                }
            }
            if (!online) {
                loadError = "This document isn't available offline."
                return@LaunchedEffect
            }
            val (body, key) = graph.content.loadContent(item)
            adopt(body)
            dek = key
            saveState = SaveState.Saved
            // The plaintext is in hand, so indexing costs a tokenize pass and no network.
            graph.search.indexDocument(item, body.doc)
            val info = runCatching { graph.content.fileInfo(item.id) }.getOrNull()
            contentVersion = info?.contentVersion ?: item.contentVersion
            resolveRole(info)
            // Opening a document caches it, so it reads later with no connection.
            if (!graph.offline.isAvailableOffline(item.id)) runCatching { graph.offline.download(item, CacheReason.OPENED) }
        } catch (e: Exception) {
            loadError = e.message ?: "The document could not be opened."
        } finally {
            loading = false
        }
    }

    suspend fun save() {
        val key = dek ?: return
        controller.publishNow()
        val snapshot = controller.document.value
        if (snapshot == loadedDocument || !isEditable) return
        val body = DocumentBody(snapshot, layout)
        // Offline: into the cache, for the sync engine to deliver. Uploading past a queued edit would reorder the user's own changes.
        if (!graph.isOnline.value) {
            if (!graph.offline.isAvailableOffline(item.id)) {
                saveState = SaveState.Failed("This document isn't available offline.")
                return
            }
            try {
                graph.offline.writePendingEdit(body, item.id, key)
                loadedDocument = snapshot
                saveState = SaveState.Queued
                graph.search.indexDocument(item, snapshot)
            } catch (e: Exception) {
                saveState = SaveState.Failed(e.message ?: "Could not save")
            }
            return
        }
        saveState = SaveState.Saving
        try {
            val saved = graph.content.saveContent(body, item, key, contentVersion)
            contentVersion = saved.contentVersion
            loadedDocument = snapshot
            saveState = SaveState.Saved
            graph.drive.documentWasSaved(item.id, saved.encoded.size.toLong(), saved.updatedAt, saved.contentVersion)
            graph.search.indexDocument(item, snapshot, saved.updatedAt.toEpochMilli())
            if (graph.offline.isAvailableOffline(item.id)) {
                runCatching { graph.offline.cacheLocalVersion(saved.encoded, item.id, key, saved.updatedAt.toEpochMilli(), saved.contentVersion) }
            }
        } catch (e: DocsApi.ContentVersionConflict) {
            // Somebody else saved meanwhile; queueing would only replay the losing write. The user decides.
            saveState = SaveState.Failed(e.message ?: "Changed elsewhere")
        } catch (e: Exception) {
            // A failed upload falls back to the queue where it can, so the work is not lost.
            val queued = graph.offline.isAvailableOffline(item.id) && runCatching { graph.offline.writePendingEdit(body, item.id, key) }.isSuccess
            if (queued) {
                loadedDocument = snapshot
                saveState = SaveState.Queued
                graph.sync.requestSync()
            } else {
                saveState = SaveState.Failed(e.message ?: "Could not save")
            }
        }
    }

    // Autosave follows the document itself, so undo and a restored version save too.
    LaunchedEffect(document, autoSave) {
        if (loading || loadedDocument == null || document == loadedDocument || !isEditable) return@LaunchedEffect
        saveState = SaveState.Dirty
        delay(autoSave * 1000L)
        save()
    }

    // Leaving must not strand an edit still inside its debounce window.
    val currentSave by rememberUpdatedState(suspend { save() })
    DisposableEffect(Unit) {
        onDispose {
            controller.detach()
            graph.scope.launch { currentSave() }
        }
    }

    val palette = EditorPalette(
        text = MaterialTheme.colorScheme.onSurface.toArgb(),
        secondary = MaterialTheme.colorScheme.onSurfaceVariant.toArgb(),
        link = MaterialTheme.colorScheme.primary.toArgb(),
        code = Color(0xFFAF52DE).toArgb(),
        codeBackground = MaterialTheme.colorScheme.surfaceContainerHigh.toArgb(),
        rule = MaterialTheme.colorScheme.outlineVariant.toArgb(),
        highlight = Color(0x59FFCC00).toArgb(),
        placeholder = MaterialTheme.colorScheme.surfaceContainerHigh.toArgb(),
        findMatch = Color(0x66FFCC00).toArgb(),
        findCurrent = Color(0xCCFF9500).toArgb(),
    )
    val density = LocalDensity.current.density
    val bodyPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSize, context.resources.displayMetrics)
    val typeface = when (editorFont) {
        EditorFont.MONOSPACED -> Typeface.MONOSPACE
        EditorFont.SERIF -> Typeface.SERIF
        // Android ships no rounded system face; the closest it has is the default sans.
        EditorFont.SYSTEM, EditorFont.ROUNDED -> Typeface.DEFAULT
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        PageBar(item.displayName, onBack) {
            if (isEditable) {
                val canUndo by controller.canUndo.collectAsState()
                val canRedo by controller.canRedo.collectAsState()
                IconButton(onClick = { controller.undo() }, enabled = canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                IconButton(onClick = { controller.redo() }, enabled = canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
            }
            if (loadError == null && !loading) {
                DocumentMenu(
                    controller = controller,
                    isEditable = isEditable,
                    onOutline = { showOutline = true },
                    onFind = { find = it },
                    onSaveVersion = { showSaveVersion = true },
                    onVersionHistory = { showVersions = true },
                    onToggleOffline = {
                        scope.launch {
                            runCatching { if (graph.offline.isAvailableOffline(item.id)) graph.offline.remove(item.id) else graph.offline.download(item) }
                                .onFailure { message = it.message }
                        }
                    },
                    isAvailableOffline = isAvailableOffline,
                    canUseServer = canUseServer,
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading && loadedDocument == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                loadError != null -> Unavailable(Icons.Filled.ErrorOutline, "Can't open this document", loadError!!, "Try Again") { reloadTick++ }
                else -> AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        DocumentEditText(ctx) { ch, shift -> shortcut(controller, ch, shift) { find = it } }.also { view ->
                            controller.renderer.style = EditorStyle(density, bodyPx, typeface, palette)
                            view.typeface = typeface
                            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, bodyPx)
                            view.setTextColor(palette.text)
                            view.chevronColor = palette.secondary
                            controller.attach(view)
                        }
                    },
                    update = { view ->
                        val style = EditorStyle(density, bodyPx, typeface, palette)
                        val current = controller.renderer.style
                        if (current == null || current.bodySize != style.bodySize || current.typeface != style.typeface || current.palette != style.palette) {
                            controller.renderer.style = style
                            view.typeface = typeface
                            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, bodyPx)
                            view.setTextColor(palette.text)
                            controller.attach(view)
                        }
                    },
                )
            }
        }
        if (find != null && loadError == null) FindBar(controller, replace = find == true && isEditable, palette) { find = null }
        if (loadError == null && !loading) {
            HorizontalDivider()
            StyleBar(
                controller = controller,
                isEditable = isEditable,
                onLink = { showLink = true },
                onImage = { showInsertImage = true },
            )
        }
        StatusBar(saveState, online, DocTextStats.compute(document.documentText).summaryText, Modifier.navigationBarsPadding())
    }

    if (showOutline) OutlineSheet(controller.outline, onDismiss = { showOutline = false }) { controller.moveCaret(it.characterOffset) }
    if (showVersions) dek?.let { key ->
        VersionHistoryDialog(item, key, onDismiss = { showVersions = false }) { restored ->
            adopt(restored)
            saveState = SaveState.Saved
        }
    }
    if (showSaveVersion) TextInputDialog("Save Version", "", "Version name (optional)", "Save", onDismiss = { showSaveVersion = false }) { label ->
        showSaveVersion = false
        val key = dek ?: return@TextInputDialog
        scope.launch {
            controller.publishNow()
            runCatching { graph.versions.saveVersion(DocumentBody(controller.document.value, layout), item, key, label.ifBlank { null }) }
                .onSuccess { loadedDocument = controller.document.value; saveState = SaveState.Saved; message = "Saved ${it.displayTitle}." }
                .onFailure { message = it.message }
        }
    }
    if (showLink) TextInputDialog("Add Link", "https://", "Address", "Add", onDismiss = { showLink = false }) {
        showLink = false
        controller.setLink(if (it == "https://") "" else it)
    }
    if (showInsertImage) InsertImageDialog(onDismiss = { showInsertImage = false }) { alt, source ->
        showInsertImage = false
        controller.insertImage(alt, source)
    }
    selectedImage?.let { selection ->
        ImagePropertiesDialog(selection, controller.images, isEditable, onDismiss = { controller.selectedImage.value = null },
            onApply = { controller.updateSelectedImage(it) }, onRemove = { controller.removeSelectedImage() })
    }
    message?.let {
        AlertDialog(onDismissRequest = { message = null }, text = { Text(it) }, confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } })
    }
}

/** The hardware-keyboard shortcuts. */
private fun shortcut(controller: EditorController, ch: Char, shift: Boolean, showFind: (Boolean) -> Unit): Boolean {
    DocumentEditingCommand.entries.firstOrNull { it.keyboardShortcut == ch }?.let { command ->
        if (command == DocumentEditingCommand.LINK) return false
        controller.apply(command)
        return true
    }
    when (ch) {
        'z' -> if (shift) controller.redo() else controller.undo()
        'y' -> controller.redo()
        'f' -> showFind(shift)
        ']' -> controller.indent()
        '[' -> controller.indent(outdent = true)
        else -> return false
    }
    return true
}

// ── The style bar ───────────────────────────────────────────────────────────────────────────────

/**
 * On a phone, every editor control: paragraph style and alignment, the inline marks, links and
 * pictures, lists, quote, code and table, indent, then the document menu. It scrolls, and nothing is
 * pinned: order is priority, since what shows without scrolling is whatever comes first. It rides
 * above the keyboard and stays docked when the keyboard is down, so Outline and Version History are
 * always reachable.
 */
@Composable
private fun StyleBar(
    controller: EditorController,
    isEditable: Boolean,
    onLink: () -> Unit,
    onImage: () -> Unit,
) {
    val status by controller.status.collectAsState()
    val editing by controller.isEditing.collectAsState()
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isEditable) {
            ParagraphStyleMenu(status.paragraphStyle) { controller.apply(it) }
            AlignmentMenu(status.alignment) { controller.setAlignment(it) }
            VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))
            for (c in listOf(DocumentEditingCommand.BOLD, DocumentEditingCommand.ITALIC, DocumentEditingCommand.STRIKETHROUGH, DocumentEditingCommand.CODE,
                DocumentEditingCommand.LINK, DocumentEditingCommand.IMAGE, DocumentEditingCommand.BULLET_LIST, DocumentEditingCommand.NUMBERED_LIST,
                DocumentEditingCommand.CHECKBOX, DocumentEditingCommand.QUOTE, DocumentEditingCommand.CODE_BLOCK, DocumentEditingCommand.TABLE)) {
                when (c) {
                    // A link needs an address, and the selection is what it goes on.
                    DocumentEditingCommand.LINK -> BarButton(Icons.Filled.Link, "Link", c in status.active, enabled = status.hasSelection, onClick = onLink)
                    // A picture has to be found before it can be written down.
                    DocumentEditingCommand.IMAGE -> BarButton(Icons.Filled.Image, "Image", onClick = onImage)
                    else -> BarButton(icon(c), c.title, c in status.active) { controller.apply(c) }
                }
            }
            BarButton(Icons.AutoMirrored.Filled.FormatIndentDecrease, "Outdent") { controller.indent(outdent = true) }
            BarButton(Icons.AutoMirrored.Filled.FormatIndentIncrease, "Indent") { controller.indent() }
            VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))
        }
        if (isEditable && editing) {
            BarButton(Icons.Filled.KeyboardHide, "Hide Keyboard") {
                controller.view?.let { v ->
                    v.clearFocus()
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
                }
            }
        }
    }
}

private fun icon(c: DocumentEditingCommand): ImageVector = when (c) {
    DocumentEditingCommand.BOLD -> Icons.Filled.FormatBold
    DocumentEditingCommand.ITALIC -> Icons.Filled.FormatItalic
    DocumentEditingCommand.STRIKETHROUGH -> Icons.Filled.FormatStrikethrough
    DocumentEditingCommand.CODE -> Icons.Filled.Code
    DocumentEditingCommand.LINK -> Icons.Filled.Link
    DocumentEditingCommand.IMAGE -> Icons.Filled.Image
    DocumentEditingCommand.QUOTE -> Icons.Filled.FormatQuote
    DocumentEditingCommand.CODE_BLOCK -> Icons.Filled.DataObject
    DocumentEditingCommand.TABLE -> Icons.Filled.TableChart
    DocumentEditingCommand.CHECKBOX -> Icons.Filled.CheckBox
    DocumentEditingCommand.BULLET_LIST -> Icons.AutoMirrored.Filled.FormatListBulleted
    DocumentEditingCommand.NUMBERED_LIST -> Icons.Filled.FormatListNumbered
    else -> Icons.AutoMirrored.Filled.FormatAlignLeft
}

/**
 * One control. An active toggle is a filled, tinted chip rather than only a different colour: colour
 * alone is not something every reader can see.
 */
@Composable
private fun BarButton(icon: ImageVector, label: String, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
            .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = tint) }
}

/** The style is the one control with no other way in: with no syntax on screen, nothing else says which level a line is. */
@Composable
private fun ParagraphStyleMenu(current: DocumentEditingCommand, onChoose: (DocumentEditingCommand) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { open = true }.padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(current.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(18.dp))
        }
        DropdownMenu(open, { open = false }) {
            DocumentEditingCommand.paragraphStyles.forEach { style ->
                DropdownMenuItem(
                    text = { Text(style.title, color = if (style == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                    onClick = { open = false; onChoose(style) },
                )
            }
        }
    }
}

@Composable
private fun AlignmentMenu(current: DocumentAlignment, onChoose: (DocumentAlignment) -> Unit) {
    var open by remember { mutableStateOf(false) }
    fun iconFor(a: DocumentAlignment) = when (a) {
        DocumentAlignment.LEFT -> Icons.AutoMirrored.Filled.FormatAlignLeft
        DocumentAlignment.CENTER -> Icons.Filled.FormatAlignCenter
        DocumentAlignment.RIGHT -> Icons.AutoMirrored.Filled.FormatAlignRight
        DocumentAlignment.JUSTIFY -> Icons.Filled.FormatAlignJustify
    }
    Box {
        BarButton(iconFor(current), "Alignment") { open = true }
        DropdownMenu(open, { open = false }) {
            DocumentAlignment.entries.forEach { a ->
                DropdownMenuItem(text = { Text(a.title) }, leadingIcon = { Icon(iconFor(a), null) }, onClick = { open = false; onChoose(a) })
            }
        }
    }
}

/** What is about the document rather than the text, in one menu in the top bar: the rare actions, kept off the formatting bar. */
@Composable
private fun DocumentMenu(
    controller: EditorController,
    isEditable: Boolean,
    onOutline: () -> Unit,
    onFind: (Boolean) -> Unit,
    onSaveVersion: () -> Unit,
    onVersionHistory: () -> Unit,
    onToggleOffline: () -> Unit,
    isAvailableOffline: Boolean,
    canUseServer: Boolean,
) {
    var open by remember { mutableStateOf(false) }
    val foldable by controller.foldable.collectAsState()
    val folded by controller.folded.collectAsState()
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "Document actions") }
        DropdownMenu(open, { open = false }) {
            @Composable
            fun item(label: String, enabled: Boolean = true, f: () -> Unit) =
                DropdownMenuItem(text = { Text(label) }, enabled = enabled, onClick = { open = false; f() })
            item("Outline", f = onOutline)
            item("Find") { onFind(false) }
            if (isEditable) item("Find and Replace") { onFind(true) }
            HorizontalDivider()
            item("Fold All", foldable.isNotEmpty()) { controller.foldAll() }
            item("Unfold All", folded.isNotEmpty()) { controller.unfoldAll() }
            HorizontalDivider()
            item("Save Version…", canUseServer && isEditable, onSaveVersion)
            item("Version History", canUseServer, onVersionHistory)
            HorizontalDivider()
            item(if (isAvailableOffline) "Remove Offline Copy" else "Make Available Offline", f = onToggleOffline)
        }
    }
}

@Composable
private fun StatusBar(state: SaveState, online: Boolean, stats: String, modifier: Modifier) {
    Row(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(state.message, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (state is SaveState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (!online) {
            Icon(Icons.Filled.WifiOff, "Offline", Modifier.size(14.dp), tint = DocsStyle.warning)
            Spacer(Modifier.width(8.dp))
        }
        Text(stats, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** Find, and Find and Replace, over the text the reader sees. */
@Composable
private fun FindBar(controller: EditorController, replace: Boolean, palette: EditorPalette, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var current by remember { mutableIntStateOf(0) }
    val document by controller.document.collectAsState()
    val matches = remember(query, document) { controller.find(query) }
    LaunchedEffect(matches, current) { controller.highlight(matches, current.coerceIn(0, maxOf(0, matches.size - 1)), palette) }
    DisposableEffect(Unit) { onDispose { controller.highlight(emptyList(), 0, palette) } }
    // Opening Find means typing a query next.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it; current = 0 }, placeholder = { Text("Find") }, singleLine = true, modifier = Modifier.weight(1f).focusRequester(focus))
            Text(if (matches.isEmpty()) "0" else "${current + 1}/${matches.size}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 6.dp))
            IconButton(onClick = { if (matches.isNotEmpty()) current = (current - 1 + matches.size) % matches.size }) { Icon(Icons.Filled.KeyboardArrowUp, "Previous") }
            IconButton(onClick = { if (matches.isNotEmpty()) current = (current + 1) % matches.size }) { Icon(Icons.Filled.KeyboardArrowDown, "Next") }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Done") }
        }
        if (replace) Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(replacement, { replacement = it }, placeholder = { Text("Replace") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = { matches.getOrNull(current)?.let { controller.replace(it, replacement) } }, enabled = matches.isNotEmpty()) { Text("Replace") }
            TextButton(onClick = { controller.replaceAll(matches, replacement) }, enabled = matches.isNotEmpty()) { Text("All") }
        }
    }
}
