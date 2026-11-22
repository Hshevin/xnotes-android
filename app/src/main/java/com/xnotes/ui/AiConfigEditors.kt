package com.xnotes.ui

import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.ai.*
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.MinTouchTarget
import com.xnotes.ui.theme.Space
import com.xnotes.ui.theme.TextSize
import com.xnotes.ui.theme.toComposeColor
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val MAX_CONFIG_FILE_BYTES = 2 * 1024 * 1024

/** Reads UTF-8 text from [stream] up to [maxBytes], returning null as soon as the cap is exceeded. */
internal fun readBoundedUtf8(stream: InputStream, maxBytes: Int = MAX_CONFIG_FILE_BYTES): String? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var total = 0
    while (true) {
        val n = stream.read(buf)
        if (n <= 0) break
        total += n
        if (total > maxBytes) return null
        out.write(buf, 0, n)
    }
    return out.toString(Charsets.UTF_8.name())
}

/**
 * Applies an imported config file onto the current settings. When merging while the JSON editor has
 * unsaved edits, those edits are parsed onto [config] first so they are never silently discarded.
 */
internal fun applyAiConfigImport(
    rawImportJson: String,
    jsonText: String,
    renderedJson: String,
    config: AiConfig,
    defaultInstructions: String,
    mode: AiConfigFile.ImportMode,
): AiConfig {
    val base = if (mode == AiConfigFile.ImportMode.MERGE && jsonText != renderedJson) {
        AiConfigFile.parseAndApply(jsonText, config, defaultInstructions, AiConfigFile.ImportMode.REPLACE)
    } else config
    return AiConfigFile.parseAndApply(rawImportJson, base, defaultInstructions, mode)
}

/** Validates one thinking-level name & JSON object payload, refusing silent duplicate overwrites. */
internal fun validateThinkingLevelEntry(
    rawName: String,
    payload: String,
    existingKeys: Set<String>,
    editingKey: String?,
    duplicateError: String,
): Result<Pair<String, String>> {
    val cleanName = rawName.trim()
    if (cleanName.isEmpty() || !AiThinking.isValidLevelName(cleanName)) {
        return Result.failure(IllegalArgumentException(AiText.t(AiMsg.CONFIG_THINKING_INVALID)))
    }
    if (cleanName != editingKey && cleanName in existingKeys) {
        return Result.failure(IllegalArgumentException(duplicateError))
    }
    return runCatching { cleanName to AiThinking.validatePayload(cleanName, payload).toString() }
}

/** Display name for a thinking level: built-in keys localize, custom keys show verbatim. */
@Composable
internal fun effortLabel(level: String): String = when (level) {
    "" -> stringResource(R.string.ai_effort_default)
    AiThinking.OFF -> stringResource(R.string.ai_effort_off)
    AiThinking.ON -> stringResource(R.string.ai_effort_on)
    "minimal" -> stringResource(R.string.ai_effort_minimal)
    "low" -> stringResource(R.string.ai_effort_low)
    "medium" -> stringResource(R.string.ai_effort_medium)
    "high" -> stringResource(R.string.ai_effort_high)
    "xhigh" -> stringResource(R.string.ai_effort_xhigh)
    else -> level
}

/**
 * Full-config JSON editor inside AI Settings: keys stay redacted by default with an explicit
 * reveal switch, plus Import (Merge / Replace), Export (Redacted / With keys), and Copy.
 */
