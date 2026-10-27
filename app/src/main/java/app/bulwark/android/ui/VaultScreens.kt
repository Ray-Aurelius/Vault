package app.bulwark.android.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.bulwark.android.BulwarkApplication
import app.bulwark.android.R
import app.bulwark.android.SelfTest
import app.bulwark.android.security.DeviceLayer
import app.bulwark.android.session.Importer
import app.bulwark.android.session.VaultSession
import app.bulwark.crypto.FileId
import app.bulwark.vault.UnlockedVault
import app.bulwark.vault.VaultEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private sealed interface Screen {
    data object Browser : Screen
    data class Viewer(val id: FileId) : Screen
    data object Trash : Screen
    data object Settings : Screen
    data object Security : Screen
    data object ChangePassword : Screen
    data object RecoverySetup : Screen
    data class Legal(@RawRes val res: Int, val title: Int) : Screen
    data object Licences : Screen
}

@Composable
fun VaultHome(session: VaultSession, vault: UnlockedVault) {
    var screen by remember { mutableStateOf<Screen>(Screen.Browser) }
    var folderHex by rememberSaveable { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(0) }
    val refresh: () -> Unit = { version++ }
    val folder = folderHex?.let { FileId.fromHex(it) }?.takeIf { vault.get(it)?.let { e -> e.isFolder && !e.isTrashed } == true }
    val toBrowser: () -> Unit = { screen = Screen.Browser; refresh() }

    BackHandler(enabled = screen != Screen.Browser || folder != null) {
        when {
            screen == Screen.ChangePassword || screen == Screen.RecoverySetup -> screen = Screen.Security
            screen is Screen.Legal || screen == Screen.Licences || screen == Screen.Security -> screen = Screen.Settings
            screen != Screen.Browser -> toBrowser()
            else -> folderHex = vault.get(folder!!)?.parent?.hex
        }
    }

    when (val s = screen) {
        Screen.Browser -> BrowserScreen(
            session, vault, folder, version, refresh,
            openFolder = { folderHex = it?.hex }, openFile = { screen = Screen.Viewer(it) },
            openTrash = { screen = Screen.Trash }, openSettings = { screen = Screen.Settings },
            setUpRecovery = { screen = Screen.RecoverySetup },
        )
        is Screen.Viewer -> ViewerScreen(session, vault, s.id, onBack = toBrowser)
        Screen.Trash -> TrashScreen(vault, version, refresh, onBack = toBrowser)
        Screen.Settings -> SettingsScreen(session, vault, onBack = toBrowser, open = { screen = it })
        Screen.Security -> SecuritySettingsScreen(
            session, vault, onBack = { screen = Screen.Settings },
            openChangePassword = { screen = Screen.ChangePassword }, openRecoverySetup = { screen = Screen.RecoverySetup },
        )
        Screen.ChangePassword -> ChangePasswordFlow(session, onDone = { screen = Screen.Security })
        Screen.RecoverySetup -> RecoverySetupFlow(session, vault, onDone = { screen = Screen.Security })
        is Screen.Legal -> Page(title = stringResource(s.title), onBack = { screen = Screen.Settings }) { LegalText(s.res) }
        Screen.Licences -> Page(title = stringResource(R.string.licences), onBack = { screen = Screen.Settings }) { LegalText(R.raw.licences) }
    }
    ImportOverlay(session.importer, onFinished = refresh)
    FailedAttemptsNotice(session)
}

@Composable
private fun FailedAttemptsNotice(session: VaultSession) {
    val count by session.failedNotice.collectAsState()
    if (count > 0) AlertDialog(
        onDismissRequest = session::dismissFailedNotice,
        title = { Text(pluralStringResource(R.plurals.failed_notice_title, count, count)) },
        text = { Text(stringResource(R.string.failed_notice_body)) },
        confirmButton = { TextButton(onClick = session::dismissFailedNotice) { Text(stringResource(R.string.ok)) } },
    )
}

// ---------------------------------------------------------------- Browser

