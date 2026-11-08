package com.neutrino.docs.ui.editor

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.neutrino.docs.ImagePrep
import com.neutrino.docs.core.editing.DriveImageReference
import com.neutrino.docs.core.editing.EditorImage
import com.neutrino.docs.core.editing.EditorImageDescriptor
import com.neutrino.docs.core.editing.EditorImageSelection
import com.neutrino.docs.core.editing.ImageAlignment
import com.neutrino.docs.core.editing.ImageAttributes
import com.neutrino.docs.core.editing.ImageShadow
import com.neutrino.docs.core.model.DocItem
import com.neutrino.docs.graph
import com.neutrino.docs.ui.FormSection
import com.neutrino.docs.ui.PageBar
import com.neutrino.docs.ui.formatBytes
import com.neutrino.shared.android.ui.ConfirmDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

/** What Insert would do: reference a Drive file where it stands, or upload bytes into Attachments first. */
private sealed class Staged(val caption: String, val preview: Bitmap?) {
    class DriveFile(val id: String, caption: String, preview: Bitmap?) : Staged(caption, preview)
    class Upload(val prepared: ImagePrep.Prepared, val name: String, preview: Bitmap?) : Staged(name, preview)
}

/**
 * Picks a picture from wherever it is. Every source ends up a Drive file, since a document stores
 * only `neutrino-drive:<id>`: one already in Drive is referenced where it stands (the same photo in
 * ten documents is stored once); a photo, a file or a linked picture is uploaded into Attachments at
 * its original resolution — on Insert, not on preview, so an abandoned pick leaves nothing behind.
 * The upload is measurable and cancellable, and a failure keeps the pick so the button is Try Again.
 */
@Composable
fun InsertImageDialog(onDismiss: () -> Unit, onInsert: (alt: String, source: String) -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf(0) }
    var alt by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var staged by remember { mutableStateOf<Staged?>(null) }
    var working by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Double?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var upload by remember { mutableStateOf<Job?>(null) }

    fun stage(build: suspend () -> Staged) {
        working = true
        failure = null
        scope.launch {
            try {
                staged = build()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e.message ?: "That picture could not be read."
            }
            working = false
        }
    }

    fun fromBytes(bytes: ByteArray, suggestion: String?): Staged {
        val prepared = ImagePrep.prepared(bytes)
        val name = EditorImage.fileName(suggestion, prepared.fileExtension)
        return Staged.Upload(prepared, name, ImagePrep.decodeBounded(prepared.data, 1024))
    }

    fun fromUri(uri: Uri) = stage {
        val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
        // The photo picker names files by number (1000000020.png), which describes nothing.
        if (alt.isBlank()) name?.substringBeforeLast('.')?.takeIf { n -> n.any { !it.isDigit() } }?.let { alt = it }
        withContext(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw ImagePrep.Unreadable()
            fromBytes(bytes, name)
        }
    }

    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::fromUri) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::fromUri) }

    fun insert() {
        val pick = staged ?: return
        upload = scope.launch {
            working = true
            failure = null
            progress = null
            try {
                val fileId = when (pick) {
                    is Staged.DriveFile -> pick.id
                    is Staged.Upload -> graph.content.uploadAttachment(pick.prepared.data, pick.name, pick.prepared.mimeType) { p ->
                        scope.launch { progress = p }
                    }
                }
                onInsert(alt.trim(), DriveImageReference.source(fileId))
            } catch (e: CancellationException) {
                // Stopped by the user: nothing reached the document, and a file is only recorded by the request carrying its bytes.
            } catch (e: Exception) {
                failure = e.message ?: "The picture could not be uploaded."
            }
            working = false
            progress = null
            upload = null
        }
    }

    Dialog(onDismissRequest = { if (!working) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                PageBar("Insert Image", { if (!working) onDismiss() }) {
                    TextButton(onClick = ::insert, enabled = staged != null && !working) { Text(if (failure == null) "Insert" else "Try Again") }
                }
                Box(Modifier.weight(1f)) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                        val pick = staged
                        if (pick != null) {
                            FormSection(pick.caption) {
                                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                    if (pick.preview != null) Image(pick.preview.asImageBitmap(), null, Modifier.heightIn(max = 240.dp), contentScale = ContentScale.Fit)
                                    else Text("That file isn't an image this app can show.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = { staged = null }, modifier = Modifier.padding(start = 4.dp)) { Text("Choose Something Else") }
                            }
                        } else {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("Photos", "Link", "Drive").forEachIndexed { i, label -> FilterChip(selected = source == i, onClick = { source = i }, label = { Text(label) }) }
                            }
                            when (source) {
                                0 -> FormSection(footer = "The picture is saved to your Drive at its original size, and the document refers to it rather than carrying a copy.") {
                                    SourceRow(Icons.Filled.PhotoLibrary, "Choose from Photos") { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                                    SourceRow(Icons.Filled.InsertDriveFile, "Choose a File") { files.launch(arrayOf("image/*")) }
                                }
                                1 -> FormSection(footer = "The picture is copied into your Drive, so the document keeps showing it even if the original address stops working.") {
                                    OutlinedTextField(link, { link = it }, placeholder = { Text("https://example.com/picture.png") }, singleLine = true,
                                        modifier = Modifier.fillMaxWidth().padding(12.dp))
                                    TextButton(enabled = link.isNotBlank(), modifier = Modifier.padding(start = 4.dp), onClick = {
                                        val address = link.trim()
                                        if (!address.startsWith("http://") && !address.startsWith("https://")) {
                                            failure = "That doesn't look like a web address."
                                            return@TextButton
                                        }
                                        stage {
                                            withContext(Dispatchers.IO) {
                                                graph.account.http.newCall(Request.Builder().url(address).build()).execute().use { response ->
                                                    if (!response.isSuccessful) throw Exception("The picture could not be downloaded (${response.code}).")
                                                    fromBytes(response.body?.bytes() ?: throw ImagePrep.Unreadable(), Uri.parse(address).lastPathSegment)
                                                }
                                            }
                                        }
                                    }) { Text("Preview") }
                                }
                                else -> DriveImagePicker { item ->
                                    if (alt.isBlank()) alt = item.displayName.substringBeforeLast('.')
                                    stage { Staged.DriveFile(item.id, item.name, ImagePrep.decodeBounded(graph.content.attachmentData(item.id), 1024)) }
                                }
                            }
                        }
                        FormSection("Alt Text", "Read aloud by TalkBack, and shown if the image can't be loaded.") {
                            OutlinedTextField(alt, { alt = it }, placeholder = { Text("Description") }, modifier = Modifier.fillMaxWidth().padding(12.dp))
                        }
                        failure?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 28.dp)) }
                    }
                    if (working) {
                        Surface(Modifier.align(Alignment.Center), tonalElevation = 6.dp, shape = MaterialTheme.shapes.large) {
                            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                val total = (staged as? Staged.Upload)?.prepared?.data?.size
                                if (upload != null && total != null) {
                                    Text("Uploading…", style = MaterialTheme.typography.titleSmall)
                                    LinearProgressIndicator(progress = { (progress ?: 0.0).toFloat() }, modifier = Modifier.width(220.dp).padding(vertical = 12.dp))
                                    Text("${formatBytes(((progress ?: 0.0) * total).toLong())} of ${formatBytes(total.toLong())}", style = MaterialTheme.typography.bodySmall)
                                    OutlinedButton(onClick = { upload?.cancel() }, modifier = Modifier.padding(top = 8.dp)) { Text("Cancel") }
                                } else CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(label, color = MaterialTheme.colorScheme.primary)
    }
}

