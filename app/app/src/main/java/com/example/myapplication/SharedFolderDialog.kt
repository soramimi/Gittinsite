package com.example.myapplication

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.myapplication.storage.SharedFolderListing
import com.example.myapplication.storage.SharedFolders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

@Composable
internal fun SharedFolderDialog(
    sharedFolders: SharedFolders, initialPath: String, onDismiss: () -> Unit, onSelect: (String) -> Unit,
) {
    var requested by remember { mutableStateOf<File?>(null) }
    var listing by remember(requested) { mutableStateOf<SharedFolderListing?>(null) }
    var error by remember(requested) { mutableStateOf<String?>(null) }
    LaunchedEffect(sharedFolders, requested) {
        try {
            listing = withContext(Dispatchers.IO) { sharedFolders.list(requested, initialPath) }
        } catch (failure: IOException) {
            error = failure.message ?: "フォルダを読み込めませんでした。"
        } catch (_: SecurityException) {
            error = "このフォルダへのアクセスが拒否されました。"
        }
    }

    val current = listing
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("共有フォルダを選択") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    current != null -> {
                        Text(current.directory.path, style = MaterialTheme.typography.bodySmall)
                        current.parent?.let { parent ->
                            TextButton(onClick = { requested = parent }) { Text("上のフォルダ") }
                        }
                        if (current.folders.isEmpty()) {
                            Text("サブフォルダはありません。")
                        } else {
                            LazyColumn(Modifier.heightIn(max = 280.dp)) {
                                items(current.folders, key = { it.path }) { folder ->
                                    ListItem(
                                        headlineContent = { Text(folder.name) },
                                        modifier = Modifier.clickable(role = Role.Button) { requested = folder },
                                    )
                                }
                            }
                        }
                    }
                    error != null -> {
                        Text(checkNotNull(error), color = MaterialTheme.colorScheme.error)
                        requested?.let { failed ->
                            TextButton(onClick = { requested = failed.parentFile }) { Text("上のフォルダ") }
                        }
                    }
                    else -> LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { current?.let { onSelect(it.directory.path) } },
                enabled = current?.selectable == true,
            ) { Text("このフォルダを選択") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}
