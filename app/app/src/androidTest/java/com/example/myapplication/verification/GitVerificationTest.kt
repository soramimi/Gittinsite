package com.example.myapplication.verification

import android.os.Build
import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.git.GitRemote
import com.example.myapplication.git.GitWorkTree
import com.example.myapplication.git.requireGit as verify
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class GitVerificationTest {
    @Test
    fun localRepositoryRoundTripWorksOnAndroid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        localRoundTrip(context.filesDir)
    }

    @Suppress("DEPRECATION")
    @Test
    fun sharedDocumentsRepositoryRoundTripWorksWithPermission() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("verifySharedStorage") == "true")
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager())
        val documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        check(documents.isDirectory || documents.mkdirs())
        localRoundTrip(documents.canonicalFile)
    }

    @Test
    fun stagingAddsEditsAndDeletionsAndPreservesUntrackedFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = Files.createTempDirectory(context.filesDir.toPath(), "verification-stage-").toFile()
        try {
            Git.init().setDirectory(repository).call().use { git ->
                val tracked = File(repository, "M0.md").apply { writeText("original") }
                val deleted = File(repository, "removed.md").apply { writeText("original") }
                val newFile = File(repository, "new.md")
                val settings = File(repository, ".obsidian/workspace.json")
                git.add().addFilepattern(tracked.name).addFilepattern(deleted.name).call()
                git.commit().setMessage("Initial").setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com").call()

                tracked.writeText("edited")
                deleted.delete()
                newFile.writeText("new")
                settings.parentFile?.mkdir()
                settings.writeText("{}")
                GitWorkTree.stage(git, setOf(tracked.name, deleted.name, newFile.name))
                val status = GitWorkTree.changes(git.status().call())
                assertEquals(setOf(tracked.name, deleted.name, newFile.name), status.staged)
                assertEquals(setOf(".obsidian/workspace.json"), status.untracked)
            }
        } finally {
            repository.deleteRecursively()
        }
    }

    private fun localRoundTrip(parent: File) {
        val root = Files.createTempDirectory(parent.toPath(), "jgit-").toFile()
        val remote = File(root, "remote.git")
        val remoteUrl = remote.toURI().toString()
        val branchRef = Constants.R_HEADS + LOCAL_BRANCH
        val trackingRef = Constants.R_REMOTES + "origin/" + LOCAL_BRANCH

        try {
            Git.init().setDirectory(remote).setBare(true).setInitialBranch(LOCAL_BRANCH).call().use {
                verify(it.repository.isBare, "テスト用 bare リポジトリを作成できませんでした。")
            }
            Git.init().setDirectory(File(root, "seed")).setInitialBranch(LOCAL_BRANCH).call().use { seed ->
                File(seed.repository.workTree, NOTE).writeText("# M0\n初期内容\n")
                seed.add().addFilepattern(NOTE).call()
                commit(seed)
                GitRemote.push(seed, remoteUrl, branchRef)
            }
            clone(File(root, "android"), remoteUrl, branchRef).call().use { android ->
                clone(File(root, "pc"), remoteUrl, branchRef).call().use { pc ->
                    val androidNote = File(android.repository.workTree, NOTE)
                    val pcNote = File(pc.repository.workTree, NOTE)
                    verify(androidNote.readText().contains("初期内容"), "clone の内容が一致しません。")
                    androidNote.appendText("Android で編集\n")
                    val obsidianSettings = File(android.repository.workTree, ".obsidian/workspace.json")
                    obsidianSettings.parentFile?.mkdir()
                    obsidianSettings.writeText("{}")
                    GitWorkTree.stage(android, setOf(NOTE))
                    commit(android)
                    GitRemote.push(android, remoteUrl, branchRef, expectedRemote = pc.repository.resolve(Constants.HEAD))
                    GitRemote.fastForward(pc, GitRemote.fetch(pc, remoteUrl, branchRef, trackingRef))
                    verify(pcNote.readText() == androidNote.readText(), "push 後の内容が一致しません。")
                    verify(!File(pc.repository.workTree, ".obsidian").exists(), "未ステージの設定が送信されました。")
                    pcNote.appendText("PC で編集\n")
                    pc.add().addFilepattern(NOTE).call()
                    commit(pc)
                    GitRemote.push(pc, remoteUrl, branchRef)
                    GitRemote.fastForward(android, GitRemote.fetch(android, remoteUrl, branchRef, trackingRef))
                    verify(androidNote.readText() == pcNote.readText(), "fast-forward 後の内容が一致しません。")
                    verify(obsidianSettings.readText() == "{}", "未追跡の設定が変わりました。")
                }
            }
        } finally {
            // この処理が作ったテスト用の一時ディレクトリを片付ける。
            root.deleteRecursively()
        }
    }

    private fun clone(directory: File, url: String, branchRef: String) = Git.cloneRepository()
        .setURI(url)
        .setDirectory(directory)
        .setBranch(branchRef)
        .setBranchesToClone(listOf(branchRef))
        .setNoTags()
        .setTimeout(GitRemote.TIMEOUT_SECONDS)

    private fun commit(git: Git) {
        git.commit()
            .setAuthor("Gittinsite Test", "test@example.com")
            .setCommitter("Gittinsite Test", "test@example.com")
            .setMessage("Test Git round trip")
            .call()
    }

    private companion object {
        const val NOTE = "M0.md"
        const val LOCAL_BRANCH = "m0"
    }
}
