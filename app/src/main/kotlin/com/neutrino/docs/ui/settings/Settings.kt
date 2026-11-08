package com.neutrino.docs.ui.settings

import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.neutrino.docs.AppTheme
import com.neutrino.docs.BuildConfig
import com.neutrino.docs.DocsPrefs
import com.neutrino.docs.EditorFont
import com.neutrino.docs.Route
import com.neutrino.docs.SyncWorker
import com.neutrino.docs.graph
import com.neutrino.docs.ui.ActionRow
import com.neutrino.docs.ui.ChoiceRow
import com.neutrino.docs.ui.DocsStyle
import com.neutrino.docs.ui.ErrorText
import com.neutrino.docs.ui.FormRow
import com.neutrino.docs.ui.FormSection
import com.neutrino.docs.ui.PageBar
import com.neutrino.docs.ui.SwitchRow
import com.neutrino.docs.ui.ValueRow
import com.neutrino.docs.ui.formatDateTime
import com.neutrino.shared.android.security.AppLock
import com.neutrino.shared.android.ui.ConfirmDialog
import com.neutrino.shared.android.ui.NeutrinoBrand
import com.neutrino.shared.android.ui.TextInputDialog
import com.neutrino.shared.android.ui.openUrl
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val graph = context.graph
    val prefs = graph.prefs
    val router = graph.router
    val theme by prefs.theme.collectAsState()
    val fontSize by prefs.fontSize.collectAsState()
    val font by prefs.editorFont.collectAsState()
    val autoSave by prefs.autoSaveInterval.collectAsState()
    val smartLists by prefs.smartLists.collectAsState()
    val closeBrackets by prefs.closeBrackets.collectAsState()
    val autoSync by prefs.autoSync.collectAsState()
    val cellular by prefs.syncOnCellular.collectAsState()
    val cacheRecents by prefs.cacheRecents.collectAsState()
    val cacheFavorites by prefs.cacheFavorites.collectAsState()
    val appLock by graph.account.settings.appLock.collectAsState()
    val keyring by graph.account.keyringVersion.collectAsState()
    var hasKey by remember { mutableStateOf(graph.account.keys.hasKey) }
    var canSetUp by remember { mutableStateOf(false) }
    var confirmRemoveKey by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var editingDeviceName by remember { mutableStateOf(false) }

    LaunchedEffect(keyring) {
        hasKey = graph.account.keys.hasKey
        // First-time setup only for an account with no key anywhere; any other needs its key brought here.
        canSetUp = !hasKey && graph.account.keys.canProvision()
    }

    Column(Modifier.fillMaxSize()) {
        PageBar("Settings", null)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            FormSection("Appearance") {
                ChoiceRow("Theme", theme, AppTheme.entries, { it.title }) { prefs.setTheme(it) }
            }
            FormSection("Editor", "Headings, quotes and code scale with the size you choose here. Auto-save writes after you stop typing for this long.") {
                ChoiceRow("Editor Font", font, EditorFont.entries, { it.title }) { prefs.setEditorFont(it) }
                FormRow {
                    Text("Font Size", Modifier.weight(1f))
                    Text("${fontSize.toInt()} pt", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Slider(fontSize, { prefs.setFontSize(Math.round(it).toFloat()) }, valueRange = DocsPrefs.FONT_SIZE_RANGE, modifier = Modifier.padding(horizontal = 16.dp))
                Text("The quick brown fox jumps over the lazy dog.", fontSize = fontSize.sp, modifier = Modifier.padding(16.dp),
                    fontFamily = when (font) { EditorFont.MONOSPACED -> FontFamily.Monospace; EditorFont.SERIF -> FontFamily.Serif; else -> FontFamily.SansSerif })
                ChoiceRow("Auto-Save", autoSave, DocsPrefs.AUTO_SAVE_OPTIONS, DocsPrefs::autoSaveLabel) { prefs.setAutoSaveInterval(it) }
            }
            FormSection("Writing", "Continue Lists carries a bullet, number or checkbox onto the next line when you press Return, and ends the list on an empty one.") {
                SwitchRow("Continue Lists", smartLists) { prefs.setSmartLists(it) }
                SwitchRow("Close Brackets", closeBrackets) { prefs.setCloseBrackets(it) }
            }
            FormSection("Synchronization", "Off, changes made offline are sent only when you pull to refresh or tap Sync Now.") {
                SwitchRow("Auto-Sync", autoSync) {
                    prefs.setAutoSync(it)
                    SyncWorker.schedule(context, it)
                    if (it) graph.sync.requestSync()
                }
                SwitchRow("Sync on Cellular", cellular) { prefs.setSyncOnCellular(it) }
            }
            FormSection("Offline", "Clearing keeps documents you chose to keep offline, and anything with unsynced changes.") {
                SwitchRow("Cache Recent Documents", cacheRecents) { prefs.setCacheRecents(it) }
                SwitchRow("Cache Favorites", cacheFavorites) { prefs.setCacheFavorites(it) }
                ActionRow("Clear Automatic Cache") { graph.offline.evictAutomaticEntries() }
            }
            FormSection("Encryption", if (hasKey) "Documents are decrypted on this phone, and encrypted before they're uploaded. The key never leaves the device."
                else "Your documents are end-to-end encrypted. To open them here, bring your key from a device that has it.") {
                if (hasKey) {
                    FormRow {
                        Icon(Icons.Filled.VerifiedUser, null, Modifier.size(20.dp), tint = NeutrinoBrand.success)
                        Spacer(Modifier.width(12.dp))
                        Text("This phone has your encryption key")
                    }
                    ActionRow("Remove Key From This Phone", color = MaterialTheme.colorScheme.error) { confirmRemoveKey = true }
                } else {
                    FormRow {
                        Icon(Icons.Filled.Warning, null, Modifier.size(20.dp), tint = DocsStyle.warning)
                        Spacer(Modifier.width(12.dp))
                        Text("No encryption key on this phone", color = DocsStyle.warning)
                    }
                    if (canSetUp) ActionRow("Set Up Encryption", Icons.Filled.Key) { router.push(Route.SetUpEncryption) }
                    ActionRow("Pair With Another Device", Icons.Filled.QrCodeScanner) { router.push(Route.Pairing) }
                    ActionRow("Restore From Recovery Kit", Icons.AutoMirrored.Filled.MenuBook) { router.push(Route.RecoveryKit) }
                    ActionRow("Scan Key Code", Icons.Filled.Password) { router.push(Route.KeyCode) }
                }
            }
            FormSection("Security") {
                ValueRow("App Lock", if (appLock) "On" else "Off", Icons.Filled.Lock, onClick = { router.push(Route.AppLockSettings) })
                ValueRow("Registered Devices", "", Icons.Filled.Devices, onClick = { router.push(Route.Devices) })
            }
            FormSection("Account") {
                ValueRow("Signed in as", graph.account.auth.email ?: "—")
                ValueRow("Server", graph.account.settings.serverUrl.removePrefix("https://"))
                ValueRow("Device name", graph.account.settings.deviceName, onClick = { editingDeviceName = true })
                ActionRow("Reset Settings to Defaults") { prefs.resetToDefaults() }
                ActionRow("Sign Out", Icons.AutoMirrored.Filled.Logout, MaterialTheme.colorScheme.error) { confirmSignOut = true }
            }
            FormSection("About") {
                ValueRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                ActionRow("Report a Bug", Icons.Filled.BugReport) { openUrl(context, BugReport.url(graph.account.settings.serverUrl)) }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
    if (confirmRemoveKey) ConfirmDialog("Remove Key?", "Your documents stay encrypted on the server. You'll need to bring your key back to read them on this phone.",
        "Remove", destructive = true, onDismiss = { confirmRemoveKey = false }) {
        graph.account.keys.remove()
        graph.account.keyringChanged()
    }
    if (confirmSignOut) ConfirmDialog("Sign Out?", "Documents stored on this phone, queued changes and the search index are removed. Your key stays, so signing back in doesn't need it again.",
        "Sign Out", destructive = true, onDismiss = { confirmSignOut = false }) { graph.account.signOut() }
    if (editingDeviceName) TextInputDialog("Device name", graph.account.settings.deviceName, "Shown in your account's device list", "Save",
        onDismiss = { editingDeviceName = false }) {
        graph.account.settings.customDeviceName = it
        editingDeviceName = false
    }
}

/** Locks Docs with a fingerprint, face or the device PIN on return; the recents card is blanked while on. */
@Composable
fun AppLockScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val on by graph.account.settings.appLock.collectAsState()
    val available = AppLock.isAvailable(context)
    Column(Modifier.fillMaxSize()) {
        PageBar("App Lock", onBack)
        FormSection(footer = if (available) "Asks for your fingerprint, face or PIN when you come back to Docs after ${AppLock.GRACE_MILLIS / 1000} seconds away. Your documents are hidden from the recents screen immediately."
            else "Set up a screen lock in Android Settings to use App Lock.") {
            SwitchRow("Require Unlock", on, enabled = available) { enable ->
                scope.launch {
                    if (AppLock.authenticate(context as FragmentActivity, if (enable) "Turn on app lock" else "Turn off app lock")) graph.account.settings.setAppLock(enable)
                }
            }
        }
    }
}

/** The devices registered against the account; revoking one signs it out. */
@Composable
fun DevicesScreen(onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val sessions by graph.sessions.sessions.collectAsState()
    val loading by graph.sessions.isLoading.collectAsState()
    val error by graph.sessions.error.collectAsState()
    var revoking by remember { mutableStateOf<com.neutrino.docs.core.model.DeviceSession?>(null) }
    LaunchedEffect(Unit) { graph.sessions.load() }
    Column(Modifier.fillMaxSize()) {
        PageBar("Registered Devices", onBack)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            FormSection("Devices", "This phone registered as “${graph.account.settings.deviceName}” when you signed in. Revoking signs that device out.") {
                if (sessions.isEmpty() && !loading) Text("No registered devices found.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                sessions.forEach { s ->
                    FormRow {
                        Column(Modifier.weight(1f)) {
                            Text(s.deviceName?.takeIf { it.isNotBlank() } ?: s.userAgent ?: "Unknown device")
                            Text((if (s.isCurrentDevice) "This device · " else "") + (s.lastUsedAt?.let { "Last used ${formatDateTime(it)}" } ?: "Registered ${formatDateTime(s.createdAt)}"),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { revoking = s }) { Icon(Icons.AutoMirrored.Filled.Logout, "Revoke", tint = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            ErrorText(error)
        }
    }
    revoking?.let { s ->
        ConfirmDialog("Revoke Device?", if (s.isCurrentDevice) "This is the phone you're using: revoking it signs you out here." else "That device will be signed out.", "Revoke",
            destructive = true, onDismiss = { revoking = null }) { graph.sessions.revoke(s.id) }
    }
}

/**
 * The GitHub issue form behind "Report a Bug", prefilled with what this build knows about itself —
 * in fields the user reads and can edit before submitting, since an issue is public. The server's
 * *kind*, never its address.
 */
object BugReport {
    const val REPOSITORY = "wcherry/neutrino_docs_android_mobile"

    fun url(server: String): String {
        val host = server.lowercase()
        val kind = when {
            host.contains("getneutrino.app") -> "getneutrino.app (hosted)"
            host.contains("localhost") || host.contains("127.0.0.1") || host.contains("10.0.2.2") -> "localhost / development"
            else -> "Self-hosted"
        }
        return Uri.parse("https://github.com/$REPOSITORY/issues/new").buildUpon()
            .appendQueryParameter("template", "1-bug-report.yml")
            .appendQueryParameter("version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            .appendQueryParameter("android", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            .appendQueryParameter("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .appendQueryParameter("install", if (BuildConfig.DEBUG) "Built from source" else "Release build")
            .appendQueryParameter("server", kind)
            .build().toString()
    }
}