@Composable
private fun BrowserScreen(
    session: VaultSession,
    vault: UnlockedVault,
    folder: FileId?,
    version: Int,
    refresh: () -> Unit,
    openFolder: (FileId?) -> Unit,
    openFile: (FileId) -> Unit,
    openTrash: () -> Unit,
    openSettings: () -> Unit,
    setUpRecovery: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items = remember(folder, version) { vault.children(folder) }
    val current = folder?.let { vault.get(it) }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var plan by remember { mutableStateOf<Importer.Plan?>(null) }
    var planning by remember { mutableStateOf(false) }
    var showRecoveryBanner by remember { mutableStateOf(!vault.hasRecoveryKey && !session.prefs.noRecoveryAcknowledged) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        session.endExternalActivity()
        if (uris.isNotEmpty()) {
            planning = true
            scope.launch {
                plan = withContext(Dispatchers.IO) { session.importer.plan(uris) }
                planning = false
            }
        }
    }
    val exporter = rememberExporter(session, vault)

    Page(
        title = current?.name ?: stringResource(R.string.app_name),
        onBack = if (folder != null) ({ openFolder(current?.parent) }) else null,
        scroll = false,
        actions = {
            IconButton(onClick = openTrash) { Icon(painterResource(R.drawable.ic_trash), stringResource(R.string.trash)) }
            IconButton(onClick = openSettings) { Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings)) }
            IconButton(onClick = { session.lock() }) { Icon(painterResource(R.drawable.ic_lock), stringResource(R.string.lock_now)) }
        },
        bottomBar = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(stringResource(R.string.new_folder), { dialog = Dialog.NewFolder }, modifier = Modifier.weight(1f))
                PrimaryButton(stringResource(R.string.import_files), {
                    session.beginExternalActivity(); picker.launch(arrayOf("*/*"))
                }, modifier = Modifier.weight(1f))
            }
        },
    ) {
        if (showRecoveryBanner) {
            InfoCard(stringResource(R.string.no_recovery_banner_title), stringResource(R.string.no_recovery_banner_body), R.drawable.ic_warning, warning = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = setUpRecovery) { Text(stringResource(R.string.set_up_recovery)) }
                TextButton(onClick = { session.prefs.noRecoveryAcknowledged = true; showRecoveryBanner = false }) { Text(stringResource(R.string.i_understand)) }
            }
        }
        if (planning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
        if (items.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(painterResource(R.drawable.ic_folder), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Body(stringResource(if (folder == null) R.string.empty_vault else R.string.empty_folder))
                Quiet(stringResource(R.string.empty_hint))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(items, key = { it.id.hex }) { e ->
                    EntryRow(
                        e, onOpen = { if (e.isFolder) openFolder(e.id) else openFile(e.id) },
                        onRename = { dialog = Dialog.Rename(e) }, onMove = { dialog = Dialog.Move(e) },
                        onExport = if (e.isFolder) null else ({ dialog = Dialog.ExportWarning(e) }),
                        onTrash = { vault.moveToTrash(e.id); refresh() },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    plan?.let { p -> ImportPlanDialog(p, onStart = { session.importer.start(vault, p, folder); plan = null }, onCancel = { plan = null }) }

    when (val d = dialog) {
        Dialog.NewFolder -> NameDialog(stringResource(R.string.new_folder), "", onDismiss = { dialog = null }) { vault.createFolder(it, folder); dialog = null; refresh() }
        is Dialog.Rename -> NameDialog(stringResource(R.string.rename), d.entry.name, onDismiss = { dialog = null }) { vault.rename(d.entry.id, it); dialog = null; refresh() }
        is Dialog.Move -> MoveDialog(vault, d.entry, onDismiss = { dialog = null }) { target -> vault.move(d.entry.id, target); dialog = null; refresh() }
        is Dialog.ExportWarning -> ConfirmDialog(
            title = stringResource(R.string.export_title), body = stringResource(R.string.export_body), confirm = stringResource(R.string.export),
            onDismiss = { dialog = null },
        ) { dialog = null; exporter(d.entry) }
        null -> Unit
    }
}

private sealed interface Dialog {
    data object NewFolder : Dialog
    data class Rename(val entry: VaultEntry) : Dialog
    data class Move(val entry: VaultEntry) : Dialog
    data class ExportWarning(val entry: VaultEntry) : Dialog
}

@Composable
private fun EntryRow(
    e: VaultEntry,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onExport: (() -> Unit)?,
    onTrash: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onOpen).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(iconFor(e)), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 16.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Quiet(if (e.isFolder) stringResource(R.string.folder) else "${Formatter.formatShortFileSize(context, e.size)} · ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(e.createdAt))}")
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_more), stringResource(R.string.more_options, e.name)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text(stringResource(R.string.move)) }, onClick = { menu = false; onMove() })
                if (onExport != null) DropdownMenuItem(text = { Text(stringResource(R.string.export)) }, onClick = { menu = false; onExport() })
                DropdownMenuItem(text = { Text(stringResource(R.string.move_to_trash)) }, onClick = { menu = false; onTrash() })
            }
        }
    }
}

