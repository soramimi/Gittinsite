package com.example.myapplication

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.myapplication.git.RepositoryConfig
import com.example.myapplication.storage.SharedFolders

@Composable
internal fun RepositoryScreen(
    state: RepositoryScreenState, storageAllowed: Boolean, model: RepositoryViewModel, requestStorage: () -> Unit,
) {
    val enabled = state.pending == null
    val selected = state.selected
    var adding by remember { mutableStateOf(false) }
    var token by remember(selected?.id, adding) { mutableStateOf("") }
    var dialog by remember(selected?.id) { mutableStateOf<String?>(null) }
    var message by remember(dialog) { mutableStateOf("") }
    var chosen by remember(selected?.id, state.changes) { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(selected?.id) { if (selected != null) adding = false }
    BackHandler(enabled = enabled && (adding || selected != null)) {
        if (adding) adding = false else model.select(null)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            if (adding || selected != null) TextButton(
                onClick = { if (adding) adding = false else model.select(null) },
                enabled = enabled,
            ) { Text("一覧に戻る") }
            Text(
                if (adding) "リポジトリを追加" else selected?.name ?: stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
            )
        }
        if (!storageAllowed) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("共有フォルダを読み書きするには、すべてのファイルへのアクセスを許可してください。")
                    Button(onClick = requestStorage, enabled = enabled) { Text("アクセスを許可") }
                }
            }
        }
        state.pending?.let { label -> item {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("$label を実行中")
        } }
        state.result?.let { result -> item { ResultText(result) } }

        when {
            adding -> item { RegisterForm(enabled && storageAllowed, token, { token = it }, model.sharedFolders, model::save) }
            selected != null -> {
                val available = enabled && storageAllowed
                item {
                    Text(selected.workingDirectory)
                    Text(selected.remoteUrl, style = MaterialTheme.typography.bodySmall)
                    Text("ブランチ ${selected.branch}")
                }
                item { TokenInput(token, { token = it }, available) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = model::status, enabled = available) { Text("status") }
                            OutlinedButton(onClick = { model.stage(chosen) }, enabled = available && chosen.isNotEmpty()) { Text("add") }
                            OutlinedButton(onClick = { dialog = "commit" }, enabled = available) { Text("commit") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { model.fetch(token) }, enabled = available) { Text("fetch") }
                            OutlinedButton(onClick = model::fastForward, enabled = available) { Text("fast-forward") }
                            Button(onClick = { dialog = "push" }, enabled = available && token.isNotBlank()) { Text("push") }
                        }
                        TextButton(onClick = model::previewReset, enabled = available) { Text("変更を破棄（reset）") }
                    }
                }
                state.changes?.let { changes ->
                    item {
                        Text("作業ツリー", style = MaterialTheme.typography.titleMedium)
                        if (changes.stageable.isEmpty() && changes.staged.isEmpty()) Text("変更はありません。")
                    }
                    items(changes.staged.sorted(), key = { "staged:$it" }) { Text("ステージ済み  $it") }
                    items(changes.stageable.sorted(), key = { "stageable:$it" }) { path ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(path in chosen, onCheckedChange = { checked ->
                                chosen = if (checked) chosen + path else chosen - path
                            }, enabled = available)
                            Text(if (path in changes.untracked) "未追跡  $path" else "未ステージ  $path")
                        }
                    }
                }
                if (state.history.isNotEmpty()) {
                    item {
                        HorizontalDivider()
                        Text("操作履歴", style = MaterialTheme.typography.titleMedium)
                    }
                    items(state.history) { ResultText(it) }
                }
                item { TextButton(onClick = { dialog = "remove" }, enabled = enabled) { Text("登録を解除") } }
            }
            else -> {
                item { Button(onClick = { adding = true }, enabled = enabled && storageAllowed) { Text("リポジトリを追加") } }
                if (state.repositories.isEmpty()) item { Text("登録済みのリポジトリはありません。") }
                items(state.repositories, key = RepositoryConfig::id) { repository ->
                    Card(onClick = { model.select(repository) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(repository.name, style = MaterialTheme.typography.titleMedium)
                            Text(repository.branch)
                            Text(repository.workingDirectory, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    dialog?.let { action ->
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(when (action) {
                "commit" -> "ステージ済みの変更をコミット"
                "push" -> "GitHub に push"
                else -> "登録を解除"
            }) },
            text = {
                when (action) {
                    "commit" -> Field("コミットメッセージ", message, { message = it }, true)
                    "push" -> Text("${selected?.remoteUrl}\nブランチ ${selected?.branch}")
                    else -> Text("登録情報を削除します。端末上のファイルは残ります。")
                }
            },
            confirmButton = {
                TextButton(
                    enabled = action != "commit" || message.isNotBlank(),
                    onClick = {
                        when (action) {
                            "commit" -> model.commit(message)
                            "push" -> model.push(token)
                            else -> model.remove()
                        }
                        dialog = null
                    },
                ) { Text(if (action == "remove") "解除" else action) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("キャンセル") } },
        )
    }

    state.resetPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = model::cancelReset,
            title = { Text("未コミットの変更を破棄") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("以下のファイルを ${preview.head.abbreviate(8).name()}（HEAD）の状態に戻します。ステージした新規ファイルも破棄されます。")
                    preview.paths.forEach { path -> Text(path) }
                }
            },
            confirmButton = {
                TextButton(onClick = model::confirmReset, enabled = enabled && storageAllowed) {
                    Text("変更を破棄", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = model::cancelReset) { Text("キャンセル") } },
        )
    }
}

