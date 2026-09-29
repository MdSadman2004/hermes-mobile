package com.hermes.mobile.ui.files

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.hermes.mobile.data.repo.RemoteEntry
import com.hermes.mobile.data.repo.RemoteFileContent
import com.hermes.mobile.ui.components.EmptyState
import com.hermes.mobile.ui.components.MetaChip
import com.hermes.mobile.ui.components.SkeletonList
import com.hermes.mobile.ui.theme.HermesMono
import com.hermes.mobile.ui.theme.hermes

/**
 * The PC's filesystem, from wherever you are.
 *
 * Directories sort above files and both sort by name, because a raw server
 * ordering makes a folder of two hundred build artefacts unnavigable. System
 * Back goes up a directory before it leaves the panel — the thing every file
 * browser on the platform already does.
 */
@Composable
fun FilesScreen(vm: FilesViewModel = hiltViewModel()) {
    val listing by vm.listing.collectAsState()
    val loading by vm.loading.collectAsState()
    val preview by vm.preview.collectAsState()
    val transfer by vm.transfer.collectAsState()
    val receipt by vm.receipt.collectAsState()

    // OpenDocument (not GetContent): it returns a persistable URI for ANY file
    // from any provider - Drive, Downloads, a gallery - which is what "send any
    // file from my phone" actually requires.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        android.util.Log.i("HermesShare", "picker returned uri=" + uri)
        if (uri != null) vm.sendToPc(uri, intoCurrentDir = true)
    }

    // Start at the inbox, not at whatever cwd the server process happens to
    // have. Running the dashboard as a SYSTEM service lands the default on
    // C:\Windows\System32\config\systemprofile - three folders of nothing,
    // and a long climb to anywhere useful.
    LaunchedEffect(Unit) { if (listing == null) vm.browseInbox() }
    BackHandler(enabled = listing?.parent != null) { vm.up() }

    val entries = remember(listing) {
        listing?.entries.orEmpty()
            .sortedWith(compareByDescending<RemoteEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        } else {
            Spacer(Modifier.height(2.dp))
        }

        listing?.let { l ->
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    // The inbox is a place with a meaning, not just a path.
                    // Naming it is what tells you where a sent file will land.
                    if (vm.atInbox) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Inbox,
                                contentDescription = null,
                                Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Inbox from this phone",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    Text(
                        l.path.ifBlank { "managed roots" },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = HermesMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (l.parent != null) {
                    IconButton(
                        onClick = { vm.up() },
                        modifier = Modifier.semantics {
                            contentDescription = "Go up one directory"
                        },
                    ) {
                        Icon(
                            Icons.Outlined.ArrowUpward,
                            contentDescription = null,
                            Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        when {
            listing == null && loading ->
                SkeletonList(rows = 8, rowHeight = 44, modifier = Modifier.padding(16.dp))

            entries.isEmpty() && vm.atInbox -> EmptyState(
                icon = Icons.Outlined.Inbox,
                title = "Nothing sent yet",
                hint = "Tap Send a file to move anything from this phone onto " +
                    "your PC \u2014 a photo, a PDF, a log \u2014 then ask Hermes to work " +
                    "on it. Files you send land here.",
            )

            entries.isEmpty() -> EmptyState(
                icon = Icons.Outlined.FolderOff,
                title = "Empty directory",
                hint = "Nothing here. Use the up arrow, or ask Hermes to " +
                    "create something in this folder.",
            )

            else -> LazyColumn(
                // Leave room for the FAB and the status cards; a list whose final row
                // sits under a floating button is a row you cannot tap.
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 168.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                items(entries, key = { it.path }) { entry ->
                    EntryRow(
                        entry = entry,
                        onClick = { vm.open(entry) },
                        onSave = { vm.saveToPhone(entry) },
                    )
                }
            }
        }
    }

        // Status lives at the bottom, above the FAB, where the thumb already is
        // and where it cannot push the listing around while you are reading it.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TransferCard(transfer)
            TransferReceipt(receipt, onDismiss = vm::dismissReceipt)
        }

        Box(Modifier.align(Alignment.BottomEnd)) {
            SendFab(enabled = transfer == null) { picker.launch(arrayOf("*/*")) }
        }
    }

    preview?.let {
        FilePreviewDialog(
            file = it,
            onClose = vm::closePreview,
            onSave = vm::savePreviewToPhone,
        )
    }
}

/**
 * "Send a file" lives on a FAB rather than in a menu: moving a file off the
 * phone is one of the two reasons to open this panel at all, and burying it
 * behind an overflow makes the feature invisible.
 */
@Composable
private fun SendFab(enabled: Boolean, onClick: () -> Unit) {
    ExtendedFloatingActionButton(
        onClick = { if (enabled) onClick() },
        icon = { Icon(Icons.Outlined.Upload, contentDescription = null) },
        text = { Text(if (enabled) "Send a file" else "Sending...") },
        modifier = Modifier
            .navigationBarsPadding()
            .padding(16.dp)
            .semantics { contentDescription = "Send a file from this phone to the PC" },
    )
}

@Composable
private fun EntryRow(entry: RemoteEntry, onClick: () -> Unit, onSave: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (entry.isDirectory) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription =
                    "${if (entry.isDirectory) "Folder" else "File"} ${entry.name}"
            },
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (entry.isDirectory) Icons.Outlined.Folder
                else Icons.AutoMirrored.Outlined.InsertDriveFile,
                contentDescription = null,
                Modifier.size(17.dp),
                tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            entry.size?.takeIf { !entry.isDirectory }?.let {
                Spacer(Modifier.width(8.dp))
                Text(
                    formatSize(it),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = HermesMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!entry.isDirectory) {
                IconButton(
                    onClick = onSave,
                    modifier = Modifier.semantics {
                        contentDescription = "Save ${entry.name} to this phone"
                    },
                ) {
                    Icon(
                        Icons.Outlined.Download,
                        contentDescription = null,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun FilePreviewDialog(
    file: RemoteFileContent,
    onClose: () -> Unit,
    onSave: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val semantics = MaterialTheme.hermes

    Dialog(onDismissRequest = onClose) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${file.mimeType} · ${formatSize(file.size)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                val bytes = remember(file.dataUrl) {
                    runCatching {
                        Base64.decode(file.dataUrl.substringAfter("base64,"), Base64.DEFAULT)
                    }.getOrNull()
                }
                val text = remember(bytes, file.mimeType) {
                    if (isTextual(file.mimeType, file.name)) {
                        bytes?.toString(Charsets.UTF_8)
                    } else {
                        null
                    }
                }

                Box(Modifier.heightIn(max = 420.dp)) {
                    when {
                        file.mimeType.startsWith("image/") && bytes != null -> {
                            val bmp = remember(bytes) {
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            }
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Preview of ${file.name}",
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                Text(
                                    "That image couldn't be decoded.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        text != null -> Text(
                            text.take(20_000),
                            fontFamily = HermesMono,
                            style = MaterialTheme.typography.bodySmall,
                            color = semantics.onCode,
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                        )

                        else -> Text(
                            "No inline preview for ${file.mimeType}. Ask Hermes to " +
                                "summarise or convert it instead.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (text != null) {
                        TextButton(
                            onClick = { clipboard.setText(AnnotatedString(text)) },
                            modifier = Modifier.heightIn(min = 48.dp).semantics {
                                contentDescription = "Copy file contents"
                            },
                        ) { Text("Copy") }
                    }
                    TextButton(
                        onClick = onSave,
                        modifier = Modifier.heightIn(min = 48.dp).semantics {
                            contentDescription = "Save ${file.name} to this phone"
                        },
                    ) { Text("Save to phone") }
                    TextButton(
                        onClick = onClose,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = "Close preview" },
                    ) { Text("Close") }
                }
            }
        }
    }
}

/**
 * The server's mime guess is `application/octet-stream` for most source files,
 * so extension is the more useful signal for "can I read this as text".
 */
private fun isTextual(mimeType: String, name: String): Boolean {
    if (mimeType.startsWith("text/")) return true
    if (mimeType.contains("json") || mimeType.contains("xml") ||
        mimeType.contains("javascript") || mimeType.contains("yaml")
    ) {
        return true
    }
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in TEXT_EXTENSIONS
}

private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "markdown", "log", "json", "yaml", "yml", "toml", "ini", "cfg", "conf", "env",
    "kt", "kts", "java", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cs", "swift",
    "js", "jsx", "ts", "tsx", "vue", "svelte", "css", "scss", "html", "htm", "xml", "svg",
    "sh", "bash", "zsh", "fish", "ps1", "bat", "cmd", "sql", "csv", "tsv", "gradle",
    "properties", "gitignore", "dockerfile", "makefile", "lock", "diff", "patch",
)

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}