@DrawableRes
private fun iconFor(e: VaultEntry): Int = when {
    e.isFolder -> R.drawable.ic_folder
    e.mimeType.startsWith("image/") -> R.drawable.ic_image
    e.mimeType.startsWith("video/") -> R.drawable.ic_video
    e.mimeType.startsWith("text/") -> R.drawable.ic_note
    else -> R.drawable.ic_file
}

// ---------------------------------------------------------------- Import

@Composable
private fun ImportPlanDialog(plan: Importer.Plan, onStart: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val size = { b: Long -> Formatter.formatShortFileSize(context, b) }
    when {
        plan.items.isEmpty() -> LaunchedEffect(Unit) { onCancel() }
        !plan.enoughSpace -> AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(stringResource(R.string.no_space_title)) },
            text = { Text(stringResource(R.string.no_space_body, size(plan.neededBytes), size(plan.freeBytes))) },
            confirmButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.ok)) } },
        )
        plan.batteryTooLow -> AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(stringResource(R.string.battery_low_title)) },
            text = { Text(stringResource(R.string.battery_low_body, plan.batteryPercent)) },
            confirmButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.ok)) } },
        )
        plan.isLarge -> AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(stringResource(R.string.large_import_title, size(plan.totalBytes), (plan.estimatedSeconds / 60).coerceAtLeast(1))) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.large_import_body))
                    if (plan.suggestPlugIn) Text(stringResource(R.string.large_import_plug_in, plan.batteryPercent))
                    Text(stringResource(R.string.not_responsible), style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { TextButton(onClick = onStart) { Text(stringResource(if (plan.suggestPlugIn) R.string.start_anyway else R.string.start)) } },
            dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
        )
        else -> LaunchedEffect(plan) { onStart() }
    }
}

@Composable
private fun ImportOverlay(importer: Importer, onFinished: () -> Unit) {
    val state by importer.state.collectAsState()
    val context = LocalContext.current
    when (val s = state) {
        is Importer.State.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.importing_title, s.fileIndex, s.fileCount)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s.currentName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LinearProgressIndicator(
                        progress = { if (s.bytesTotal > 0) (s.bytesDone.toFloat() / s.bytesTotal).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Quiet("${Formatter.formatShortFileSize(context, s.bytesDone)} / ${Formatter.formatShortFileSize(context, s.bytesTotal)}")
                    Quiet(stringResource(R.string.importing_keep_open))
                }
            },
            confirmButton = { TextButton(onClick = { importer.cancel() }) { Text(stringResource(R.string.cancel)) } },
        )
        is Importer.State.Finished -> AlertDialog(
            onDismissRequest = { importer.dismiss(); onFinished() },
            title = { Text(stringResource(if (s.cancelled) R.string.import_stopped else R.string.import_done)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pluralStringResource(R.plurals.imported_count, s.imported, s.imported))
                    if (s.failed > 0) Text(pluralStringResource(R.plurals.not_imported_count, s.failed, s.failed))
                    Quiet(stringResource(R.string.import_originals_note))
                }
            },
            confirmButton = { TextButton(onClick = { importer.dismiss(); onFinished() }) { Text(stringResource(R.string.ok)) } },
        )
        null -> Unit
    }
}

// ---------------------------------------------------------------- Export

/** "Save as" with the file's own type, so the saved copy opens correctly elsewhere. */
private class CreateTypedDocument : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>) =
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(input.second).putExtra(Intent.EXTRA_TITLE, input.first)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
}

