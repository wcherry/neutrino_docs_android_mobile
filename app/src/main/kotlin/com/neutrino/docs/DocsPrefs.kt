package com.neutrino.docs

import android.content.SharedPreferences
import com.neutrino.docs.core.editing.DocumentInputRules
import com.neutrino.docs.core.model.DocSortOrder
import com.neutrino.docs.core.model.DocsSection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class AppTheme(val title: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

/** The editor's face. Monospaced by default, as on iOS: a document's alignment reads best in a fixed pitch. */
enum class EditorFont(val title: String) { MONOSPACED("Monospaced"), SYSTEM("System"), SERIF("Serif"), ROUNDED("Rounded") }

/**
 * The user's preferences, in the account's own `ndoc.settings` file under `ndoc.` keys — the iOS
 * app's settings, value for value.
 */
class DocsPrefs(private val prefs: SharedPreferences) {

    private fun <T> state(initial: T) = MutableStateFlow(initial)

    private val _theme = state(AppTheme.entries.firstOrNull { it.name == prefs.getString(THEME, null) } ?: AppTheme.SYSTEM)
    val theme: StateFlow<AppTheme> = _theme
    fun setTheme(v: AppTheme) { prefs.edit().putString(THEME, v.name).apply(); _theme.value = v }

    private val _fontSize = state(prefs.getFloat(FONT_SIZE, DEFAULT_FONT_SIZE).coerceIn(FONT_SIZE_RANGE))
    val fontSize: StateFlow<Float> = _fontSize
    fun setFontSize(v: Float) { val c = v.coerceIn(FONT_SIZE_RANGE); prefs.edit().putFloat(FONT_SIZE, c).apply(); _fontSize.value = c }

    private val _editorFont = state(EditorFont.entries.firstOrNull { it.name == prefs.getString(EDITOR_FONT, null) } ?: EditorFont.MONOSPACED)
    val editorFont: StateFlow<EditorFont> = _editorFont
    fun setEditorFont(v: EditorFont) { prefs.edit().putString(EDITOR_FONT, v.name).apply(); _editorFont.value = v }

    private val _autoSave = state(prefs.getInt(AUTO_SAVE, DEFAULT_AUTO_SAVE))
    /** Seconds. */
    val autoSaveInterval: StateFlow<Int> = _autoSave
    fun setAutoSaveInterval(v: Int) { prefs.edit().putInt(AUTO_SAVE, v).apply(); _autoSave.value = v }

    private val _autoSync = state(prefs.getBoolean(AUTO_SYNC, true))
    /** When off, the queue drains only when asked — pull to refresh, Sync Now. */
    val autoSync: StateFlow<Boolean> = _autoSync
    fun setAutoSync(v: Boolean) { prefs.edit().putBoolean(AUTO_SYNC, v).apply(); _autoSync.value = v }

    private val _syncOnCellular = state(prefs.getBoolean(SYNC_CELLULAR, true))
    val syncOnCellular: StateFlow<Boolean> = _syncOnCellular
    fun setSyncOnCellular(v: Boolean) { prefs.edit().putBoolean(SYNC_CELLULAR, v).apply(); _syncOnCellular.value = v }

    private val _cacheRecents = state(prefs.getBoolean(CACHE_RECENTS, true))
    val cacheRecents: StateFlow<Boolean> = _cacheRecents
    fun setCacheRecents(v: Boolean) { prefs.edit().putBoolean(CACHE_RECENTS, v).apply(); _cacheRecents.value = v }

    private val _cacheFavorites = state(prefs.getBoolean(CACHE_FAVORITES, true))
    val cacheFavorites: StateFlow<Boolean> = _cacheFavorites
    fun setCacheFavorites(v: Boolean) { prefs.edit().putBoolean(CACHE_FAVORITES, v).apply(); _cacheFavorites.value = v }

    private val _sortOrder = state(DocSortOrder.entries.firstOrNull { it.name == prefs.getString(SORT, null) })
    /** Null until the user picks one, so each section keeps its own default — Recent stays newest first. */
    val sortOrder: StateFlow<DocSortOrder?> = _sortOrder
    fun setSortOrder(v: DocSortOrder?) { prefs.edit().putString(SORT, v?.name).apply(); _sortOrder.value = v }
    fun sortOrder(section: DocsSection): DocSortOrder = _sortOrder.value ?: section.defaultSortOrder

    private val _smartLists = state(prefs.getBoolean(SMART_LISTS, true))
    val smartLists: StateFlow<Boolean> = _smartLists
    fun setSmartLists(v: Boolean) { prefs.edit().putBoolean(SMART_LISTS, v).apply(); _smartLists.value = v }

    private val _closeBrackets = state(prefs.getBoolean(CLOSE_BRACKETS, true))
    val closeBrackets: StateFlow<Boolean> = _closeBrackets
    fun setCloseBrackets(v: Boolean) { prefs.edit().putBoolean(CLOSE_BRACKETS, v).apply(); _closeBrackets.value = v }

    val inputRules: DocumentInputRules.Options get() = DocumentInputRules.Options(continueLists = _smartLists.value, closeBrackets = _closeBrackets.value)

    fun resetToDefaults() {
        setTheme(AppTheme.SYSTEM); setFontSize(DEFAULT_FONT_SIZE); setEditorFont(EditorFont.MONOSPACED)
        setAutoSaveInterval(DEFAULT_AUTO_SAVE); setAutoSync(true); setSyncOnCellular(true)
        setCacheRecents(true); setCacheFavorites(true); setSortOrder(null); setSmartLists(true); setCloseBrackets(true)
    }

    companion object {
        val FONT_SIZE_RANGE = 11f..24f
        const val DEFAULT_FONT_SIZE = 17f
        val AUTO_SAVE_OPTIONS = listOf(1, 2, 5, 10, 30)
        const val DEFAULT_AUTO_SAVE = 2

        fun autoSaveLabel(seconds: Int) = if (seconds == 1) "Every second" else "Every $seconds seconds"

        private const val THEME = "ndoc.theme"
        private const val FONT_SIZE = "ndoc.font_size"
        private const val EDITOR_FONT = "ndoc.editor_font"
        private const val AUTO_SAVE = "ndoc.auto_save_seconds"
        private const val AUTO_SYNC = "ndoc.auto_sync"
        private const val SYNC_CELLULAR = "ndoc.sync_on_cellular"
        private const val CACHE_RECENTS = "ndoc.cache_recents"
        private const val CACHE_FAVORITES = "ndoc.cache_favorites"
        private const val SORT = "ndoc.sort_order"
        private const val SMART_LISTS = "ndoc.smart_lists"
        private const val CLOSE_BRACKETS = "ndoc.close_brackets"
    }
}
