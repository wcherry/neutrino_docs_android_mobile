package com.neutrino.docs.ui.editor

import android.graphics.Bitmap
import android.util.Base64
import com.neutrino.docs.ImagePrep
import com.neutrino.docs.core.editing.DriveImageReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The pictures the editor is showing, by source. Layout asks for a picture's size on every pass and
 * cannot fetch or decode anything, so a miss starts a load and answers null; [onLoad] asks for
 * another pass when the picture arrives. A failed source is remembered, so a broken link is not
 * fetched again all session.
 *
 * `neutrino-drive:` is downloaded and decrypted through the content service; `http(s):` and `data:`
 * — what older documents hold — still draw. Decoding is bounded, so a document showing five
 * full-resolution photographs costs their size on screen, not on disk.
 */
class EditorImageStore(
    private val scope: CoroutineScope,
    private val resolveDrive: suspend (String) -> ByteArray,
    private val http: OkHttpClient,
    private val maxPixels: Int,
) {
    var onLoad: ((String) -> Unit)? = null

    private val images = HashMap<String, Bitmap>()
    private val failed = HashSet<String>()
    private val loading = HashSet<String>()

    fun bitmap(source: String): Bitmap? {
        images[source]?.let { return it }
        if (source.isEmpty() || source in failed || source in loading) return null
        loading += source
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { runCatching { bytes(source)?.let { ImagePrep.decodeBounded(it, maxPixels) } }.getOrNull() }
            loading -= source
            if (bitmap != null) images[source] = bitmap else failed += source
            onLoad?.invoke(source)
        }
        return null
    }

    fun hasFailed(source: String) = source in failed

    /**
     * Loads every source not already in hand, and returns once each has arrived or failed — for
     * drawing that cannot wait for [onLoad], such as a printed page, where a picture still loading
     * is a placeholder on paper. Main thread, like the rest of the store.
     */
    suspend fun preload(sources: Collection<String>) {
        for (source in sources) {
            if (source.isEmpty() || source in images || source in failed) continue
            val bitmap = withContext(Dispatchers.IO) { runCatching { bytes(source)?.let { ImagePrep.decodeBounded(it, maxPixels) } }.getOrNull() }
            if (bitmap != null) images[source] = bitmap else failed += source
        }
    }

    /** Retries what failed — after the connection comes back. */
    fun retryFailed() = failed.clear()

    private suspend fun bytes(source: String): ByteArray? {
        DriveImageReference.fileId(source)?.let { return resolveDrive(it) }
        if (source.startsWith("data:")) {
            val comma = source.indexOf(',')
            if (comma < 0) return null
            val payload = source.substring(comma + 1)
            return if (source.substring(0, comma).endsWith(";base64")) Base64.decode(payload, Base64.DEFAULT) else java.net.URLDecoder.decode(payload, "UTF-8").toByteArray()
        }
        if (source.startsWith("http://") || source.startsWith("https://")) {
            http.newCall(Request.Builder().url(source).build()).execute().use { return if (it.isSuccessful) it.body?.bytes() else null }
        }
        return null
    }
}
