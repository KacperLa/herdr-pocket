package dev.kaspy.herdr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class AgentFileEntry(
    val name: String,
    val isDirectory: Boolean,
    val canOpen: Boolean = true,
    val identity: SftpFileIdentity? = null,
)

internal data class AgentFilePreview(
    val name: String,
    val text: String,
    val truncated: Boolean = false,
)

internal data class AgentFileImagePreview(val name: String, val bitmap: Bitmap)
internal data class AgentFileDeletePrompt(val name: String, val folder: String, val directory: Boolean)
internal class AgentNewFolderPrompt(val folder: String)

internal data class AgentFilesUiState(
    val folder: String? = null,
    val entries: List<AgentFileEntry> = emptyList(),
    val preview: AgentFilePreview? = null,
    val image: AgentFileImagePreview? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val canGoBack: Boolean = false,
    val workspaceFallback: Boolean = false,
    val canRetry: Boolean = true,
    val downloading: Boolean = false,
    val downloadMessage: String? = null,
    val unrenderableName: String? = null,
    val uploading: Boolean = false,
    val uploadMessage: String? = null,
    val deletePrompt: AgentFileDeletePrompt? = null,
    val deleting: Boolean = false,
    val deleteMessage: String? = null,
    val newFolderPrompt: AgentNewFolderPrompt? = null,
    val creatingFolder: Boolean = false,
    val folderMessage: String? = null,
)

@Composable
internal fun AgentActionsMenu(buttonSize: Dp, onOpenMenu: () -> Unit, enabled: Boolean = true) {
    OverflowDotsButton(buttonSize, stringResource(R.string.agent_actions_menu), onOpenMenu, enabled)
}

@Composable
private fun OverflowDotsButton(buttonSize: Dp, label: String, onOpenMenu: () -> Unit, enabled: Boolean = true) {
    PocketIconControl(label, onOpenMenu, buttonSize, enabled) { ink ->
        Canvas(Modifier.size(PocketGlyphSize)) {
            for (position in listOf(0.23f, 0.50f, 0.77f)) {
                drawCircle(ink, 1.5.dp.toPx(), Offset(size.width / 2, size.height * position))
            }
        }
    }
}

@Composable
private fun CodexProgressPanel(anchor:Any,read:(suspend (Any)->CodexProgress.Snapshot),enabled:Boolean) {
    var expanded by remember(anchor) { mutableStateOf(false) }
    var snapshot by remember(anchor,expanded) { mutableStateOf<CodexProgress.Snapshot?>(null) }
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(anchor,expanded,enabled,lifecycle) {
        if(expanded && enabled) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                while(true) {
                    snapshot=read(anchor)
                    delay(5_000)
                }
            } finally { snapshot=null }
        }
    }
    Surface(Modifier.fillMaxWidth(),shape=PocketPanelShape,color=PocketBlack,border=BorderStroke(1.dp,PocketOutline)) {
        Column(Modifier.padding(horizontal=12.dp,vertical=4.dp)) {
            TextButton(onClick={expanded=!expanded},enabled=enabled || expanded,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                Text(stringResource(if(expanded) R.string.codex_progress_hide else R.string.codex_progress_show))
            }
            if(expanded) {
                val current=snapshot
                val status=when(current?.availability) {
                    null -> R.string.codex_progress_loading
                    CodexProgress.Availability.NO_SESSION -> R.string.codex_progress_no_session
                    CodexProgress.Availability.UNAVAILABLE -> R.string.codex_progress_unavailable
                    CodexProgress.Availability.AVAILABLE -> if(current.messages.isEmpty()) R.string.codex_progress_empty
                        else R.string.codex_progress_saved
                }
                Text(stringResource(status),style=MaterialTheme.typography.bodyMedium,
                    modifier=Modifier.padding(horizontal=4.dp,vertical=8.dp))
                current?.messages?.forEach { message ->
                    HorizontalDivider()
                    Text(message,style=MaterialTheme.typography.bodyLarge,
                        modifier=Modifier.padding(horizontal=4.dp,vertical=10.dp))
                }
            }
        }
    }
}

