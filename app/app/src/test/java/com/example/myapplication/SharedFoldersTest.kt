package com.example.myapplication

import com.example.myapplication.storage.SharedFolders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class SharedFoldersTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun listsAllowedDirectoriesAndStartsAtNearestExistingParent() {
        val root = temporary.newFolder("shared").canonicalFile
        val vaults = File(root, "Documents").apply { mkdir() }
        val notes = File(vaults, "日本語 Vault").apply { mkdir() }
        File(vaults, ".git").mkdir()
        File(vaults, "settings.txt").writeText("file")
        File(root, "Android").mkdir()
        val folders = SharedFolders(root)

        val primary = folders.list(null, "")
        assertEquals(listOf(vaults), primary.folders)
        assertFalse(primary.selectable)
        val listing = folders.list(null, File(vaults, "Not yet created").path)
        assertEquals(vaults, listing.directory)
        assertEquals(root, listing.parent)
        assertEquals(listOf(notes), listing.folders)
        assertTrue(folders.list(notes, "").selectable)
        assertFalse(folders.isRepositoryPath(File(vaults, ".git")))
    }

    @Test
    fun excludesDirectoryLinksAndKeepsNavigationInsideSharedRoot() {
        val root = temporary.newFolder("shared").canonicalFile
        val outside = temporary.newFolder("outside").canonicalFile
        val link = File(root, "alias")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        val folders = SharedFolders(root)

        assertEquals(root, folders.list(null, link.path).directory)
        assertTrue(folders.list(null, "").folders.isEmpty())
        assertThrows(IOException::class.java) { folders.list(link, "") }
        assertFalse(folders.isRepositoryPath(outside))
    }
}
