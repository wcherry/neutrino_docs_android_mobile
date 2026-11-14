package com.neutrino.docs.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.neutrino.docs.AppGraph
import com.neutrino.docs.Route
import com.neutrino.docs.Tab
import com.neutrino.docs.core.model.DocsSection
import com.neutrino.docs.core.service.activity
import com.neutrino.docs.graph
import com.neutrino.docs.ui.browser.BrowserScreen
import com.neutrino.docs.ui.editor.EditorScreen
import com.neutrino.docs.ui.offline.OfflineScreen
import com.neutrino.docs.ui.search.SearchScreen
import com.neutrino.docs.ui.settings.AppLockScreen
import com.neutrino.docs.ui.settings.DevicesScreen
import com.neutrino.docs.ui.settings.SettingsScreen
import com.neutrino.shared.android.security.AppLock
import com.neutrino.shared.android.ui.ConnectivityBanner
import com.neutrino.shared.android.ui.LockScreen
import com.neutrino.shared.android.ui.auth.LoginScreen
import com.neutrino.shared.android.ui.auth.RegisterScreen
import com.neutrino.shared.android.ui.keys.EncryptionSetupScreen
import com.neutrino.shared.android.ui.keys.KeyCodeScreen
import com.neutrino.shared.android.ui.keys.PairingScreen
import com.neutrino.shared.android.ui.keys.RecoveryKitScreen

/** Signed out → sign-in; just registered → encryption setup; otherwise the tabs. The lock sits over all of it. */
@Composable
fun Root() {
    val graph = LocalContext.current.graph
    val signedIn by graph.account.signedIn.collectAsState()
    val locked by AppLock.locked.collectAsState()
    var justRegistered by rememberSaveable { mutableStateOf(false) }
    var registering by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(signedIn) { if (signedIn) graph.signedIn() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when {
            !signedIn -> if (registering) {
                BackHandler { registering = false }
                RegisterScreen(onBack = { registering = false }, onRegistered = { justRegistered = true; registering = false })
            } else {
                LoginScreen(onRegister = { registering = true })
            }
            justRegistered -> Surface(Modifier.fillMaxSize()) {
                Box(Modifier.padding(top = 32.dp)) { EncryptionSetupScreen(onDone = { justRegistered = false }) }
            }
            // Keyed on the account, so a different sign-in never sees the last one's screens.
            else -> key(graph.account.auth.userId) { Home(graph) }
        }
        if (locked && signedIn) LockScreen()
    }
}

@Composable
private fun Home(graph: AppGraph) {
    val router = graph.router
    val documents by graph.offline.documents.collectAsState()
    val mutations by graph.offline.pendingMutations.collectAsState()
    val pending = documents.count { it.pendingEdit != null } + mutations.size

    // A link that arrived while signed out, or just now: fetched here, so it can name a document no listing has loaded.
    LaunchedEffect(router.pendingLink) {
        val id = router.pendingLink ?: return@LaunchedEffect
        router.pendingLink = null
        val cached = graph.drive.item(id)?.takeIf { !it.isFolder }
        try {
            router.open(cached ?: graph.drive.fetchItem(id))
        } catch (e: Exception) {
            router.linkError = e.message ?: "That document is no longer available."
        }
    }

    BackHandler(enabled = router.current != null) { router.pop() }

    val inEditor = router.current is Route.Editor
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            // A document takes the whole screen: the tab bar is for choosing what to open.
            if (!inEditor) NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = router.tab == tab, onClick = { router.select(tab) }, label = { Text(tab.label) },
                        icon = {
                            val icon = when (tab) {
                                Tab.DOCUMENTS -> Icons.Filled.Description
                                Tab.RECENT -> Icons.Filled.Schedule
                                Tab.SEARCH -> Icons.Filled.Search
                                Tab.OFFLINE -> Icons.Filled.CloudDownload
                                Tab.SETTINGS -> Icons.Filled.Settings
                            }
                            if (tab == Tab.OFFLINE && pending > 0) BadgedBox(badge = { Badge { Text(pending.toString()) } }) { Icon(icon, null) }
                            else Icon(icon, null)
                        },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
            Connectivity(graph)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val route = router.current
                if (route == null) TabRoot(router.tab) else RouteContent(route)
            }
        }
    }

    router.linkError?.let { message ->
        AlertDialog(
            onDismissRequest = { router.linkError = null },
            title = { Text("Couldn’t Open Document") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { router.linkError = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun TabRoot(tab: Tab) = when (tab) {
    Tab.DOCUMENTS -> BrowserScreen(DocsSection.HOME, null, null)
    Tab.RECENT -> BrowserScreen(DocsSection.RECENT, null, null)
    Tab.SEARCH -> SearchScreen()
    Tab.OFFLINE -> OfflineScreen()
    Tab.SETTINGS -> SettingsScreen()
}

@Composable
private fun RouteContent(route: Route) {
    val router = LocalContext.current.graph.router
    val back: () -> Unit = { router.pop() }
    when (route) {
        is Route.Folder -> BrowserScreen(route.section, route.folderId, route.title)
        is Route.Editor -> key(route.item.id) { EditorScreen(route.item, back) }
        Route.Trash -> BrowserScreen(DocsSection.TRASH, null, "Trash")
        Route.Devices -> DevicesScreen(back)
        Route.AppLockSettings -> AppLockScreen(back)
        Route.Pairing -> KeyFlow(back) { PairingScreen(it) }
        Route.RecoveryKit -> KeyFlow(back) { RecoveryKitScreen(it) }
        Route.KeyCode -> KeyFlow(back) { KeyCodeScreen(it) }
        Route.SetUpEncryption -> KeyFlow(back) { EncryptionSetupScreen(it) }
    }
}

/** A shared key screen inside the app: back closes it, finishing drops every key screen. */
@Composable
private fun KeyFlow(onBack: () -> Unit, content: @Composable (onDone: () -> Unit) -> Unit) {
    val graph = LocalContext.current.graph
    Column(Modifier.fillMaxSize()) {
        PageBar("", onBack)
        Box(Modifier.weight(1f)) {
            content {
                graph.account.keyringChanged()
                graph.router.popWhile { it is Route.Pairing || it is Route.RecoveryKit || it is Route.KeyCode || it is Route.SetUpEncryption }
            }
        }
    }
}

/**
 * Offline, the queue, syncing, a failed pass with Retry, and "Back online". Conflicted documents are
 * left out of the count: they wait on the user in the Offline tab, not on a sync.
 */
@Composable
private fun Connectivity(graph: AppGraph) {
    val online by graph.isOnline.collectAsState()
    val state by graph.sync.state.collectAsState()
    val docs by graph.offline.documents.collectAsState()
    val mutations by graph.offline.pendingMutations.collectAsState()
    val pending = docs.count { it.pendingEdit != null && it.conflict == null } + mutations.size
    ConnectivityBanner(isOnline = online, pendingChanges = pending, activity = state.activity, onRetry = { graph.sync.requestSync() })
}
