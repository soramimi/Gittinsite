package com.example.myapplication

import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.git.GitOperationException
import com.example.myapplication.git.JGitRepositoryService
import com.example.myapplication.git.RepositoryStore
import com.example.myapplication.storage.SharedFolders
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand.ResetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class RepositoryServiceTest {
    @Test
    fun registeredRepositoryStagesOnlyRequestedChangesAndUnregisterKeepsFiles() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val parent = Files.createTempDirectory(context.filesDir.canonicalFile.toPath(), "m1-local-").toFile()
        val workTree = File(parent, "repository")
        val service = JGitRepositoryService(SharedFolders(parent))
        val store = RepositoryStore(context)
        val config = service.config(
            "Test", workTree.path, "https://github.com/octocat/Hello-World", "main", "Test", "test@example.com",
        )
        try {
            Git.init().setDirectory(workTree).setInitialBranch("main").call().use { git ->
                val file = File(workTree, "test.md")
                file.writeText("first")
                git.add().addFilepattern(file.name).call()
                git.commit().setMessage("initial").setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com").call()
                git.repository.config.apply {
                    setString("remote", "origin", "url", config.remoteUrl)
                    save()
                }
            }
            service.register(config)
            store.add(config)
            assertTrue(config in store.repositories.first())
            assertThrows(GitOperationException::class.java) {
                store.checkAvailable(File(workTree, "nested"), listOf(config))
            }
            assertThrows(GitOperationException::class.java) {
                service.register(config.copy(branch = "other"))
            }
            assertThrows(GitOperationException::class.java) {
                service.register(config.copy(remoteUrl = "https://github.com/octocat/Spoon-Knife.git"))
            }
            File(workTree, "test.md").writeText("edited")
            File(workTree, "extra.md").writeText("untracked")
            assertEquals(setOf("test.md", "extra.md"), service.status(config).stageable)
            service.stage(config, setOf("test.md"))
            assertEquals(setOf("test.md"), service.status(config).staged)
            service.commit(config, "manual commit")
            assertEquals(setOf("extra.md"), service.status(config).untracked)
            assertTrue(service.status(config).staged.isEmpty())
            store.remove(config.id)
            assertFalse(config in store.repositories.first())
            assertEquals("edited", File(workTree, "test.md").readText())
        } finally {
            store.remove(config.id)
            parent.deleteRecursively()
        }
    }

    @Test
    fun resetRequiresFreshPreviewAndPreservesUntrackedFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val parent = Files.createTempDirectory(context.filesDir.canonicalFile.toPath(), "reset-").toFile()
        val workTree = File(parent, "repository")
        val service = JGitRepositoryService(SharedFolders(parent))
        val config = service.config(
            "Test", workTree.path, "https://github.com/octocat/Hello-World", "main", "Test", "test@example.com",
        )
        try {
            val note = File(workTree, "note.md")
            val removed = File(workTree, "removed.md")
            val added = File(workTree, "added.md")
            val untracked = File(workTree, "keep.md")
            val head = Git.init().setDirectory(workTree).setInitialBranch("main").call().use { git ->
                note.writeText("original")
                removed.writeText("restore")
                git.add().addFilepattern(note.name).addFilepattern(removed.name).call()
                git.repository.config.apply {
                    setString("remote", "origin", "url", config.remoteUrl)
                    save()
                }
                git.commit().setMessage("initial").setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com").call().id
            }
            note.writeText("edited")
            check(removed.delete())
            added.writeText("staged")
            untracked.writeText("keep")
            service.stage(config, setOf(added.name, removed.name))
            val preview = service.previewReset(config)
            assertEquals(setOf(note.name, removed.name, added.name), preview.paths.toSet())
            note.writeText("edited again")
            assertThrows(GitOperationException::class.java) { service.resetToHead(config, preview) }
            assertEquals("edited again", note.readText())

            val status = service.resetToHead(config, service.previewReset(config))
            assertEquals("original", note.readText())
            assertEquals("restore", removed.readText())
            assertFalse(added.exists())
            assertEquals("keep", untracked.readText())
            assertTrue(status.discardable.isEmpty())
            assertEquals(setOf(untracked.name), status.untracked)
            Git.open(workTree).use { assertEquals(head, it.repository.resolve("HEAD")) }

            check(note.delete())
            check(note.mkdir())
            val obstruction = File(note, "child.md").apply { writeText("keep this too") }
            assertThrows(GitOperationException::class.java) { service.previewReset(config) }
            assertEquals("keep this too", obstruction.readText())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun publicCloneFetchAndFastForwardOnSharedStorage() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyPublicGitHub") == "true")
        check(Environment.isExternalStorageManager())
        val root = Environment.getExternalStorageDirectory().canonicalFile
        val documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).canonicalFile
        check(documents.isDirectory || documents.mkdirs())
        val parent = Files.createTempDirectory(documents.toPath(), "m1-network-").toFile()
        val destination = File(parent, "public").apply { check(mkdir()) }
        val service = JGitRepositoryService(SharedFolders(root))
        val config = service.config(
            "Public", destination.path, "https://github.com/octocat/Hello-World", "master", "Test", "test@example.com",
        )
        try {
            service.clone(config, "")
            val head = Git.open(destination).use { git ->
                val id = git.repository.resolve("HEAD")
                val parentId = git.repository.resolve("HEAD~1")
                // このテスト専用の clone だけを一つ前のコミットに戻す。
                git.reset().setRef(parentId.name()).setMode(ResetType.HARD).call()
                id
            }
            assertEquals(head.abbreviate(8).name(), service.fetch(config, ""))
            val note = File(destination, "README")
            val contents = note.readBytes()
            note.writeText("local edit")
            assertThrows(GitOperationException::class.java) { service.fastForward(config) }
            assertEquals("local edit", note.readText())
            note.writeBytes(contents)
            service.fastForward(config)
            Git.open(destination).use { git -> assertEquals(head, git.repository.resolve("HEAD")) }
        } finally {
            parent.deleteRecursively()
        }
    }
}
