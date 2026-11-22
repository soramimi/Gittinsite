package com.example.myapplication.git

import com.example.myapplication.storage.SharedFolders
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.RemoteConfig
import org.eclipse.jgit.util.io.DisabledOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal interface GitRepositoryService {
    fun register(config: RepositoryConfig)
    fun clone(config: RepositoryConfig, token: String)
    fun status(config: RepositoryConfig): GitChanges
    fun stage(config: RepositoryConfig, paths: Set<String>)
    fun commit(config: RepositoryConfig, message: String): String
    fun previewReset(config: RepositoryConfig): ResetPreview
    fun resetToHead(config: RepositoryConfig, preview: ResetPreview): GitChanges
    fun fetch(config: RepositoryConfig, token: String): String
    fun fastForward(config: RepositoryConfig): String
    fun push(config: RepositoryConfig, token: String): String
}

internal class JGitRepositoryService(private val sharedFolders: SharedFolders) : GitRepositoryService {
    private val fetched = ConcurrentHashMap<String, ObjectId>()

    fun config(name: String, path: String, url: String, branch: String, author: String, email: String): RepositoryConfig {
        val directory = File(path.trim())
        requireGit(directory.isAbsolute, "共有フォルダの絶対パスを入力してください。")
        val canonical = directory.canonicalFile
        checkLocation(canonical)
        requireGit(Repository.isValidRefName(Constants.R_HEADS + branch.trim()), "有効なブランチ名を入力してください。")
        requireGit(author.isNotBlank() && email.contains('@'), "コミット作成者の名前とメールアドレスを入力してください。")
        return RepositoryConfig(
            UUID.randomUUID().toString(), name.trim().ifEmpty { canonical.name }, canonical.path,
            normalizeUrl(url), branch.trim(), author.trim(), email.trim(),
        )
    }

    override fun register(config: RepositoryConfig) {
        withRepository(config) { }
    }

    override fun clone(config: RepositoryConfig, token: String) {
        val directory = config.directory
        RepositoryLocks.withLock(directory) {
            checkLocation(directory)
            val parent = checkNotNull(directory.parentFile)
            requireGit(parent.isDirectory && parent.canWrite(), "保存先の親フォルダを書き込めません。")
            checkEmptyDestination(directory)
            val transport = githubTransport(config.remoteUrl, token, allowAnonymous = true)
            val temporary = Files.createTempDirectory(parent.toPath(), ".GitSync-clone-").toFile()
            Git.cloneRepository().setURI(config.remoteUrl).setDirectory(temporary)
                .setBranch(config.branchRef).setBranchesToClone(listOf(config.branchRef))
                .setNoTags().setTimeout(GitRemote.TIMEOUT_SECONDS)
                .setTransportConfigCallback(transport).call().use { validate(it, config) }
            checkEmptyDestination(directory)
            try {
                Files.move(temporary.toPath(), directory.toPath(), ATOMIC_MOVE)
            } catch (_: IOException) {
                throw GitOperationException("保存先へ移動できませんでした。clone 済みのフォルダは ${temporary.path} にあります。")
            }
        }
    }

    override fun status(config: RepositoryConfig): GitChanges =
        withRepository(config) { GitWorkTree.changes(it.status().call()) }

    override fun stage(config: RepositoryConfig, paths: Set<String>) =
        withRepository(config) { GitWorkTree.stage(it, paths) }

    override fun previewReset(config: RepositoryConfig): ResetPreview =
        withRepository(config) { GitWorkTree.previewReset(it) }

    override fun resetToHead(config: RepositoryConfig, preview: ResetPreview): GitChanges =
        withRepository(config) {
            GitWorkTree.resetToHead(it, preview)
            GitWorkTree.changes(it.status().call())
        }

    override fun commit(config: RepositoryConfig, message: String): String =
        withRepository(config) { git ->
            requireGit(GitWorkTree.changes(git.status().call()).staged.isNotEmpty(), "ステージ済みの変更がありません。")
            requireGit(message.isNotBlank(), "コミットメッセージを入力してください。")
            requireGit(config.authorName.isNotBlank() && config.authorEmail.contains('@'),
                "登録時にコミット作成者の名前とメールアドレスを指定してください。")
            git.commit().setMessage(message.trim())
                .setAuthor(config.authorName, config.authorEmail)
                .setCommitter(config.authorName, config.authorEmail).call().id.abbreviate(8).name()
        }

    override fun fetch(config: RepositoryConfig, token: String): String =
        withRepository(config) { git ->
            fetched.remove(config.id)
            val remote = fetchRemote(git, config, token)
            fetched[config.id] = remote
            remote.abbreviate(8).name()
        }