@Composable
private fun rememberExporter(session: VaultSession, vault: UnlockedVault): (VaultEntry) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<VaultEntry?>(null) }
    var result by remember { mutableStateOf<Boolean?>(null) }
    var needsPassword by remember { mutableStateOf<VaultEntry?>(null) }
    val launcher = rememberLauncherForActivityResult(CreateTypedDocument()) { uri ->
        session.endExternalActivity()
        val entry = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            result = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { vault.readFile(entry.id, it) }
                    true
                } catch (e: Exception) {
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) } // never leave a partial copy
                    false
                }
            }
        }
    }
    result?.let { ok ->
        AlertDialog(
            onDismissRequest = { result = null },
            title = { Text(stringResource(if (ok) R.string.export_done else R.string.export_failed)) },
            confirmButton = { TextButton(onClick = { result = null }) { Text(stringResource(R.string.ok)) } },
        )
    }
    val launch = { entry: VaultEntry ->
        pending = entry
        session.beginExternalActivity()
        launcher.launch(entry.name to entry.mimeType.ifBlank { "application/octet-stream" })
    }
    needsPassword?.let { entry ->
        PasswordConfirmDialog(session, stringResource(R.string.confirm_export), onDismiss = { needsPassword = null }) {
            needsPassword = null; launch(entry)
        }
    }
    return { entry -> if (session.prefs.confirmPasswordForExport) needsPassword = entry else launch(entry) }
}

// ---------------------------------------------------------------- Viewer

private sealed interface Preview {
    data object Loading : Preview
    class Picture(val bitmap: Bitmap) : Preview
    class Plain(val text: String) : Preview
    data object Unsupported : Preview
    data object Damaged : Preview
    data object TooLarge : Preview
}

@Composable
private fun ViewerScreen(session: VaultSession, vault: UnlockedVault, id: FileId, onBack: () -> Unit) {
    val entry = remember(id) { vault.get(id) } ?: run { LaunchedEffect(Unit) { onBack() }; return }
    var preview by remember(id) { mutableStateOf<Preview>(Preview.Loading) }
    var confirmExport by remember { mutableStateOf(false) }
    val exporter = rememberExporter(session, vault)

    LaunchedEffect(id) {
        preview = withContext(Dispatchers.IO) {
            try {
                when {
                    entry.mimeType.startsWith("image/") ->
                        if (entry.size > MAX_IMAGE_BYTES) Preview.TooLarge
                        else decodeImage(vault.readBytes(id, MAX_IMAGE_BYTES))?.let { Preview.Picture(it) } ?: Preview.Unsupported
                    entry.mimeType.startsWith("text/") || entry.name.endsWith(".md") || entry.name.endsWith(".txt") ->
                        if (entry.size > MAX_TEXT_BYTES) Preview.TooLarge
                        else vault.readBytes(id, MAX_TEXT_BYTES).let { b -> Preview.Plain(String(b, Charsets.UTF_8)).also { b.fill(0) } }
                    else -> Preview.Unsupported
                }
            } catch (e: app.bulwark.vault.VaultLockedException) {
                Preview.Loading
            } catch (e: Exception) {
                Preview.Damaged
            }
        }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopBar(entry.name, onBack) {
                IconButton(onClick = { confirmExport = true }) { Icon(painterResource(R.drawable.ic_export), stringResource(R.string.export)) }
                IconButton(onClick = { vault.moveToTrash(id); onBack() }) { Icon(painterResource(R.drawable.ic_trash), stringResource(R.string.move_to_trash)) }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (val p = preview) {
                    Preview.Loading -> CircularProgressIndicator()
                    is Preview.Picture -> Image(p.bitmap.asImageBitmap(), entry.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    is Preview.Plain -> Text(
                        p.text, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SourceCodePro), color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    )
                    Preview.Unsupported, Preview.TooLarge, Preview.Damaged -> Column(Modifier.widthIn(max = 480.dp).padding(24.dp)) {
                        val (title, body) = when (p) {
                            Preview.Damaged -> R.string.preview_damaged_title to R.string.preview_damaged_body
                            Preview.TooLarge -> R.string.preview_large_title to R.string.preview_unsupported_body
                            else -> R.string.preview_unsupported_title to R.string.preview_unsupported_body
                        }
                        InfoCard(stringResource(title), stringResource(body), R.drawable.ic_file, warning = p == Preview.Damaged)
                        if (p != Preview.Damaged) SecondaryButton(stringResource(R.string.export), { confirmExport = true })
                    }
                }
            }
        }
    }
    if (confirmExport) ConfirmDialog(
        title = stringResource(R.string.export_title), body = stringResource(R.string.export_body), confirm = stringResource(R.string.export),
        onDismiss = { confirmExport = false },
    ) { confirmExport = false; exporter(entry) }
}

