package com.example.myapplication.git

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.Status
import org.eclipse.jgit.dircache.DirCacheCheckout
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest

internal class GitOperationException(message: String) : Exception(message)

internal fun requireGit(condition: Boolean, message: String) {
    if (!condition) throw GitOperationException(message)
}

internal data class GitChanges(
    val staged: Set<String>,
    val unstaged: Set<String>,
    val untracked: Set<String>,
) {
    val stageable: Set<String> get() = unstaged + untracked
    val discardable: Set<String> get() = staged + unstaged
}

internal data class ResetPreview(
    val head: ObjectId,
    val paths: List<String>,
    val indexDigest: List<Byte>?,
    val fileDigests: List<List<Byte>?>,
)

internal object GitWorkTree {
    fun changes(status: Status) = GitChanges(
        staged = status.added + status.changed + status.removed,
        unstaged = status.modified + status.missing,
        untracked = status.untracked,
    )

    fun stage(git: Git, paths: Set<String>) {
        val status = git.status().call()
        requireGit(paths.isNotEmpty() && changes(status).stageable.containsAll(paths),
            "変更一覧が変わりました。ステージするファイルを選び直してください。")
        val (deleted, present) = paths.partition { it in status.missing }
        paths.forEach { checkedFile(git, it) }
        if (present.isNotEmpty()) {
            git.add().apply { present.forEach { addFilepattern(it) } }.call()
        }
        if (deleted.isNotEmpty()) {
            git.add().setUpdate(true).apply { deleted.forEach { addFilepattern(it) } }.call()
        }
    }

    fun previewReset(git: Git): ResetPreview {
        val repository = git.repository
        val head = repository.resolve(Constants.HEAD)
        val status = git.status().call()
        val changed = changes(status).discardable.sorted()
        requireGit(changed.isNotEmpty(), "破棄する追跡済み・ステージ済みの変更はありません。")

        val index = repository.readDirCache()
        val paths = mutableSetOf<String>()
        for (position in 0 until index.entryCount) {
            val entry = index.getEntry(position)
            requireRegularMode(entry.fileMode)
            paths.add(entry.pathString)
        }
        RevWalk(repository).use { walk ->
            TreeWalk(repository).use { tree ->
                tree.addTree(walk.parseCommit(head).tree)
                tree.isRecursive = true
                while (tree.next()) {
                    requireRegularMode(tree.getFileMode(0))
                    paths.add(tree.pathString)
                }
            }
        }

        val extraFiles = status.untracked + status.ignoredNotInIndex
        requireGit(paths.none { path -> extraFiles.any { pathsOverlap(path, it, ignoreCase = true) } },
            "復元先に未追跡または無視対象のファイルがあるため、破棄を中止しました。")
        paths.forEach { checkedFile(git, it) }
        return ResetPreview(head, changed, digest(repository.indexFile), changed.map { digest(checkedFile(git, it)) })
    }

    fun resetToHead(git: Git, preview: ResetPreview) {
        val repository = git.repository
        val index = repository.lockDirCache()
        try {
            requireGit(previewReset(git) == preview && repository.resolve(Constants.HEAD) == preview.head,
                "確認後に変更がありました。破棄する内容を確認し直してください。")
            val tree = RevWalk(repository).use { it.parseCommit(preview.head).tree.id }
            // hard reset と同じ方法で作業ツリーとインデックスを戻し、ブランチの参照は書き換えない。
            val checkout = DirCacheCheckout(repository, index, tree)
            checkout.setFailOnConflict(false)
            requireGit(checkout.checkout(), "一部のファイルを HEAD に戻せませんでした。")
        } finally {
            index.unlock()
        }
    }

    fun pathsOverlap(left: String, right: String, ignoreCase: Boolean = false): Boolean =
        left.equals(right, ignoreCase) || left.startsWith("$right/", ignoreCase) || right.startsWith("$left/", ignoreCase)

    fun checkedFile(git: Git, path: String): File {
        val root = git.repository.workTree.canonicalFile
        val file = File(root, path)
        requireGit(file != root && file.toPath().startsWith(root.toPath()) && file.canonicalFile == file &&
            (Files.notExists(file.toPath(), NOFOLLOW_LINKS) || Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)),
            "通常ファイルとして扱えないパスがあります。")
        return file
    }

    private fun requireRegularMode(mode: FileMode) {
        requireGit(mode == FileMode.REGULAR_FILE || mode == FileMode.EXECUTABLE_FILE,
            "シンボリックリンクなど、通常ファイル以外を含むため破棄を中止しました。")
    }

    private fun digest(file: File): List<Byte>? {
        if (Files.notExists(file.toPath(), NOFOLLOW_LINKS)) return null
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toList()
    }
}