    override fun fastForward(config: RepositoryConfig): String =
        withRepository(config) { git ->
            val remote = fetched[config.id]
                ?: throw GitOperationException("このセッションで対象ブランチを fetch してください。")
            val repo = git.repository
            requireGit(repo.resolve(config.trackingRef) == remote, "追跡ブランチが変わりました。再度 fetch してください。")
            val head = repo.resolve(Constants.HEAD)
            if (head == remote) return@withRepository "すでに最新です。"
            requireGit(isAncestor(repo, head, remote), "ローカルが先行または分岐しています。fast-forward できません。")
            val changedPaths = DiffFormatter(DisabledOutputStream.INSTANCE).use { formatter ->
                formatter.setRepository(repo)
                formatter.scan(head, remote).flatMap { entry ->
                    requireGit(entry.newPath == DiffEntry.DEV_NULL ||
                        entry.newMode == FileMode.REGULAR_FILE || entry.newMode == FileMode.EXECUTABLE_FILE,
                        "取り込み先に通常ファイル以外の変更があります。")
                    listOf(entry.oldPath, entry.newPath).filterNot { it == DiffEntry.DEV_NULL }
                }.toSet()
            }
            validate(git, config)
            val status = git.status().call()
            val untracked = status.untracked + status.ignoredNotInIndex
            requireGit(repo.resolve(Constants.HEAD) == head &&
                GitWorkTree.changes(status).discardable.isEmpty() &&
                changedPaths.none { path -> untracked.any { GitWorkTree.pathsOverlap(path, it) } },
                "追跡済みの変更、ステージ済み変更、または取り込み先と重なるファイルがあります。")
            changedPaths.forEach { GitWorkTree.checkedFile(git, it) }
            GitRemote.fastForward(git, remote)
            "fast-forward が完了しました。"
        }

    override fun push(config: RepositoryConfig, token: String): String =
        withRepository(config) { git ->
            val transport = githubTransport(config.remoteUrl, token)
            val head = git.repository.resolve(Constants.HEAD)
            fetched.remove(config.id)
            val remote = fetchRemote(git, config, token)
            fetched[config.id] = remote
            validate(git, config)
            requireGit(git.repository.resolve(Constants.HEAD) == head, "ローカルの HEAD が変わりました。")
            if (head == remote) return@withRepository "送信するコミットはありません。"
            requireGit(isAncestor(git.repository, remote, head), "リモートが先行または分岐しています。push を停止しました。")
            GitRemote.push(git, config.remoteUrl, config.branchRef, transport, remote)
            "push が完了しました。"
        }

    private fun <T> withRepository(config: RepositoryConfig, action: (Git) -> T): T {
        val directory = config.directory
        return RepositoryLocks.withLock(directory) {
            checkLocation(directory)
            requireGit(Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS) &&
                Files.isDirectory(File(directory, ".git").toPath(), NOFOLLOW_LINKS) &&
                directory.canRead() && directory.canWrite(),
                "読み書き可能な既存の Git フォルダを指定してください。")
            Git.open(directory).use { git ->
                requireGit(git.repository.workTree.canonicalFile == directory &&
                    git.repository.directory.canonicalFile == File(directory, ".git"),
                    "登録したフォルダの外を参照する Git 設定があります。")
                validate(git, config)
                action(git)
            }
        }
    }

    private fun validate(git: Git, config: RepositoryConfig) {
        val repo = git.repository
        requireGit(repo.repositoryState == RepositoryState.SAFE, "マージや rebase などの未完了の操作があります。")
        requireGit(repo.fullBranch == config.branchRef && repo.resolve(Constants.HEAD) != null,
            "現在のブランチが登録したブランチと一致しません。")
        val remote = RemoteConfig(repo.config, "origin")
        val fetchUrl = remote.getURIs().singleOrNull()?.toString()
        val pushUrls = remote.getPushURIs()
        requireGit(fetchUrl != null && normalizeUrl(fetchUrl) == config.remoteUrl &&
            (pushUrls.isEmpty() || pushUrls.size == 1 && normalizeUrl(pushUrls.first().toString()) == config.remoteUrl),
            "origin の fetch 先または push 先が登録した GitHub リポジトリと一致しません。")
        requireGit(repo.config.getString("core", null, "hooksPath") == null &&
            repo.config.getSubsections("filter").isEmpty() &&
            File(repo.directory, "hooks").listFiles().orEmpty().none { !it.name.endsWith(".sample") },
            "カスタム Git フックまたはフィルターの設定があります。")
    }

    private fun checkLocation(directory: File) {
        requireGit(sharedFolders.isRepositoryPath(directory),
            "共有ストレージ内の通常のフォルダを指定してください。")
    }

    private fun checkEmptyDestination(directory: File) {
        requireGit(directory.canonicalFile == directory &&
            (Files.notExists(directory.toPath(), NOFOLLOW_LINKS) ||
                directory.isDirectory && directory.list()?.isEmpty() == true),
            "clone 先には空のフォルダを指定してください。")
    }

    private fun normalizeUrl(value: String): String {
        val url = value.trim()
        requireGit(url.matches(Regex("""https://github[.]com/[A-Za-z0-9-]+/[A-Za-z0-9_.-]+""")),
            "https://github.com/所有者/リポジトリ の形式で指定してください。")
        return if (url.endsWith(".git")) url else "$url.git"
    }

    private fun fetchRemote(git: Git, config: RepositoryConfig, token: String) =
        GitRemote.fetch(git, config.remoteUrl, config.branchRef, config.trackingRef,
            githubTransport(config.remoteUrl, token, allowAnonymous = true))

    private fun isAncestor(repo: Repository, ancestor: ObjectId, descendant: ObjectId): Boolean =
        RevWalk(repo).use { it.isMergedInto(it.parseCommit(ancestor), it.parseCommit(descendant)) }

    private val RepositoryConfig.branchRef get() = Constants.R_HEADS + branch
    private val RepositoryConfig.trackingRef get() = Constants.R_REMOTES + "origin/" + branch
}
