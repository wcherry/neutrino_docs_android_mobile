package com.neutrino.docs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neutrino.docs.R
import com.neutrino.shared.android.ui.NeutrinoBrand
import com.neutrino.shared.android.ui.TrustRow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Docs on the shared sign-in and lock screens: `NeutrinoBrand.docs` from the iOS package, blue into cyan. */
val DocsBrand = NeutrinoBrand(
    name = "Neutrino Docs",
    tagline = "Encrypted documents that open anywhere",
    primary = Color(0xFF007AFF),
    secondary = Color(0xFF32ADE6),
    primaryDark = Color(0xFF4DA3FF),
    secondaryDark = Color(0xFF64D2FF),
    icon = R.drawable.ic_launcher_art,
    trustRows = listOf(
        TrustRow(Icons.Filled.Lock, "End-to-end encrypted", Color(0xFF34C759)),
        TrustRow(Icons.Filled.Sync, "Synced with Neutrino Drive", Color(0xFF007AFF)),
        TrustRow(Icons.Filled.Key, "Only you hold your keys", Color(0xFF5856D6)),
    ),
    lockedSubtitle = "Your documents are encrypted on this phone.",
    registerSubtitle = "Your documents are encrypted on this phone before they are uploaded. Only you hold the key.",
    encryptionBlurb = "Your documents are encrypted with a key only you hold. It stays on this phone and is never sent to the server.",
)

object DocsStyle {
    val warning = Color(0xFFE08A00)
    val star = Color(0xFFFFB800)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageBar(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        actions = actions,
    )
}

/** A grouped section, as an iOS inset list draws one: a header, rounded rows, a footer. */
@Composable
fun FormSection(header: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (header != null) {
            Text(header.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, bottom = 6.dp))
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest), content = content)
        if (footer != null) {
            Text(footer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp))
        }
    }
}

@Composable
fun FormRow(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, divider: Boolean = true, content: @Composable RowScope.() -> Unit) {
    Column {
        Row(
            modifier.fillMaxWidth().heightIn(min = 50.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        if (divider) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

@Composable
fun ValueRow(label: String, value: String, icon: ImageVector? = null, onClick: (() -> Unit)? = null, valueColor: Color? = null) {
    FormRow(onClick = onClick) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
        }
        Text(label, Modifier.weight(1f))
        Text(value, color = valueColor ?: MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    FormRow(onClick = { if (enabled) onChange(!checked) }) {
        Text(label, Modifier.weight(1f), color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Switch(checked, onChange, enabled = enabled)
    }
}

@Composable
fun ActionRow(label: String, icon: ImageVector? = null, color: Color? = null, enabled: Boolean = true, onClick: () -> Unit) {
    FormRow(onClick = if (enabled) onClick else null) {
        val tint = if (!enabled) MaterialTheme.colorScheme.onSurfaceVariant else color ?: MaterialTheme.colorScheme.primary
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp), tint = tint)
            Spacer(Modifier.width(12.dp))
        }
        Text(label, color = tint)
    }
}

/** A choice from a short list, shown as a menu from its row. */
@Composable
fun <T> ChoiceRow(label: String, value: T, choices: List<T>, describe: (T) -> String, onChoose: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ValueRow(label, describe(value), onClick = { open = true })
        DropdownMenu(open, { open = false }) {
            choices.forEach { c ->
                DropdownMenuItem(text = { Text(describe(c)) }, onClick = { open = false; onChoose(c) })
            }
        }
    }
}

@Composable
fun ErrorText(message: String?, modifier: Modifier = Modifier) {
    message ?: return
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Warning, null, Modifier.size(16.dp), tint = DocsStyle.warning)
        Spacer(Modifier.width(6.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = DocsStyle.warning)
    }
}

/** An empty or failed state with a way forward. */
@Composable
fun Unavailable(icon: ImageVector, title: String, message: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp))
        if (action != null) Button(onClick = onAction, modifier = Modifier.padding(top = 16.dp)) { Text(action) }
    }
}

val mediumDateTime: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

fun formatDateTime(instant: Instant): String = mediumDateTime.format(instant.atZone(ZoneId.systemDefault()))

/** "3 min ago", "Yesterday", "12 Mar" — the browser's subtitle. */
fun relativeTime(instant: Instant, now: Instant = Instant.now()): String {
    val seconds = now.epochSecond - instant.epochSecond
    return when {
        seconds < 60 -> "Just now"
        seconds < 3600 -> "${seconds / 60} min ago"
        seconds < 86_400 -> "${seconds / 3600} hr ago"
        seconds < 2 * 86_400 -> "Yesterday"
        seconds < 7 * 86_400 -> "${seconds / 86_400} days ago"
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(instant.atZone(ZoneId.systemDefault()))
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
}
