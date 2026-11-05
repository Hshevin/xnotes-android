package com.xnotes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.xnotes.R
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.xnotes.ui.theme.Space
import com.xnotes.ui.theme.TextSize

// This MovinkPad build's own repository; the upstream project is credited by UPSTREAM_URL.
private const val REPO_URL = "https://github.com/Hshevin/xnotes-android"
private const val RELEASES_URL = "$REPO_URL/releases"
private const val ISSUES_URL = "$REPO_URL/issues/new"
private const val LICENSE_URL = "$REPO_URL/blob/master/LICENSE"
private const val UPSTREAM_URL = "https://github.com/shardulvs/xnotes-android"
private const val MIN_FILL_MS = 120L

/**
 * The About backstage pane: app identity + the one place that points users back at the
 * project. Since the app ships with no accounts or telemetry, these links are the only
 * feedback channel. The bug/feature links open GitHub's new-issue form with the body
 * pre-filled (app version + device + OS for bug reports). Back from here returns to Home.
 */
@Composable
fun AboutPane() {
    val palette = LocalPalette.current
    val ctx = LocalContext.current

    val appIcon = remember {
        runCatching { ctx.packageManager.getApplicationIcon(ctx.packageName).toBitmap(144, 144).asImageBitmap() }.getOrNull()
    }
    val version = remember {
        runCatching {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode else pi.versionCode.toLong()
            "${pi.versionName} ($code)"
        }.getOrDefault("")
    }

    fun open(url: String) {
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(top = Space.s16, bottom = Space.s32),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (appIcon != null) {
                Image(appIcon, "xnotes", modifier = Modifier.size(72.dp))
                Spacer(Modifier.height(Space.s16))
            }
            Text("xnotes", color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = TextSize.display)
            Spacer(Modifier.height(Space.s4))
            Text(
                stringResource(R.string.app_tagline),
                color = palette.textDim.toComposeColor(), fontSize = TextSize.body, textAlign = TextAlign.Center,
            )
            if (version.isNotEmpty()) {
                Spacer(Modifier.height(Space.s8))
                // Tap to copy, so a version string is easy to paste into a report.
                Tooltip(stringResource(R.string.copy_version)) {
                    Row(
                        Modifier.clickable { copyVersion(ctx, version) }.padding(horizontal = Space.s8, vertical = Space.s4),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.version_n, version), color = palette.textDim.toComposeColor(), fontSize = TextSize.label)
                        Spacer(Modifier.width(Space.s6))
                        Icon(XnotesIcons.copy, stringResource(R.string.copy_version), tint = palette.textDim.toComposeColor(), modifier = Modifier.size(12.dp))
                    }
                }
            }

            Spacer(Modifier.height(Space.s32))
            Text(stringResource(R.string.help_make_better), color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = TextSize.title)
            Spacer(Modifier.height(Space.s16))

            // Three buttons, side by side; each fills with the selection container while pressed.
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(Space.s8),
            ) {
                AboutButton(XnotesIcons.bug, stringResource(R.string.report_bug)) { open(bugReportUrl(version)) }
                AboutButton(XnotesIcons.idea, stringResource(R.string.request_feature)) { open(featureRequestUrl()) }
                AboutButton(XnotesIcons.download, stringResource(R.string.releases)) { open(RELEASES_URL) }
            }

            Spacer(Modifier.height(Space.s24))
            // Quiet, single-line growth nudge (a link, never a popup).
            Row(
                Modifier.clickable { open(REPO_URL) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.enjoying_xnotes), color = palette.textDim.toComposeColor(), fontSize = TextSize.label)
                Text(stringResource(R.string.star_on_github), color = palette.accent.toComposeColor(), fontSize = TextSize.label, fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(Space.s16))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.mit_license), color = palette.textDim.toComposeColor(), fontSize = TextSize.caption, modifier = Modifier.clickable { open(LICENSE_URL) })
                Text("   ·   ", color = palette.textDim.toComposeColor(), fontSize = TextSize.caption)
                Text(stringResource(R.string.upstream_project), color = palette.textDim.toComposeColor(), fontSize = TextSize.caption, modifier = Modifier.clickable { open(UPSTREAM_URL) })
            }
        }
    }
}

/** A link button: line glyph above a label. Fills with the selection container while pressed. */
@Composable
private fun RowScope.AboutButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    // Keep the pressed fill visible for a minimum time so even a millisecond tap registers.
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(interaction) {
        val scope = this
        var pressedAt = 0L
        var clearJob: Job? = null
        interaction.interactions.collect { event ->
            when (event) {
                is PressInteraction.Press -> {
                    clearJob?.cancel()
                    pressedAt = System.currentTimeMillis()
                    pressed = true
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    val remaining = MIN_FILL_MS - (System.currentTimeMillis() - pressedAt)
                    clearJob?.cancel()
                    clearJob = scope.launch {
                        if (remaining > 0) delay(remaining)
                        pressed = false
                    }
                }
            }
        }
    }
    val accent = palette.accent.toComposeColor()
    val onAccent = palette.selectionForeground.toComposeColor()
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(MaterialTheme.shapes.medium)
            .background(if (pressed) palette.selectionBackground.toComposeColor() else Color.Transparent)
            .border(1.dp, if (pressed) accent else palette.border.toComposeColor(), MaterialTheme.shapes.medium)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = Space.s16, horizontal = Space.s8),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = if (pressed) onAccent else accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(Space.s8))
        Text(
            label,
            color = if (pressed) onAccent else palette.text.toComposeColor(),
            fontSize = TextSize.label, lineHeight = 14.sp, textAlign = TextAlign.Center,
        )
    }
}

private fun copyVersion(ctx: Context, version: String) {
    runCatching {
        val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("xnotes version", "xnotes $version"))
        Toast.makeText(ctx, ctx.getString(R.string.version_copied), Toast.LENGTH_SHORT).show()
    }
}

/** GitHub new-issue link with a bug template and the reporter's version/device/OS pre-filled. */
private fun bugReportUrl(version: String): String {
    val body = """
        **What happened?**


        **Steps to reproduce**
        1.
        2.

        **What did you expect instead?**


        ---
        xnotes ${version.ifEmpty { "(unknown)" }}
        ${Build.MANUFACTURER} ${Build.MODEL}
        Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
    """.trimIndent()
    return "$ISSUES_URL?title=${Uri.encode("[Bug] ")}&body=${Uri.encode(body)}"
}

/** GitHub new-issue link with a lightweight feature template. */
private fun featureRequestUrl(): String {
    val body = """
        **What would you like xnotes to do?**

    """.trimIndent()
    return "$ISSUES_URL?title=${Uri.encode("[Feature] ")}&body=${Uri.encode(body)}"
}