/** Pictures already in Drive: recently touched first, then the folder tree — the first listing here that is not documents. */
@Composable
private fun DriveImagePicker(onPick: (DocItem) -> Unit) {
    val graph = LocalContext.current.graph
    var folder by remember { mutableStateOf<DocItem?>(null) }
    var trail by remember { mutableStateOf(listOf<DocItem>()) }
    var folders by remember { mutableStateOf<List<DocItem>>(emptyList()) }
    var images by remember { mutableStateOf<List<DocItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(folder) {
        images = null
        error = null
        try {
            if (folder == null) {
                images = graph.drive.fetchRecentImages()
                folders = graph.drive.fetchImages(null).first
            } else {
                val (f, i) = graph.drive.fetchImages(folder!!.id)
                folders = f
                images = i
            }
        } catch (e: Exception) {
            error = e.message
        }
    }
    FormSection(if (folder == null) "Recent pictures" else folder!!.name) {
        if (folder != null) SourceRow(Icons.Filled.Folder, "Back") { folder = trail.dropLast(1).lastOrNull(); trail = trail.dropLast(1) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        if (images == null && error == null) CircularProgressIndicator(Modifier.padding(16.dp))
        folders.forEach { f -> SourceRow(Icons.Filled.Folder, f.name) { trail = trail + f; folder = f } }
        images?.forEach { item -> SourceRow(Icons.Filled.Image, item.name) { onPick(item) } }
        if (images?.isEmpty() == true && folders.isEmpty()) Text("No pictures here.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
    }
}

/**
 * Everything about a picture except the picture: the web's properties panel, as a form — Layout,
 * Appearance, Tint, Metadata — with a live preview, and nothing written until Done, so dragging a
 * slider does not fill the undo stack.
 */
@Composable
fun ImagePropertiesDialog(
    selection: EditorImageSelection,
    images: EditorImageStore,
    isEditable: Boolean,
    onDismiss: () -> Unit,
    onApply: (EditorImageDescriptor) -> Unit,
    onRemove: () -> Unit,
) {
    val initial = selection.attributes
    val tint = initial.appliedFilter ?: ImageAttributes.Filter()
    var width by remember { mutableStateOf(initial.width ?: "") }
    var alignment by remember { mutableStateOf(initial.alignment) }
    var border by remember { mutableStateOf(initial.border ?: "") }
    var shadow by remember { mutableStateOf(initial.shadow) }
    var brightness by remember { mutableFloatStateOf((tint.brightness * 100).toFloat()) }
    var contrast by remember { mutableFloatStateOf((tint.contrast * 100).toFloat()) }
    var saturation by remember { mutableFloatStateOf((tint.saturation * 100).toFloat()) }
    var alt by remember { mutableStateOf(selection.descriptor.alt) }
    var title by remember { mutableStateOf(selection.descriptor.title ?: "") }
    var caption by remember { mutableStateOf(initial.caption) }
    var confirmRemove by remember { mutableStateOf(false) }

    fun edited(): EditorImageDescriptor = selection.descriptor.copy(
        alt = alt,
        title = title.trim().ifEmpty { null },
        attributes = initial.copy(
            width = width.trim().ifEmpty { null },
            alignment = alignment,
            border = border.trim().ifEmpty { null },
            shadow = shadow,
            caption = caption.trim(),
            filter = ImageAttributes.Filter.css(Math.round(brightness).toDouble() / 100, Math.round(contrast).toDouble() / 100, Math.round(saturation).toDouble() / 100),
        ),
    )

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                PageBar("Image", onDismiss) {
                    if (isEditable) TextButton(onClick = { onApply(edited()) }) { Text("Done") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                    // The picture as the settings would leave it.
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = when (alignment.placement) {
                        ImageAlignment.CENTER -> Alignment.Center
                        ImageAlignment.RIGHT -> Alignment.CenterEnd
                        else -> Alignment.CenterStart
                    }) {
                        val bitmap = images.bitmap(selection.source)
                        if (bitmap != null) {
                            val m = PictureSpan.matrix(ImageAttributes.Filter(brightness / 100.0, contrast / 100.0, saturation / 100.0))
                            Image(bitmap.asImageBitmap(), alt, Modifier.heightIn(max = 160.dp), contentScale = ContentScale.Fit,
                                colorFilter = ColorFilter.colorMatrix(ColorMatrix(m.array)))
                        } else Text("Loading image…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!isEditable) {
                        Text("This document is read-only.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                        return@Column
                    }
                    FormSection("Layout", if (alignment.isFloat) "Text flows around a floated image on the web. Here it sits on its own line, on the ${if (alignment == ImageAlignment.FLOAT_LEFT) "left" else "right"} — the setting is kept either way."
                        else "A width in per cent is of the column, so 50% is half the width of the text.") {
                        OutlinedTextField(width, { width = it }, label = { Text("Width") }, placeholder = { Text("Auto") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(12.dp))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ImageAttributes.WidthPreset.entries.forEach { p -> OutlinedButton(onClick = { width = p.raw }, modifier = Modifier.weight(1f)) { Text(p.label) } }
                        }
                        Text("Position", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
                        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ImageAlignment.entries.forEach { a -> FilterChip(selected = alignment == a, onClick = { alignment = a }, label = { Text(a.label) }) }
                        }
                    }
                    FormSection("Appearance", "A border is CSS: a width, a style and a colour — “2px solid #333”.") {
                        OutlinedTextField(border, { border = it }, label = { Text("Border") }, placeholder = { Text("None") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(12.dp))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ImageAttributes.borderPresets.forEach { (label, css) -> OutlinedButton(onClick = { border = css }, modifier = Modifier.weight(1f)) { Text(label) } }
                        }
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(12.dp)) {
                            ImageShadow.entries.forEachIndexed { i, s ->
                                SegmentedButton(selected = shadow == s, onClick = { shadow = s }, shape = SegmentedButtonDefaults.itemShape(i, ImageShadow.entries.size)) { Text(s.label) }
                            }
                        }
                    }
                    FormSection("Tint") {
                        TintSlider("Brightness", brightness, 50f..150f) { brightness = it }
                        TintSlider("Contrast", contrast, 50f..150f) { contrast = it }
                        TintSlider("Saturation", saturation, 0f..200f) { saturation = it }
                        if (Math.round(brightness) != 100 || Math.round(contrast) != 100 || Math.round(saturation) != 100) {
                            TextButton(onClick = { brightness = 100f; contrast = 100f; saturation = 100f }, modifier = Modifier.padding(start = 4.dp)) { Text("Reset Tint") }
                        }
                    }
                    FormSection("Metadata", "Alt text is read aloud by TalkBack and shown if the picture can't be loaded.") {
                        OutlinedTextField(alt, { alt = it }, label = { Text("Alt Text") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp))
                        OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp))
                        OutlinedTextField(caption, { caption = it }, label = { Text("Caption") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                    FormSection {
                        TextButton(onClick = { confirmRemove = true }, modifier = Modifier.padding(4.dp)) { Text("Remove Image", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
    if (confirmRemove) ConfirmDialog("Remove this image?", "The picture stays in your Drive; the document stops showing it.", "Remove Image",
        destructive = true, onDismiss = { confirmRemove = false }) { onRemove() }
}

@Composable
private fun TintSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row {
            Text(label, Modifier.weight(1f))
            Text("${Math.round(value)}%", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value, { onChange(Math.round(it).toFloat()) }, valueRange = range)
    }
}