private const val MAX_IMAGE_BYTES = 64L * 1024 * 1024
private const val MAX_TEXT_BYTES = 2L * 1024 * 1024

/** Decodes in memory only, scaled down to at most ~2560 px so huge photos don't exhaust memory. */
private fun decodeImage(bytes: ByteArray): Bitmap? {
    try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 2560 || bounds.outHeight / sample > 2560) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    } finally {
        bytes.fill(0)
    }
}

// ---------------------------------------------------------------- Trash

@Composable
private fun TrashScreen(vault: UnlockedVault, version: Int, refresh: () -> Unit, onBack: () -> Unit) {
    val items = remember(version) { vault.trash() }
    var confirmEmpty by remember { mutableStateOf(false) }
    var deleteOne by remember { mutableStateOf<VaultEntry?>(null) }
    val context = LocalContext.current
    Page(
        title = stringResource(R.string.trash), onBack = onBack, scroll = false,
        bottomBar = if (items.isEmpty()) null else ({ DangerButton(stringResource(R.string.empty_trash), { confirmEmpty = true }) }),
    ) {
        Quiet(stringResource(R.string.trash_retention), Modifier.padding(bottom = 8.dp))
        if (items.isEmpty()) Body(stringResource(R.string.trash_empty), Modifier.padding(top = 24.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(items, key = { it.id.hex }) { e ->
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(iconFor(e)), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onBackground)
                        Quiet(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(e.trashedAt ?: 0)) + if (e.isFolder) "" else " · " + Formatter.formatShortFileSize(context, e.size))
                    }
                    IconButton(onClick = { vault.restore(e.id); refresh() }) { Icon(painterResource(R.drawable.ic_restore), stringResource(R.string.restore_item, e.name)) }
                    IconButton(onClick = { deleteOne = e }) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.delete_forever_item, e.name)) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
    deleteOne?.let { e ->
        ConfirmDialog(stringResource(R.string.delete_forever_title), stringResource(R.string.delete_forever_body, e.name), stringResource(R.string.delete_forever), danger = true, onDismiss = { deleteOne = null }) {
            vault.deleteForever(e.id); deleteOne = null; refresh()
        }
    }
    if (confirmEmpty) {
        val typed = items.size > 50
        ConfirmDialog(
            stringResource(R.string.empty_trash_title), pluralStringResource(R.plurals.empty_trash_body, items.size, items.size),
            stringResource(R.string.empty_trash), danger = true, requireTyping = if (typed) stringResource(R.string.delete_phrase) else null,
            onDismiss = { confirmEmpty = false },
        ) { vault.emptyTrash(); confirmEmpty = false; refresh() }
    }
}

// ---------------------------------------------------------------- Settings

