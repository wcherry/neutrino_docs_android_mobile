package com.neutrino.docs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.neutrino.docs.core.model.DocItem
import com.neutrino.docs.core.model.DocsSection

/** The bottom bar. Favorites, Shared and Trash are sections of Documents, as five tabs is the comfortable most. */
enum class Tab(val label: String) { DOCUMENTS("Documents"), RECENT("Recent"), SEARCH("Search"), OFFLINE("Offline"), SETTINGS("Settings") }

/** A screen pushed over a tab. */
sealed class Route {
    data class Folder(val section: DocsSection, val folderId: String, val title: String) : Route()
    data class Editor(val item: DocItem) : Route()
    object Devices : Route()
    object AppLockSettings : Route()
    object Pairing : Route()
    object RecoveryKit : Route()
    object KeyCode : Route()
    object SetUpEncryption : Route()
    object Trash : Route()
}

/**
 * Where the app is showing, for what arrives from outside the tabs — a link naming a document.
 * Held in the process, so a link that lands during sign-in still opens once the app shows.
 */
class AppRouter {
    var tab by mutableStateOf(Tab.DOCUMENTS)
    private val stacks = Tab.entries.associateWith { mutableStateListOf<Route>() }

    fun stack(tab: Tab): List<Route> = stacks.getValue(tab)
    val current: Route? get() = stacks.getValue(tab).lastOrNull()

    fun push(route: Route, on: Tab = tab) {
        tab = on
        stacks.getValue(on).add(route)
    }

    fun pop(): Boolean {
        val stack = stacks.getValue(tab)
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun popWhile(predicate: (Route) -> Boolean) {
        val stack = stacks.getValue(tab)
        while (stack.isNotEmpty() && predicate(stack.last())) stack.removeAt(stack.lastIndex)
    }

    /** A second tap on the tab showing goes back to its root, as tab bars do. */
    fun select(tab: Tab) {
        if (this.tab == tab) stacks.getValue(tab).clear()
        this.tab = tab
    }

    /** A document to open, from a link: always onto Documents, with the stack reset so no unwalked back trail. */
    fun open(item: DocItem) {
        stacks.getValue(Tab.DOCUMENTS).clear()
        push(Route.Editor(item), Tab.DOCUMENTS)
    }

    var pendingLink by mutableStateOf<String?>(null)
    var linkError by mutableStateOf<String?>(null)
    var newDocumentRequested by mutableStateOf(false)

    fun reset() {
        stacks.values.forEach { it.clear() }
        tab = Tab.DOCUMENTS
        pendingLink = null
        linkError = null
        newDocumentRequested = false
    }
}
