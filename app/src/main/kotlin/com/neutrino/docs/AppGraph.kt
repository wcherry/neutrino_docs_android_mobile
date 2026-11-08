package com.neutrino.docs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.neutrino.docs.core.api.DocsApi
import com.neutrino.docs.core.model.CacheReason
import com.neutrino.docs.core.search.IndexKeyStore
import com.neutrino.docs.core.search.SearchIndexStore
import com.neutrino.docs.core.search.SearchService
import com.neutrino.docs.core.service.DeviceSessionService
import com.neutrino.docs.core.service.DocContentService
import com.neutrino.docs.core.service.DocsDriveService
import com.neutrino.docs.core.service.OfflineStore
import com.neutrino.docs.core.service.SyncEngine
import com.neutrino.docs.core.service.VersionHistoryService
import com.neutrino.shared.android.NeutrinoAccount
import com.neutrino.shared.android.net.NetworkMonitor
import com.neutrino.shared.android.storage.SecureStore
import com.neutrino.shared.core.api.NeutrinoApps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Every long-lived object, built once per process in `Application.onCreate` — the background sync
 * job can start the process with no screen. The iOS app's composition root. Main-thread confined.
 */
class AppGraph(context: Context) {
    val context: Context = context.applicationContext

    /** Sign-in, the session, the keyring and the HTTP client, from neutrino_shared_android. */
    val account = NeutrinoAccount(this.context, NeutrinoApps.docs, "NeutrinoDocs-Android/${BuildConfig.VERSION_NAME}") { http, auth -> DocsApi(http, auth) }
    val api: DocsApi get() = account.api
    val prefs = DocsPrefs(account.settings.prefs)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val network = NetworkMonitor(this.context)
    val isOnline: StateFlow<Boolean> = network.isOnline
    /** A metered network, which "Sync on Cellular" can rule out. */
    val isMetered: StateFlow<Boolean> = network.isMetered

    val content = DocContentService(account.api, { account.keys.keyring() }, account.keyCheck, ::thumbnailBase64)
    val offline = OfflineStore(File(this.context.noBackupFilesDir, "offline-docs"), content)
    val drive = DocsDriveService(account.api, content, offline, scope) { isOnline.value }
    val sync = SyncEngine(offline, content, drive, scope) { isOnline.value && (prefs.syncOnCellular.value || !isMetered.value) }
    val versions = VersionHistoryService(account.api, content)
    val sessions = DeviceSessionService(account.api, scope) { account.auth.config.headerSafeDeviceName(account.settings.deviceName) }
    val search = SearchService(
        SearchIndexStore(File(this.context.noBackupFilesDir, "search-index"), SecureIndexKey(SecureStore(this.context, "neutrino.docs.search.v1"))),
        scope, drive, content, offline,
    )
    val router = AppRouter()

    init {
        account.onSignOut {
            // Nothing decrypted for this account — the cache's ciphertext, the index, the pictures —
            // may outlive its session; the next account must start clean.
            content.reset()
            drive.reset()
            offline.wipe()
            search.signOut()
            File(this.context.cacheDir, "exports").deleteRecursively()
            router.reset()
        }
        network.start()
        // Drains the queue on reconnecting; going offline stops a drain mid-flight.
        isOnline.drop(1).onEach { online ->
            if (account.auth.isSignedIn && (prefs.autoSync.value || !online)) sync.connectivityChanged(online)
        }.launchIn(scope)
        if (account.auth.isSignedIn) scope.launch { search.start() }
    }

    /** After sign-in: load the index and drain anything queued. */
    fun signedIn() {
        scope.launch { search.start() }
        if (prefs.autoSync.value) sync.requestSync()
    }

    /** Offline mode's "cache recent / favorites": what the listings brought in, kept automatically. */
    fun cacheAutomatically() {
        if (!isOnline.value) return
        scope.launch {
            val wanted = buildList {
                if (prefs.cacheRecents.value) addAll(drive.recentItems.value.take(10).map { it to CacheReason.RECENT })
                if (prefs.cacheFavorites.value) addAll(drive.starredItems.value.filter { it.isEditable }.map { it to CacheReason.FAVORITE })
            }
            for ((item, reason) in wanted) {
                if (offline.isAvailableOffline(item.id)) continue
                runCatching { offline.download(item, reason) }
            }
        }
    }

    companion object {
        /**
         * The 512px JPEG preview stored beside an uploaded picture — the web's size and quality. The
         * server cannot make one from ciphertext, so without it a picture shows as a blank icon in
         * every Drive listing.
         */
        fun thumbnailBase64(data: ByteArray): String? = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 512) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val oriented = ImagePrep.applyExifOrientation(decoded, data)
            val scale = 512f / maxOf(oriented.width, oriented.height)
            val thumb = if (scale < 1f) Bitmap.createScaledBitmap(oriented, (oriented.width * scale).toInt().coerceAtLeast(1), (oriented.height * scale).toInt().coerceAtLeast(1), true) else oriented
            val out = ByteArrayOutputStream()
            thumb.compress(Bitmap.CompressFormat.JPEG, 80, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }.getOrNull()
    }
}

/** The search index's key, sealed by an Android Keystore key of its own; it never leaves the device. */
class SecureIndexKey(private val store: SecureStore) : IndexKeyStore {
    override fun load(): ByteArray? = store.get(ITEM)
    override fun save(key: ByteArray) = store.set(ITEM, key)
    override fun delete() = store.delete(ITEM)

    companion object {
        private const val ITEM = "search-index-key"
    }
}