@Composable
private fun RegisterForm(
    enabled: Boolean, token: String, changeToken: (String) -> Unit,
    sharedFolders: SharedFolders,
    save: (RepositoryForm, Boolean, String) -> Unit,
) {
    var form by remember { mutableStateOf(RepositoryForm()) }
    var clone by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = !clone, onClick = { clone = false }, enabled = enabled)
            Text("既存フォルダ")
            RadioButton(selected = clone, onClick = { clone = true }, enabled = enabled)
            Text("新規 clone")
        }
        Field("表示名（任意）", form.name, { form = form.copy(name = it) }, enabled)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                form.path, { form = form.copy(path = it) }, Modifier.weight(1f),
                enabled = enabled, label = { Text("共有フォルダの絶対パス") }, singleLine = true,
            )
            OutlinedButton(onClick = {
                focusManager.clearFocus()
                browsing = true
            }, enabled = enabled) { Text("参照") }
        }
        if (clone) Text("作成済みの親フォルダの下に、空の保存先を指定してください。")
        Field("GitHub HTTPS URL", form.url, { form = form.copy(url = it) }, enabled)
        Field("ブランチ", form.branch, { form = form.copy(branch = it) }, enabled)
        Field("コミット作成者の名前", form.author, { form = form.copy(author = it) }, enabled)
        Field("コミット作成者のメール", form.email, { form = form.copy(email = it) }, enabled)
        if (clone) TokenInput(token, changeToken, enabled)
        Button(
            onClick = { save(form, clone, token) },
            enabled = enabled && form.path.isNotBlank() && form.url.isNotBlank() && form.branch.isNotBlank() &&
                form.author.isNotBlank() && form.email.contains('@'),
        ) { Text(if (clone) "clone して登録" else "登録") }
    }
    if (browsing && enabled) {
        SharedFolderDialog(sharedFolders, form.path, onDismiss = { browsing = false }) { path ->
            form = form.copy(path = path)
            browsing = false
        }
    }
}

@Composable
private fun Field(label: String, value: String, change: (String) -> Unit, enabled: Boolean) {
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), enabled = enabled, label = { Text(label) }, singleLine = true)
}

@Composable
private fun TokenInput(token: String, change: (String) -> Unit, enabled: Boolean) {
    OutlinedTextField(
        token, change, Modifier.fillMaxWidth(), enabled = enabled, label = { Text("GitHub PAT") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        supportingText = { Text("公開リポジトリの取得時は省略できます。入力はメモリ内に保持されます。") },
    )
}

@Composable
private fun ResultText(result: OperationResult) {
    Text(result.message, color = if (result.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
}
