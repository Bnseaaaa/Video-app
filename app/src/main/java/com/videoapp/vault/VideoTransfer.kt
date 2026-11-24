package com.videoapp.vault

import android.app.Activity
import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

private enum class TransferMode { COPY, MOVE }

private data class TransferBatch(
    val mode: TransferMode,
    val items: List<VideoItem>,
)

private data class CopiedVideos(
    val originals: List<VideoItem>,
    val failed: Int,
    val alreadyThere: Int,
) {
    val skippedNote: String get() = buildString {
        if (alreadyThere > 0) append(". $alreadyThere video đã ở thư mục đích")
        if (failed > 0) append(". $failed video sao chép thất bại")
    }
}

@Composable
internal fun rememberVideoTransfer(vm: LibraryViewModel): (List<VideoItem>) -> Unit {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()
    var choices by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var pending by remember { mutableStateOf<TransferBatch?>(null) }
    var awaitingDelete by remember { mutableStateOf<CopiedVideos?>(null) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun finishMove(copied: CopiedVideos, mediaDeleted: Boolean) {
        if (!mediaDeleted) {
            message = "Đã giữ bản gốc và tạo ${copied.originals.size} bản sao${copied.skippedNote}"
            vm.refresh()
            return
        }
        working = true
        scope.launch {
            val deletedLegacy = withContext(Dispatchers.IO) {
                copied.originals.count { item ->
                    val file = item.localFile ?: return@count false
                    runCatching { file.delete() }.getOrDefault(false)
                }
            }
            val moved = copied.originals.count { it.localFile == null } + deletedLegacy
            val remaining = copied.originals.size - moved
            message = "Đã di chuyển $moved video" +
                (if (remaining > 0) ". $remaining bản gốc vẫn còn" else "") + copied.skippedNote
            working = false
            vm.refresh()
        }
    }

    val deleteOriginals = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val copied = awaitingDelete ?: return@rememberLauncherForActivityResult
        awaitingDelete = null
        finishMove(copied, result.resultCode == Activity.RESULT_OK)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val batch = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (tree == null) return@rememberLauncherForActivityResult
        if (tree.authority != "com.android.externalstorage.documents") {
            message = "Hãy chọn thư mục trong bộ nhớ điện thoại hoặc trên thẻ nhớ."
            return@rememberLauncherForActivityResult
        }

        working = true
        scope.launch {
            val copied = copyIntoFolder(resolver, tree, batch)
            working = false
            vm.refresh()
            if (batch.mode == TransferMode.COPY || copied.originals.isEmpty()) {
                message = "Đã sao chép ${copied.originals.size} video${copied.skippedNote}"
                return@launch
            }

            val media = copied.originals.filter { it.localFile == null }
            if (media.isEmpty()) {
                finishMove(copied, mediaDeleted = true)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                awaitingDelete = copied
                val launched = runCatching {
                    val request = MediaStore.createDeleteRequest(resolver, media.map { it.uri })
                    deleteOriginals.launch(IntentSenderRequest.Builder(request.intentSender).build())
                }.isSuccess
                if (!launched) {
                    awaitingDelete = null
                    finishMove(copied, mediaDeleted = false)
                }
            }
        }
    }

    fun pickDestination(mode: TransferMode) {
        val items = choices
        choices = emptyList()
        if (mode == TransferMode.MOVE && items.count { it.localFile == null } > 2_000) {
            message = "Hãy chọn tối đa 2.000 video trong mỗi lần di chuyển."
            return
        }
        pending = TransferBatch(mode, items)
        if (runCatching { pickFolder.launch(null) }.isFailure) {
            pending = null
            message = "Không mở được trình chọn thư mục."
        }
    }

    if (choices.isNotEmpty()) {
        val canMove = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R || choices.all { it.localFile != null }
        AlertDialog(
            onDismissRequest = { choices = emptyList() },
            title = { Text("Chuyển ${choices.size} video sang thư mục khác") },
            text = {
                Text(
                    if (canMove) "Chọn thao tác rồi chọn thư mục đích. Khi di chuyển video trong thư viện, Android sẽ hỏi trước khi xóa bản gốc."
                    else "Bạn có thể sao chép trên thiết bị này. Di chuyển video trong thư viện cần Android 11 trở lên.",
                )
            },
            confirmButton = { TextButton(onClick = { pickDestination(TransferMode.COPY) }) { Text("Sao chép") } },
            dismissButton = {
                if (canMove) {
                    TextButton(onClick = { pickDestination(TransferMode.MOVE) }) { Text("Di chuyển") }
                } else {
                    TextButton(onClick = { choices = emptyList() }) { Text("Hủy") }
                }
            },
        )
    }
    if (working) {
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("Đang xử lý video") },
            text = { CircularProgressIndicator() },
            confirmButton = {},
        )
    }
    message?.let { result ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("Kết quả") },
            text = { Text(result) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("Đóng") } },
        )
    }
    return { items ->
        if (!working && pending == null && awaitingDelete == null) {
            choices = items.distinctBy { it.id }
        }
    }
}

private suspend fun copyIntoFolder(
    resolver: ContentResolver,
    tree: Uri,
    batch: TransferBatch,
): CopiedVideos = withContext(Dispatchers.IO) {
    val treeId = DocumentsContract.getTreeDocumentId(tree)
    val folder = DocumentsContract.buildDocumentUriUsingTree(tree, treeId)
    val copied = mutableListOf<VideoItem>()
    var failed = 0
    var alreadyThere = 0
    for (item in batch.items) {
        coroutineContext.ensureActive()
        if (batch.mode == TransferMode.MOVE && isInFolder(resolver, item, treeId)) {
            alreadyThere++
        } else if (copyVideo(resolver, folder, item)) {
            copied += item
        } else {
            failed++
        }
    }
    CopiedVideos(copied, failed, alreadyThere)
}

private fun isInFolder(resolver: ContentResolver, item: VideoItem, treeId: String): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || item.localFile != null) return false
    val targetPath = treeId.substringAfter(':').trim('/')
    if (item.path?.trim('/') != targetPath) return false
    val volumeColumn = MediaStore.MediaColumns.VOLUME_NAME
    val volume = runCatching {
        resolver.query(item.uri, arrayOf(volumeColumn), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()
    val targetVolume = treeId.substringBefore(':').let {
        if (it.equals("primary", ignoreCase = true)) MediaStore.VOLUME_EXTERNAL_PRIMARY else it
    }
    return volume.equals(targetVolume, ignoreCase = true)
}

private suspend fun copyVideo(resolver: ContentResolver, folder: Uri, item: VideoItem): Boolean {
    var created: Uri? = null
    var completed = false
    try {
        val input = item.localFile?.inputStream() ?: resolver.openInputStream(item.uri) ?: return false
        input.use { source ->
            val mime = item.mime ?: "video/mp4"
            val name = item.fileName.ifBlank { item.localFile?.name ?: item.name }
                .replace('/', '_').replace('\\', '_')
            val destination = DocumentsContract.createDocument(resolver, folder, mime, name) ?: return false
            created = destination
            val output = resolver.openOutputStream(destination, "w") ?: return false
            var total = 0L
            output.use { target ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    target.write(buffer, 0, count)
                    total += count
                }
            }
            val savedSize = resolver.openFileDescriptor(destination, "r")?.use { it.statSize }
            completed = total > 0L && savedSize == total && (item.sizeBytes <= 0L || total == item.sizeBytes)
        }
        return completed
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        return false
    } finally {
        if (!completed) created?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
    }
}