@Composable
private fun SettingsScreen(session: VaultSession, vault: UnlockedVault, onBack: () -> Unit, open: (Screen) -> Unit) {
    val context = LocalContext.current
    var selfTest by remember { mutableStateOf<SelfTest.Result?>(null) }
    var selfTestRunning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var protection by remember { mutableStateOf<DeviceLayer.Protection?>(null) }
    LaunchedEffect(Unit) { protection = withContext(Dispatchers.Default) { session.deviceLayer.protection() } }
    val versionName = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?" }

    Page(title = stringResource(R.string.settings), onBack = onBack) {
        Section(stringResource(R.string.section_security))
        SettingRow(stringResource(R.string.security_privacy), stringResource(R.string.security_privacy_sub)) { open(Screen.Security) }
        SettingRow(stringResource(R.string.lock_now), null) { session.lock() }
        SettingRow(
            stringResource(R.string.device_protection),
            stringResource(
                when (protection) {
                    DeviceLayer.Protection.STRONGBOX -> R.string.protection_strongbox
                    DeviceLayer.Protection.SECURE_HARDWARE -> R.string.protection_tee
                    DeviceLayer.Protection.SOFTWARE -> R.string.protection_software
                    else -> R.string.protection_unknown
                },
            ),
        ) {}
        val selfTestText = if (selfTestRunning) stringResource(R.string.self_test_running) else when (val r = selfTest) {
            is SelfTest.Result.Passed -> stringResource(R.string.self_test_detail, r.millis)
            is SelfTest.Result.Failed -> stringResource(R.string.self_test_failed, r.reason)
            null -> null
        }
        SettingRow(stringResource(R.string.run_self_test), selfTestText) {
            if (selfTestRunning) return@SettingRow
            selfTestRunning = true
            scope.launch {
                val app = context.applicationContext as BulwarkApplication
                selfTest = withContext(Dispatchers.Default) { SelfTest.run(app.primitives) }
                selfTestRunning = false
            }
        }

        Section(stringResource(R.string.section_storage))
        SettingRow(
            stringResource(R.string.storage_used),
            vault.fileCount().let { n -> pluralStringResource(R.plurals.storage_used_sub, n, Formatter.formatShortFileSize(context, vault.storageUsed()), n) },
        ) {}
        InfoCard(null, stringResource(R.string.uninstall_warning), R.drawable.ic_warning, warning = true)

        Section(stringResource(R.string.section_about))
        SettingRow(stringResource(R.string.terms_title), null) { open(Screen.Legal(R.raw.terms_of_service, R.string.terms_title)) }
        SettingRow(stringResource(R.string.privacy_title), null) { open(Screen.Legal(R.raw.privacy_statement, R.string.privacy_title)) }
        SettingRow(stringResource(R.string.licences), null) { open(Screen.Licences) }
        SettingRow(stringResource(R.string.version), versionName) {}
        Quiet(stringResource(R.string.no_internet), Modifier.padding(vertical = 12.dp))
    }

}

@Composable
internal fun Section(title: String) = Text(
    title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
)

@Composable
internal fun SettingRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(vertical = 10.dp), verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
        if (subtitle != null) Quiet(subtitle)
    }
}

@Composable
private fun ChangePasswordFlow(session: VaultSession, onDone: () -> Unit) {
    var saving by remember { mutableStateOf<String?>(null) }
    val pending = saving
    if (pending != null) SecuringScreen(onRun = { session.changePassword(pending.toCharArray()); onDone() }, onFailed = { saving = null })
    else CreatePasswordScreen(session, title = stringResource(R.string.change_password), intro = stringResource(R.string.change_password_body), onBack = onDone) { saving = it }
}

@Composable
private fun RecoverySetupFlow(session: VaultSession, vault: UnlockedVault, onDone: () -> Unit) {
    val key = remember { session.newRecoveryKey() }
    var confirming by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { key.close() } }
    if (!confirming) ShowWordsScreen(key, onDone = { confirming = true }, onBack = onDone)
    else ConfirmWordsScreen(key, onConfirmed = {
        key.copy().use { vault.setRecoveryKey(it) }
        session.prefs.noRecoveryAcknowledged = false
        onDone()
    }, onShowAgain = { confirming = false })
}

// ---------------------------------------------------------------- Dialogs

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.name)) }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onDone(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun MoveDialog(vault: UnlockedVault, entry: VaultEntry, onDismiss: () -> Unit, onMove: (FileId?) -> Unit) {
    // Every folder except the item itself and anything inside it.
    val targets = remember(entry) {
        fun walk(parent: FileId?, depth: Int): List<Pair<VaultEntry, Int>> =
            vault.children(parent).filter { it.isFolder && it.id != entry.id }.flatMap { listOf(it to depth) + walk(it.id, depth + 1) }
        walk(null, 0)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_title, entry.name)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item { MoveTarget(stringResource(R.string.top_level), 0) { onMove(null) } }
                items(targets, key = { it.first.id.hex }) { (f, depth) -> MoveTarget(f.name, depth + 1) { onMove(f.id) } }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun MoveTarget(name: String, depth: Int, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(start = (depth * 16).dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_folder), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 12.dp))
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    danger: Boolean = false,
    requireTyping: String? = null,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    val ok = requireTyping == null || typed.trim().equals(requireTyping, ignoreCase = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(body)
                if (requireTyping != null) {
                    Text(stringResource(R.string.skip_type, requireTyping), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(typed, { typed = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                if (danger) Text(stringResource(R.string.not_responsible), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = ok) {
                Text(confirm, color = if (danger && ok) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