@Composable
internal fun AiJsonConfigPane(
    config: AiConfig,
    jsonText: String,
    renderedJson: String,
    showKeys: Boolean,
    defaultInstructions: String,
    onJsonChange: (String) -> Unit,
    onShowKeysChange: (Boolean) -> Unit,
    onApplied: (AiConfig, String) -> Unit,
    onError: (String) -> Unit,
    onNotice: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val palette = LocalPalette.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var pendingImportJson by remember { mutableStateOf<String?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var exportWithKeys by remember { mutableStateOf(false) }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        readBoundedUtf8(stream) ?: error(context.getString(R.string.ai_config_file_too_large))
                    } ?: error(context.getString(R.string.ai_config_io_failed))
                }
            }
            result.onSuccess { text -> pendingImportJson = text }
                .onFailure { onError(it.message ?: context.getString(R.string.ai_config_io_failed)) }
        }
    }

    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val edited = jsonText != renderedJson
        val prepared = runCatching {
            val current = if (!edited) config else {
                AiConfigFile.parseAndApply(jsonText, config, defaultInstructions, AiConfigFile.ImportMode.REPLACE)
            }
            val out = AiConfigFile.exportJson(current, includeKeys = exportWithKeys, includeEffectiveThinking = true)
            current to out
        }.onFailure {
            onError(it.message ?: context.getString(R.string.ai_config_io_failed))
        }.getOrNull() ?: return@rememberLauncherForActivityResult
        val (current, out) = prepared
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(out.toByteArray(Charsets.UTF_8)) }
                        ?: error(context.getString(R.string.ai_config_io_failed))
                }
            }
            written.onSuccess {
                if (edited) {
                    onApplied(current, AiConfigFile.exportJson(current, includeKeys = showKeys, includeEffectiveThinking = true))
                }
                onNotice(context.getString(R.string.ai_config_exported))
            }.onFailure {
                onError(it.message ?: context.getString(R.string.ai_config_io_failed))
            }
        }
    }

    pendingImportJson?.let { raw ->
        fun applyImport(mode: AiConfigFile.ImportMode) {
            pendingImportJson = null
            runCatching {
                val merged = applyAiConfigImport(raw, jsonText, renderedJson, config, defaultInstructions, mode)
                val rendered = AiConfigFile.exportJson(merged, includeKeys = showKeys, includeEffectiveThinking = true)
                onApplied(merged, rendered)
                onNotice(context.getString(R.string.ai_config_imported))
            }.onFailure {
                onError(it.message ?: context.getString(R.string.ai_settings_invalid))
            }
        }
        AlertDialog(
            onDismissRequest = { pendingImportJson = null },
            title = { Text(stringResource(R.string.ai_config_import_title), fontSize = TextSize.heading) },
            text = { Text(stringResource(R.string.ai_config_import_note), fontSize = TextSize.body) },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                    TextButton(onClick = { applyImport(AiConfigFile.ImportMode.REPLACE) }) {
                        Text(stringResource(R.string.ai_config_import_replace))
                    }
                    TextButton(onClick = { applyImport(AiConfigFile.ImportMode.MERGE) }) {
                        Text(stringResource(R.string.ai_config_import_merge), fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportJson = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text(stringResource(R.string.ai_config_export_title), fontSize = TextSize.heading) },
            text = { Text(stringResource(R.string.ai_config_export_note), fontSize = TextSize.body) },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                    TextButton(onClick = {
                        showExportDialog = false
                        exportWithKeys = true
                        exportPicker.launch("xnotes-ai-config.json")
                    }) { Text(stringResource(R.string.ai_config_export_with_keys)) }
                    TextButton(onClick = {
                        showExportDialog = false
                        exportWithKeys = false
                        exportPicker.launch("xnotes-ai-config.json")
                    }) { Text(stringResource(R.string.ai_config_export_redacted), fontWeight = FontWeight.Bold) }
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s12)) {
        AiSwitchRow(
            title = stringResource(R.string.ai_config_show_keys),
            detail = stringResource(R.string.ai_config_json_note),
            checked = showKeys,
            onChange = onShowKeysChange,
        )
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.s8),
            verticalArrangement = Arrangement.spacedBy(Space.s8),
        ) {
            OutlinedButton(
                onClick = { importPicker.launch(arrayOf("application/json", "text/plain", "*/*")) },
                modifier = Modifier.heightIn(min = MinTouchTarget),
            ) {
                Icon(XnotesIcons.importDoc, null, Modifier.size(16.dp))
                Spacer(Modifier.width(Space.s6))
                Text(stringResource(R.string.ai_config_import), fontSize = TextSize.body)
            }
            OutlinedButton(
                onClick = { showExportDialog = true },
                modifier = Modifier.heightIn(min = MinTouchTarget),
            ) {
                Icon(XnotesIcons.exportDoc, null, Modifier.size(16.dp))
                Spacer(Modifier.width(Space.s6))
                Text(stringResource(R.string.ai_config_export), fontSize = TextSize.body)
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(ClipData.newPlainText("XNotes AI Config", jsonText)))
                        onNotice(context.getString(R.string.ai_config_copied))
                    }
                },
                modifier = Modifier.heightIn(min = MinTouchTarget),
            ) {
                Icon(XnotesIcons.copy, null, Modifier.size(16.dp))
                Spacer(Modifier.width(Space.s6))
                Text(stringResource(R.string.ai_config_copy), fontSize = TextSize.body)
            }
        }
        OutlinedTextField(
            value = jsonText,
            onValueChange = onJsonChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
            textStyle = TextStyle(
                color = palette.text.toComposeColor(),
                fontSize = TextSize.label,
                fontFamily = FontFamily.Monospace,
            ),
            minLines = 16,
        )
    }
}

