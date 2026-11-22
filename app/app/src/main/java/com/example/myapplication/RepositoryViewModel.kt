package com.example.myapplication

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.git.GitChanges
import com.example.myapplication.git.GitOperationException
import com.example.myapplication.git.JGitRepositoryService
import com.example.myapplication.git.RepositoryConfig
import com.example.myapplication.git.RepositoryStore
import com.example.myapplication.git.ResetPreview
import com.example.myapplication.git.requireGit
import com.example.myapplication.storage.SharedFolders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.errors.CheckoutConflictException
import org.eclipse.jgit.api.errors.TransportException
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

internal data class RepositoryForm(
    val name: String = "",
    val path: String = "",
    val url: String = "",
    val branch: String = "main",
    val author: String = "",
    val email: String = "",
)

internal data class OperationResult(val message: String, val failed: Boolean = false)

internal data class RepositoryScreenState(
    val repositories: List<RepositoryConfig> = emptyList(),
    val selected: RepositoryConfig? = null,
    val changes: GitChanges? = null,
    val pending: String? = null,
    val result: OperationResult? = null,
    val history: List<OperationResult> = emptyList(),
    val resetPreview: ResetPreview? = null,
)

internal class RepositoryViewModel(application: Application) : AndroidViewModel(application) {
    @Suppress("DEPRECATION")
    val sharedFolders = SharedFolders(Environment.getExternalStorageDirectory())
    private val git = JGitRepositoryService(sharedFolders)
    private val store = RepositoryStore(application)
    private val mutableState = MutableStateFlow(RepositoryScreenState())
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            store.repositories.catch {
                mutableState.update { state -> state.copy(result = OperationResult("登録情報を読み込めませんでした。", true)) }
            }.collect { repositories ->
                mutableState.update { it.copy(repositories = repositories) }
            }
        }
    }

    fun select(config: RepositoryConfig?) {
        if (mutableState.value.pending != null) return
        mutableState.update { it.copy(selected = config, changes = null, result = null, history = emptyList(), resetPreview = null) }
        if (config != null) status()
    }

    fun save(form: RepositoryForm, clone: Boolean, token: String) = run(if (clone) "clone" else "登録") {
        val config = git.config(form.name, form.path, form.url, form.branch, form.author, form.email)
        store.checkAvailable(config.directory, store.repositories.first())
        if (clone) git.clone(config, token) else git.register(config)
        store.add(config)
        config
    } then { config ->
        copy(selected = config, changes = null, result = OperationResult("登録しました。"))
    }

    fun remove() = runSelected("登録解除", requiresStorage = false) { config ->
        store.remove(config.id)
        "登録情報を削除しました。"
    } then { message ->
        copy(selected = null, changes = null, result = OperationResult(message), history = emptyList())
    }

    fun status() = runSelected("status") { git.status(it) } then { changes ->
        copy(changes = changes, result = OperationResult("作業ツリーを確認しました。"))
    }

    fun stage(paths: Set<String>) = runSelected("add") {
        git.stage(it, paths)
        git.status(it)
    } then { changes ->
        copy(changes = changes, result = OperationResult("${paths.size} 件をステージしました。"))
    }

    fun commit(message: String) = operation("commit") { "コミット ${git.commit(it, message)} を作成しました。" }
    fun fetch(token: String) = operation("fetch") { "${git.fetch(it, token)} を取得しました。" }
    fun fastForward() = operation("fast-forward", clearsStatus = true) { git.fastForward(it) }
    fun push(token: String) = operation("push") { git.push(it, token) }

    fun previewReset() = runSelected("破棄の対象を確認") { git.previewReset(it) } then { preview ->
        copy(resetPreview = preview)
    }

    fun cancelReset() {
        mutableState.update { it.copy(resetPreview = null) }
    }

    fun confirmReset() {
        val preview = mutableState.value.resetPreview ?: return
        runSelected("reset") { git.resetToHead(it, preview) } then { changes ->
            copy(changes = changes, result = OperationResult("未コミットの変更を HEAD に戻しました。"))
        }
    }

    private fun operation(label: String, clearsStatus: Boolean = label == "commit", work: (RepositoryConfig) -> String) =
        runSelected(label, work = work) then { message ->
            copy(changes = if (clearsStatus) null else changes, result = OperationResult(message))
        }

    private fun <T> runSelected(
        label: String, requiresStorage: Boolean = true, work: suspend (RepositoryConfig) -> T,
    ): Operation<T> = run(label, requiresStorage) {
        val config = mutableState.value.selected ?: throw GitOperationException("リポジトリを選択してください。")
        work(config)
    }

    private fun <T> run(label: String, requiresStorage: Boolean = true, work: suspend () -> T) =
        Operation(label, requiresStorage, work)

    private inner class Operation<T>(
        private val label: String,
        private val requiresStorage: Boolean,
        private val work: suspend () -> T,
    ) {
        infix fun then(update: RepositoryScreenState.(T) -> RepositoryScreenState) {
            if (mutableState.value.pending != null) return
            mutableState.update { it.copy(pending = label, result = null, resetPreview = null) }
            viewModelScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        requireGit(!requiresStorage || Environment.isExternalStorageManager(), "共有ストレージへのアクセスを許可してください。")
                        work()
                    }
                    mutableState.update { it.update(result).record(label) }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    mutableState.update { it.copy(result = OperationResult(describe(error), true)).record(label) }
                } finally {
                    mutableState.update { it.copy(pending = null) }
                }
            }
        }
    }

    private fun RepositoryScreenState.record(label: String): RepositoryScreenState {
        val entry = result ?: return this
        return copy(history = (listOf(entry.copy(message = "$label · ${entry.message}")) + history).take(8))
    }

    private fun describe(error: Exception): String = when (error) {
        is GitOperationException -> error.message.orEmpty()
        is CheckoutConflictException -> "取り込み先と作業ツリーが競合しました。"
        is TransportException -> when {
            generateSequence<Throwable>(error) { it.cause }.any { it is SSLException } -> "TLS 証明書または HTTPS 接続を確認してください。"
            generateSequence<Throwable>(error) { it.cause }.any { it is SocketTimeoutException } -> "通信がタイムアウトしました。"
            Regex("(?i)not authorized|authentication|401|403").containsMatchIn(error.message.orEmpty()) ->
                "GitHub の PAT とリポジトリ権限を確認してください。"
            else -> "GitHub との通信に失敗しました。接続とリポジトリ設定を確認してください。"
        }
        is SecurityException -> "フォルダへのアクセスが拒否されました。"
        is IOException -> "ファイルの読み書きに失敗しました。"
        else -> "Git 操作を完了できませんでした（${error.javaClass.simpleName}）。"
    }
}
