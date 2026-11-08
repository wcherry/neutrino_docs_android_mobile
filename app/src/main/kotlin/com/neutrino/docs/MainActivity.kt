package com.neutrino.docs

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.neutrino.docs.ui.DocsBrand
import com.neutrino.docs.ui.Root
import com.neutrino.shared.android.LocalNeutrinoAccount
import com.neutrino.shared.android.security.AppLock
import com.neutrino.shared.android.ui.NeutrinoTheme
import com.neutrino.shared.android.ui.neutrinoColorScheme
import kotlinx.coroutines.launch

/** The one activity. A `FragmentActivity` because `BiometricPrompt` needs one; everything on screen is Compose. */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val settings = graph.account.settings
        if (savedInstanceState == null) {
            AppLock.lockOnLaunch(settings.appLock.value)
            handle(intent)
        }
        // With the lock on, the recents screen shows a blank card rather than a document.
        lifecycleScope.launch {
            settings.appLock.collect { on ->
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        setContent {
            val theme by graph.prefs.theme.collectAsState()
            val dark = when (theme) {
                AppTheme.SYSTEM -> isSystemInDarkTheme()
                AppTheme.LIGHT -> false
                AppTheme.DARK -> true
            }
            CompositionLocalProvider(LocalNeutrinoAccount provides graph.account) {
                NeutrinoTheme(DocsBrand, neutrinoColorScheme(DocsBrand, dark)) {
                    Root()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        AppLock.onForeground(graph.account.settings.appLock.value)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) AppLock.onBackground()
    }

    /**
     * `https://www.getneutrino.app/open/doc/<id>` and `neutrinodocs://doc/<id>` name a document; only
     * the id travels, and the document is fetched here, so permissions stay the server's. Held by the
     * router until the app is signed in and showing.
     */
    private fun handle(intent: Intent?) {
        val data = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        DocLinks.fileId(data)?.let { graph.router.pendingLink = it; return }
        if (data.scheme == "neutrinodocs" && data.host == "new") graph.router.newDocumentRequested = true
    }
}

/** The document links this app answers. `/open/note/…` and the like belong to the sibling apps. */
object DocLinks {
    fun fileId(uri: Uri): String? {
        val segments = uri.pathSegments
        return when {
            uri.scheme == "https" && (uri.host == "www.getneutrino.app" || uri.host == "getneutrino.app") &&
                segments.size >= 3 && segments[0] == "open" && segments[1] == "doc" -> segments[2]
            uri.scheme == "neutrinodocs" && uri.host == "doc" -> segments.firstOrNull()
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    fun link(fileId: String) = "https://www.getneutrino.app/open/doc/$fileId"
}