/**
 * Visual GUI editor for a provider's or model's `thinkingLevels` map: shows each level and its
 * merged request JSON fields, with add / edit / remove / reset-to-defaults actions.
 */
@Composable
internal fun AiThinkingLevelsEditor(
    levels: Map<String, String>?,
    defaultLevels: Map<String, String>,
    onChange: (Map<String, String>?) -> Unit,
) {
    val palette = LocalPalette.current
    val effective = levels ?: defaultLevels
    var editing by remember { mutableStateOf<Pair<String?, String>?>(null) }

    editing?.let { (existingKey, initialPayload) ->
        AiThinkingLevelDialog(
            existingKey = existingKey,
            existingKeys = effective.keys,
            initialPayload = initialPayload,
            onDismiss = { editing = null },
            onSave = { name, payload ->
                val next = LinkedHashMap<String, String>()
                if (existingKey != null && existingKey != name) {
                    for ((k, v) in effective) {
                        if (k == existingKey) next[name] = payload else next[k] = v
                    }
                } else {
                    next.putAll(effective)
                    next[name] = payload
                }
                onChange(next)
                editing = null
            },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(Space.s8)) {
        Text(
            stringResource(R.string.ai_thinking_levels_title),
            color = palette.text.toComposeColor(),
            fontSize = TextSize.bodyLarge,
        )
        Text(
            stringResource(R.string.ai_thinking_levels_note),
            color = palette.textDim.toComposeColor(),
            fontSize = TextSize.label,
        )
        if (effective.isEmpty()) {
            Text(
                stringResource(R.string.ai_thinking_levels_empty),
                color = palette.textDim.toComposeColor(),
                fontSize = TextSize.body,
            )
        } else {
            val shape = MaterialTheme.shapes.small
            Column(
                Modifier.fillMaxWidth().clip(shape).border(1.dp, palette.border.toComposeColor(), shape),
            ) {
                effective.entries.forEachIndexed { index, (level, payload) ->
                    if (index > 0) HorizontalDivider(color = palette.border.toComposeColor())
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)
                            .clickable { editing = level to payload }
                            .padding(start = Space.s12, end = Space.s4, top = Space.s6, bottom = Space.s6),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.s8),
                    ) {
                        Column(Modifier.weight(1f)) {
                            val label = effortLabel(level)
                            Text(
                                if (label == level) level else "$label ($level)",
                                color = palette.text.toComposeColor(),
                                fontSize = TextSize.body,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                payload,
                                color = palette.textDim.toComposeColor(),
                                fontSize = TextSize.caption,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        AiIconButton(XnotesIcons.edit, stringResource(R.string.ai_thinking_level_edit)) {
                            editing = level to payload
                        }
                        AiIconButton(XnotesIcons.close, stringResource(R.string.ai_thinking_level_remove, level)) {
                            val next = LinkedHashMap(effective)
                            next.remove(level)
                            onChange(next)
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s8),
        ) {
            TextButton(
                enabled = effective.size < AiThinking.MAX_CUSTOM_LEVELS,
                onClick = { editing = null to """{"reasoning_effort": "high"}""" },
                contentPadding = PaddingValues(horizontal = Space.s4),
            ) {
                Icon(XnotesIcons.plus, null, Modifier.size(16.dp))
                Spacer(Modifier.width(Space.s6))
                Text(stringResource(R.string.ai_thinking_level_add))
            }
            if (levels != null) {
                TextButton(
                    onClick = { onChange(null) },
                    contentPadding = PaddingValues(horizontal = Space.s4),
                ) {
                    Icon(XnotesIcons.restore, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(Space.s6))
                    Text(stringResource(R.string.ai_thinking_level_reset))
                }
            }
        }
    }
}

@Composable
private fun AiThinkingLevelDialog(
    existingKey: String?,
    existingKeys: Set<String>,
    initialPayload: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    val context = LocalContext.current
    val palette = LocalPalette.current
    var name by remember { mutableStateOf(existingKey.orEmpty()) }
    var payload by remember { mutableStateOf(initialPayload) }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (existingKey == null) R.string.ai_thinking_level_add else R.string.ai_thinking_level_edit), fontSize = TextSize.heading) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s12)) {
                if (error.isNotEmpty()) Text(error, color = palette.text.toComposeColor(), fontSize = TextSize.label)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40); error = "" },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.ai_thinking_level_name)) },
                    placeholder = { Text(stringResource(R.string.ai_thinking_level_name_hint)) },
                )
                OutlinedTextField(
                    value = payload,
                    onValueChange = { payload = it; error = "" },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                    textStyle = TextStyle(color = palette.text.toComposeColor(), fontSize = TextSize.body, fontFamily = FontFamily.Monospace),
                    label = { Text(stringResource(R.string.ai_thinking_level_payload)) },
                    placeholder = { Text(stringResource(R.string.ai_thinking_level_payload_hint)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cleanName = name.trim()
                val dupError = context.getString(R.string.ai_thinking_level_duplicate, cleanName)
                validateThinkingLevelEntry(cleanName, payload, existingKeys, existingKey, dupError)
                    .onSuccess { (validatedName, normalizedPayload) -> onSave(validatedName, normalizedPayload) }
                    .onFailure { error = it.message ?: AiText.t(AiMsg.CONFIG_THINKING_INVALID) }
            }) { Text(stringResource(R.string.ai_ok), fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * Per-model visual override dialog (OpenCode-style): lets the user pick `tokenField` and override
 * the thinking levels / request JSON fields for one model without leaving the GUI.
 */
@Composable
internal fun AiModelOverrideDialog(
    modelName: String,
    initial: AiModelOverride?,
    providerLevels: Map<String, String>,
    onDismiss: () -> Unit,
    onSave: (AiModelOverride?) -> Unit,
) {
    val palette = LocalPalette.current
    var tokenField by remember { mutableStateOf(initial?.tokenField.orEmpty()) }
    var customThinking by remember { mutableStateOf(initial?.thinkingLevels != null) }
    var levels by remember { mutableStateOf(initial?.thinkingLevels ?: LinkedHashMap(providerLevels)) }
    val tokenOptions = listOf(
        "" to stringResource(R.string.ai_model_override_token_auto),
        "max_tokens" to "max_tokens",
        "max_completion_tokens" to "max_completion_tokens",
    )
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val shape = MaterialTheme.shapes.large
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth(0.9f).heightIn(max = 680.dp).clip(shape)
                .background(palette.menuBg.toComposeColor()).border(1.dp, palette.border.toComposeColor(), shape).imePadding(),
        ) {
            Row(
                Modifier.fillMaxWidth().height(MinTouchTarget + Space.s8).padding(horizontal = Space.s4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AiIconButton(XnotesIcons.close, stringResource(R.string.cancel), onClick = onDismiss)
                Text(
                    stringResource(R.string.ai_model_override_title, modelName),
                    Modifier.weight(1f).padding(start = Space.s4),
                    color = palette.text.toComposeColor(),
                    fontSize = TextSize.heading,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = {
                    val result = if (tokenField.isEmpty() && !customThinking) null
                    else AiModelOverride(tokenField = tokenField, thinkingLevels = if (customThinking) LinkedHashMap(levels) else null)
                    onSave(result)
                }) { Text(stringResource(R.string.ai_provider_done), fontWeight = FontWeight.Bold) }
            }
            HorizontalDivider(color = palette.border.toComposeColor())
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(Space.s16),
                verticalArrangement = Arrangement.spacedBy(Space.s16),
            ) {
                Text(stringResource(R.string.ai_model_override_token_field), color = palette.text.toComposeColor(), fontSize = TextSize.bodyLarge)
                AiSegmented(tokenOptions, tokenField, Modifier.fillMaxWidth()) { tokenField = it }
                HorizontalDivider(color = palette.border.toComposeColor())
                AiSwitchRow(
                    title = stringResource(R.string.ai_model_override_thinking_toggle),
                    detail = stringResource(R.string.ai_model_override_thinking_note),
                    checked = customThinking,
                    onChange = { on ->
                        customThinking = on
                        if (on && initial?.thinkingLevels == null && levels.isEmpty()) levels = LinkedHashMap(providerLevels)
                    },
                )
                if (customThinking) {
                    AiThinkingLevelsEditor(
                        levels = levels,
                        defaultLevels = providerLevels,
                        onChange = { next ->
                            if (next == null) {
                                customThinking = false
                                levels = LinkedHashMap(providerLevels)
                            } else {
                                levels = LinkedHashMap(next)
                            }
                        },
                    )
                }
                if (tokenField.isNotEmpty() || customThinking) {
                    TextButton(
                        onClick = { onSave(null) },
                        contentPadding = PaddingValues(horizontal = Space.s4),
                    ) {
                        Icon(XnotesIcons.restore, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(Space.s6))
                        Text(stringResource(R.string.ai_model_override_clear))
                    }
                }
            }
        }
    }
}