@Composable
internal fun AgentMenuScreen(
    agentName: String,
    details: CoreSession?,
    avatarScope: AgentAvatarScope?,
    canEditName: Boolean,
    refreshing: Boolean,
    notice: String?,
    onEditName: () -> Unit,
    onRefresh: () -> Unit,
    onOpenFiles: () -> Unit,
    onBack: () -> Unit,
    progressAnchor: Any? = null,
    readProgress: (suspend (Any) -> CodexProgress.Snapshot)? = null,
    typeOverride:String?=null,
    backLabel:String?=null,
) {
    val title = displayFileName(agentName).ifBlank { stringResource(R.string.agent_menu_fallback_title) }
    val scheme = MaterialTheme.colorScheme
    val agent = details?.takeIf { it.isAgent }
    val summary = details?.let(::agentCardSummary)
    val returnToTerminal = backLabel ?: stringResource(R.string.agent_menu_back)
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focus.clearFocus(force = true)
        keyboard?.hide()
        backFocus.requestFocus()
    }
    BackHandler(onBack = onBack)
    Surface(
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope { while (true) awaitPointerEvent() }
        }.semantics { paneTitle = title },
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                RoundBackArrow(returnToTerminal, onBack, 48.dp, Modifier.focusRequester(backFocus))
            }
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (agent != null) {
                    val reportedError=agent.status.equals("error",ignoreCase=true)
                    val status=if(reportedError) TuiAgents.Status.BLOCKED else TuiAgents.status(agent.status)
                    val statusLabel=stringResource(if(reportedError) R.string.roster_row_error else when(status) {
                        TuiAgents.Status.WORKING -> R.string.agent_status_working
                        TuiAgents.Status.BLOCKED -> R.string.agent_status_blocked
                        TuiAgents.Status.DONE -> R.string.agent_status_done
                        TuiAgents.Status.IDLE -> R.string.agent_status_idle
                        TuiAgents.Status.UNKNOWN -> R.string.agent_status_unknown
                    })
                    AgentFaceAvatar(avatarScope,agent.address,status,statusLabel,diameter=120.dp)
                } else if (details != null) {
                    TerminalAvatar(diameter=120.dp)
                } else if (refreshing) {
                    Box(Modifier.size(120.dp),contentAlignment=Alignment.Center) { CircularProgressIndicator() }
                }
                Text(title,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold,
                    textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth().semantics { heading() })
                if (canEditName) {
                    FilledTonalButton(onClick=onEditName,enabled=!refreshing,modifier=Modifier.heightIn(min=48.dp)) {
                        Text(stringResource(R.string.agent_settings_edit_name))
                    }
                }
                notice?.takeIf {it.isNotBlank()}?.let {
                    Text(it,style=MaterialTheme.typography.bodyMedium,textAlign=TextAlign.Center,
                        modifier=Modifier.fillMaxWidth().semantics { liveRegion=LiveRegionMode.Polite })
                }
                if (!refreshing && details==null && notice!=null) {
                    TextButton(onClick=onRefresh) {Text(stringResource(R.string.agent_files_retry))}
                }
                val empty=stringResource(R.string.agent_settings_not_reported)
                val type=typeOverride ?: if(agent!=null) agent.agent.takeIf {it.isNotBlank()} else
                    details?.let {stringResource(R.string.roster_row_terminal)}
                Surface(Modifier.fillMaxWidth(),shape=PocketPanelShape,color=PocketBlack,border=BorderStroke(1.dp,PocketOutline)) {
                    Column(Modifier.padding(horizontal=16.dp)) {
                        details?.rosterPlacement?.tabLabel?.let {
                            AgentSettingDetail(R.string.agent_settings_tab_label,it)
                            HorizontalDivider()
                        }
                        AgentSettingDetail(R.string.agent_settings_type,type ?: empty)
                        HorizontalDivider()
                        AgentSettingDetail(R.string.agent_settings_description,summary?.description ?: empty)
                        HorizontalDivider()
                        AgentSettingDetail(R.string.agent_settings_path,summary?.path ?: empty)
                    }
                }
                if (progressAnchor != null && readProgress != null) {
                    CodexProgressPanel(progressAnchor,readProgress,enabled=!refreshing)
                }
                val cardColor = scheme.onSurface
                Surface(
                    onClick = onOpenFiles,
                    enabled = !refreshing,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = cardColor,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Canvas(Modifier.size(26.dp)) {
                            val h = size.height
                            val w = size.width
                            val folder = Path().apply {
                                moveTo(w * .10f, h * .77f)
                                lineTo(w * .10f, h * .27f)
                                lineTo(w * .40f, h * .27f)
                                lineTo(w * .50f, h * .39f)
                                lineTo(w * .90f, h * .39f)
                                lineTo(w * .90f, h * .77f)
                                close()
                            }
                            drawPath(folder, cardColor, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
                        }
                        Text(
                            stringResource(R.string.agent_files_title),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentSettingDetail(label:Int,value:String) {
    Column(Modifier.fillMaxWidth().padding(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(stringResource(label),style=MaterialTheme.typography.labelLarge,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(displayFileName(value),style=MaterialTheme.typography.bodyLarge) }
    }
}

@Composable
internal fun AgentFilesExplorer(
    state: AgentFilesUiState,
    onOpenEntry: (AgentFileEntry) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onDownload: () -> Unit,
    onUpload: () -> Unit,
    onDeleteEntry: (AgentFileEntry) -> Unit,
    onConfirmDelete: (AgentFileDeletePrompt) -> Unit,
    onCancelDelete: (AgentFileDeletePrompt) -> Unit,
    onNewFolder: () -> Unit,
    onConfirmNewFolder: (AgentNewFolderPrompt, String) -> Unit,
    onCancelNewFolder: (AgentNewFolderPrompt) -> Unit,
    modifier: Modifier = Modifier,
) {
    val preview = state.preview
    val image = state.image
    val unrenderableName = state.unrenderableName
    val appContext = LocalContext.current.applicationContext
    val viewerPreferences = remember(appContext) {
        FileViewerPreferences(appContext.getSharedPreferences("workspace", Context.MODE_PRIVATE))
    }
    var wrap by remember(viewerPreferences) { mutableStateOf(viewerPreferences.wrap()) }
    var lineNumbers by remember(viewerPreferences) { mutableStateOf(viewerPreferences.lineNumbers()) }
    val folder = state.folder
    val title = when {
        preview != null -> displayFileName(preview.name)
        image != null -> displayFileName(image.name)
        unrenderableName != null -> displayFileName(unrenderableName)
        folder != null -> displayFileName(folder)
        else -> stringResource(R.string.agent_files_title)
    }
    val previewName = preview?.name ?: image?.name ?: unrenderableName
    val isDirectoryTitle = preview == null && image == null && unrenderableName == null && folder != null
    val canUseEntries = !state.loading && !state.uploading && !state.deleting && !state.creatingFolder &&
        state.deletePrompt == null && state.newFolderPrompt == null
    val showUp = isDirectoryTitle && state.canGoBack && canUseEntries && state.error == null
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val backFocus = remember { FocusRequester() }
    val backLabel = stringResource(R.string.agent_files_back)
    LaunchedEffect(Unit) {
        focus.clearFocus(force = true)
        keyboard?.hide()
        backFocus.requestFocus()
    }
    state.deletePrompt?.let { prompt ->
        val deleteLabel=if(prompt.directory) R.string.agent_files_remove_directory else R.string.agent_files_delete
        PocketDialog(
            onDismissRequest = { onCancelDelete(prompt) },
            title = { Text(stringResource(deleteLabel)) },
            text = {
                Column(
                    Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(stringResource(
                        if(prompt.directory) R.string.agent_files_delete_folder else R.string.agent_files_delete_name,
                        displayFileName(prompt.name),
                    ))
                    Text(stringResource(R.string.agent_files_delete_parent, displayFileName(prompt.folder)))
                    Text(stringResource(
                        if(prompt.directory) R.string.agent_files_remove_directory_warning else R.string.agent_files_delete_warning,
                    ))
                }
            },
            confirmButton = {
                TextButton(onClick = { onConfirmDelete(prompt) }) {
                    Text(stringResource(deleteLabel), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { onCancelDelete(prompt) }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
    state.newFolderPrompt?.let { prompt ->
        NewFilesFolderDialog(prompt, onConfirmNewFolder, onCancelNewFolder)
    }
    BackHandler(onBack = onBack)
    Surface(
        // A full-screen pointer target prevents blank areas from hitting the PTY beneath.
        modifier = modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope { while (true) awaitPointerEvent() }
        }.semantics { paneTitle = title },
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            val previewAction: (@Composable () -> Unit)? = when {
                previewName != null && !state.loading && state.error == null -> {
                    {
                        key(folder, previewName) {
                            PreviewOptionsMenu(
                                copyPath = AgentFiles.clipboardPath(folder, previewName),
                                downloading = state.downloading,
                                onDownload = onDownload,
                                textOptions = if (preview == null) null else ({
                                    PreviewToggle(stringResource(R.string.agent_files_wrap), wrap) {
                                        wrap = it
                                        viewerPreferences.setWrap(it)
                                    }
                                    PreviewToggle(stringResource(R.string.agent_files_line_numbers), lineNumbers) {
                                        lineNumbers = it
                                        viewerPreferences.setLineNumbers(it)
                                    }
                                    HorizontalDivider()
                                }),
                            )
                        }
                    }
                }
                isDirectoryTitle && state.error == null -> {
                    { key(folder) {
                        FilesFolderOptions(
                            enabled = canUseEntries, copyPath = AgentFiles.clipboardPath(folder),
                            onUpload = onUpload, onNewFolder = onNewFolder,
                        )
                    } }
                }
                else -> null
            }
            AgentPageHeader(
                title,
                backLabel,
                onBack,
                backModifier = Modifier.focusRequester(backFocus),
                isPath = isDirectoryTitle,
                onUp = onBack.takeIf { showUp },
                endAction = previewAction,
            )
            HorizontalDivider()
            if (isDirectoryTitle) {
                val message = when {
                    state.creatingFolder -> stringResource(R.string.agent_files_creating_folder)
                    state.deleting -> stringResource(R.string.agent_files_deleting)
                    state.uploading -> stringResource(R.string.agent_files_uploading)
                    else -> state.folderMessage ?: state.deleteMessage ?: state.uploadMessage
                }
                if (message != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (state.uploading || state.deleting || state.creatingFolder) CircularProgressIndicator(Modifier.size(22.dp))
                        Text(message, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                        if (state.uploading) TextButton(onClick = onBack) {
                            Text(stringResource(R.string.agent_files_cancel_upload))
                        }
                    }
                    HorizontalDivider()
                }
            }
            when {
                state.loading -> AgentFilesStatus(
                    message = stringResource(R.string.agent_files_loading),
                    busy = true,
                    modifier = Modifier.weight(1f),
                )
                state.error != null -> AgentFilesStatus(
                    message = state.error,
                    onRetry = onRetry.takeIf { state.canRetry },
                    modifier = Modifier.weight(1f),
                )
                preview != null -> key(state.folder, preview.name) {
                    TextFilePreview(
                        preview = preview,
                        wrap = wrap,
                        lineNumbers = lineNumbers,
                        downloading = state.downloading,
                        downloadMessage = state.downloadMessage,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
                image != null -> key(image) {
                    AgentImageViewer(image, state.downloading, state.downloadMessage, Modifier.weight(1f).fillMaxWidth())
                }
                unrenderableName != null -> AgentFilesStatus(
                    message = stringResource(R.string.agent_files_cannot_preview),
                    busy = state.downloading,
                    detail = state.downloadMessage
                        ?: if (state.downloading) stringResource(R.string.agent_files_downloading) else null,
                    modifier = Modifier.weight(1f),
                )
                else -> key(folder) {
                    Column(Modifier.weight(1f).fillMaxWidth()) {
                        if (state.workspaceFallback) {
                            Text(
                                stringResource(R.string.agent_files_workspace_fallback),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            HorizontalDivider()
                        }
                        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                            if (state.entries.isEmpty()) item {
                                Text(
                                    stringResource(R.string.agent_files_empty_folder),
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                )
                            }
                            items(state.entries, key = { it.name }) { entry ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        onClick = { onOpenEntry(entry) },
                                        enabled = entry.canOpen && canUseEntries,
                                        modifier = Modifier.weight(1f).heightIn(min = 60.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                    ) {
                                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                            Text(
                                                displayFileName(entry.name),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 19.sp,
                                            )
                                            Text(
                                                stringResource(when {
                                                    !entry.canOpen -> R.string.agent_files_unavailable_entry
                                                    entry.isDirectory -> R.string.agent_files_folder
                                                    else -> R.string.agent_files_file
                                                }),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    if (entry.canOpen) {
                                        Box(Modifier.padding(end = 8.dp)) {
                                            FilesEntryOptions(entry, canUseEntries, AgentFiles.clipboardPath(folder, entry.name)) {
                                                onDeleteEntry(entry)
                                            }
                                        }
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilesFolderOptions(enabled: Boolean, copyPath: String?, onUpload: () -> Unit, onNewFolder: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OverflowDotsButton(48.dp, stringResource(R.string.agent_files_folder_options), { open = true }, enabled)
        PocketMenu(expanded = open && enabled, onDismissRequest = { open = false }) {
            copyPath?.let { CopyPathMenuItem(it) { open = false } }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.agent_files_upload_menu)) },
                onClick = { open = false; onUpload() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.agent_files_new_folder)) },
                onClick = { open = false; onNewFolder() },
            )
        }
    }
}

@Composable
private fun NewFilesFolderDialog(
    prompt: AgentNewFolderPrompt,
    onConfirm: (AgentNewFolderPrompt, String) -> Unit,
    onCancel: (AgentNewFolderPrompt) -> Unit,
) {
    var name by remember(prompt) { mutableStateOf("") }
    val valid = AgentFiles.newFolderName(name)
    PocketDialog(
        onDismissRequest = { onCancel(prompt) },
        title = { Text(stringResource(R.string.agent_files_new_folder)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.agent_files_delete_folder, displayFileName(prompt.folder)))
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 1024) name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.agent_files_folder_name)) },
                    isError = name.isNotEmpty() && !valid,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(prompt, name) }) {
                Text(stringResource(R.string.agent_files_create))
            }
        },
        dismissButton = {
            TextButton(onClick = { onCancel(prompt) }) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun FilesEntryOptions(entry: AgentFileEntry, enabled: Boolean, copyPath: String?, onDelete: () -> Unit) {
    var open by remember(entry) { mutableStateOf(false) }
    Box {
        if (enabled) {
            OverflowDotsButton(48.dp, stringResource(R.string.agent_files_options_for, displayFileName(entry.name)), {
                open = true
            })
        } else Spacer(Modifier.size(48.dp))
        PocketMenu(expanded = open && enabled, onDismissRequest = { open = false }) {
            copyPath?.let { CopyPathMenuItem(it) { open = false } }
            DropdownMenuItem(
                text = { Text(stringResource(
                    if(entry.isDirectory) R.string.agent_files_remove_directory else R.string.agent_files_delete,
                )) },
                onClick = { open = false; onDelete() },
            )
        }
    }
}

@Composable
private fun PreviewOptionsMenu(
    copyPath: String?,
    downloading: Boolean,
    onDownload: () -> Unit,
    textOptions: (@Composable () -> Unit)?,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OverflowDotsButton(48.dp, stringResource(R.string.agent_files_preview_options), { expanded = true })
        PocketMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            textOptions?.invoke()
            copyPath?.let { CopyPathMenuItem(it) { expanded = false } }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.agent_files_download)) },
                onClick = { expanded = false; onDownload() },
                enabled = !downloading,
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

@Composable
private fun CopyPathMenuItem(path: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val label = stringResource(R.string.agent_files_path_label)
    DropdownMenuItem(
        text = { Text(stringResource(R.string.agent_files_copy_path)) },
        onClick = {
            onDismiss()
            val copied = runCatching {
                val clipboard = checkNotNull(context.getSystemService(ClipboardManager::class.java))
                clipboard.setPrimaryClip(ClipData.newPlainText(label, path))
            }.isSuccess
            if (!copied) Toast.makeText(context, R.string.agent_files_copy_path_failure, Toast.LENGTH_SHORT).show()
        },
    )
}

@Composable
private fun PreviewToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = { Switch(checked = checked, onCheckedChange = null, colors = pocketSwitchColors()) },
        onClick = { onCheckedChange(!checked) },
        modifier = Modifier.heightIn(min = 48.dp).semantics {
            role = Role.Switch
            toggleableState = ToggleableState(checked)
        },
    )
}

internal fun displayPreviewText(source: String): String =
    source.replace("\r\n", "\n").replace('\r', '\n')

/** Source starts, in UTF-16 offsets (matching Compose TextLayoutResult), including an empty last line. */
internal fun previewSourceLineStarts(displayText: String): IntArray {
    if (displayText.isEmpty()) return IntArray(0)
    val positions = IntArray(1 + displayText.count { it == '\n' })
    var line = 1
    displayText.forEachIndexed { offset, ch -> if (ch == '\n') positions[line++] = offset + 1 }
    return positions
}

@Composable
private fun TextFilePreview(
    preview: AgentFilePreview,
    wrap: Boolean,
    lineNumbers: Boolean,
    downloading: Boolean,
    downloadMessage: String?,
    modifier: Modifier = Modifier,
) {
    val text = remember(preview.text) { displayPreviewText(preview.text) }
    val darkSurface = MaterialTheme.colorScheme.surface.luminance() < .18f
    val plainText = remember(text) { AnnotatedString(text) }
    // A new file/text starts plain immediately; a departing preview cannot publish stale spans.
    var coloredText by remember(preview.name, text, darkSurface) {
        mutableStateOf<AnnotatedString?>(null)
    }
    LaunchedEffect(preview.name, text, darkSurface) {
        if (text.isEmpty()) return@LaunchedEffect
        coloredText = withContext(Dispatchers.Default) {
            val spans = highlightFileSyntax(preview.name, text) { ensureActive() }
            if (spans.isEmpty()) null else {
                val styles = SyntaxKind.entries.associateWith { SpanStyle(color = syntaxColor(it, darkSurface)) }
                buildAnnotatedString {
                    append(text)
                    for ((index, span) in spans.withIndex()) {
                        if (index and 255 == 0) ensureActive()
                        if (span.start >= 0 && span.end > span.start && span.end <= text.length) {
                            styles[span.kind]?.let { addStyle(it, span.start, span.end) }
                        }
                    }
                }
            }
        }
    }
    val sourceStarts = remember(text, lineNumbers) {
        if (lineNumbers) previewSourceLineStarts(text) else IntArray(0)
    }
    val plainStyle = MaterialTheme.typography.bodyLarge.copy(
        fontFamily = FontFamily.Monospace,
        lineBreak = LineBreak.Simple,
    )
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    var viewportHeight by remember { mutableIntStateOf(0) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    val gutterColor = MaterialTheme.colorScheme.onSurfaceVariant
    val fontPixels = with(density) { plainStyle.fontSize.toPx() }
    val numberPaint = remember(fontPixels, gutterColor) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = gutterColor.toArgb()
            typeface = Typeface.MONOSPACE
            textSize = fontPixels
            textAlign = Paint.Align.RIGHT
        }
    }

    Column(modifier.padding(16.dp)) {
        if (preview.truncated) {
            Text(
                stringResource(R.string.agent_files_truncated),
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        val message = downloadMessage ?: if (downloading) stringResource(R.string.agent_files_downloading) else null
        if (message != null) {
            Text(message, modifier = Modifier.padding(bottom = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
        }
        SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
            val gutterWidth = if (sourceStarts.isEmpty()) 0.dp else {
                val lineDigits = sourceStarts.size.toString().length
                with(density) { numberPaint.measureText("9".repeat(lineDigits)).toDp() } + 16.dp
            }
            Box(
                Modifier.fillMaxWidth().onSizeChanged { viewportHeight = it.height }
                    .verticalScroll(vertical).drawBehind {
                        val rendered = layout ?: return@drawBehind
                        if (sourceStarts.isEmpty()) return@drawBehind
                        val top = vertical.value.toFloat()
                        val bottom = top + viewportHeight
                        val numberRight = (gutterWidth - 10.dp).toPx()
                        var visualLine = rendered.getLineForVerticalPosition(top)
                        while (visualLine < rendered.lineCount && rendered.getLineTop(visualLine) < bottom) {
                            val index = sourceStarts.binarySearch(rendered.getLineStart(visualLine))
                            if (index >= 0) {
                                drawContext.canvas.nativeCanvas.drawText(
                                    (index + 1).toString(),
                                    numberRight,
                                    rendered.getLineBaseline(visualLine),
                                    numberPaint,
                                )
                            }
                            visualLine++
                        }
                    },
            ) {
                Box(Modifier.fillMaxWidth().absolutePadding(left = gutterWidth)) {
                    Text(
                        if (text.isEmpty()) AnnotatedString(stringResource(R.string.agent_files_empty_file))
                        else coloredText ?: plainText,
                        softWrap = wrap,
                        style = plainStyle,
                        onTextLayout = { layout = it },
                        modifier = if (wrap) Modifier.fillMaxWidth() else Modifier.horizontalScroll(horizontal),
                    )
                }
            }
        }
    }
}

// Bright pastels on the current dark Files surface, with dark equivalents for a light theme.
private fun syntaxColor(kind: SyntaxKind, darkSurface: Boolean): Color = when (kind) {
    SyntaxKind.COMMENT, SyntaxKind.LOG_DEBUG -> if (darkSurface) Color(0xFFB0BBC5) else Color(0xFF424B56)
    SyntaxKind.STRING -> if (darkSurface) Color(0xFFC5E88C) else Color(0xFF355209)
    SyntaxKind.NUMBER -> if (darkSurface) Color(0xFFFFCB90) else Color(0xFF744000)
    SyntaxKind.KEYWORD -> if (darkSurface) Color(0xFFE1BCFF) else Color(0xFF5A2683)
    SyntaxKind.TYPE -> if (darkSurface) Color(0xFF8EDFFA) else Color(0xFF00566C)
    SyntaxKind.PROPERTY -> if (darkSurface) Color(0xFFAEC9FF) else Color(0xFF214C89)
    SyntaxKind.LOG_ERROR -> if (darkSurface) Color(0xFFFFAAB6) else Color(0xFF8D1233)
    SyntaxKind.LOG_WARN -> if (darkSurface) Color(0xFFFFDD8A) else Color(0xFF6F4800)
    SyntaxKind.LOG_INFO -> if (darkSurface) Color(0xFF8DE9D6) else Color(0xFF005750)
}

@Composable
private fun AgentPageHeader(
    title: String,
    backLabel: String,
    onBack: () -> Unit,
    backModifier: Modifier = Modifier,
    isPath: Boolean = false,
    onUp: (() -> Unit)? = null,
    endAction: (@Composable () -> Unit)? = null,
) {
    val backSize = 48.dp
    val titleStyle = MaterialTheme.typography.headlineSmall
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundBackArrow(backLabel, onBack, backSize, backModifier)
        Text(
            title,
            style = if (isPath) titleStyle.copy(textDirection = TextDirection.Ltr) else titleStyle,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = if (isPath) 1 else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        // The full title remains in Text semantics; only the visible glyphs are ellipsized.
        // Reserve the same width opposite Back when this page has no Up action.
        when {
            endAction != null -> endAction()
            onUp != null -> AgentFilesUpButton(onUp)
            else -> Spacer(Modifier.size(backSize))
        }
    }
}

@Composable
private fun AgentFilesUpButton(onBack: () -> Unit) {
    val label = stringResource(R.string.agent_files_parent)
    PocketIconControl(label, onBack) { ink ->
        Canvas(Modifier.size(PocketGlyphSize)) {
            val top = Offset(size.width * .50f, size.height * .20f)
            val stroke = PocketGlyphStroke.toPx()
            drawLine(ink, top, Offset(size.width * .50f, size.height * .80f), stroke, StrokeCap.Round)
            drawLine(ink, top, Offset(size.width * .24f, size.height * .46f), stroke, StrokeCap.Round)
            drawLine(ink, top, Offset(size.width * .76f, size.height * .46f), stroke, StrokeCap.Round)
        }
    }
}

@Composable
private fun AgentFilesStatus(
    message: String,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    onRetry: (() -> Unit)? = null,
    detail: String? = null,
) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(32.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (detail != null) {
            Text(
                detail,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (onRetry != null) {
            OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.agent_files_retry), fontSize = 17.sp)
            }
        }
    }
}

// Only for display: never trim, rewrite, or reparse an entry name before sending it to SFTP.
private fun displayFileName(name: String): String = buildString(name.length) {
    for (ch in name) append(
        if (ch.isISOControl() || ch in '\u202a'..'\u202e' || ch in '\u2066'..'\u2069') '\ufffd' else ch
    )
}
