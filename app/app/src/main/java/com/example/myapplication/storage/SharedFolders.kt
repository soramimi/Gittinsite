package com.example.myapplication.storage

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

internal data class SharedFolderListing(
    val directory: File,
    val parent: File?,
    val folders: List<File>,
    val selectable: Boolean,
)

internal class SharedFolders(private val sharedRoot: File) {
    fun isRepositoryPath(directory: File): Boolean {
        val root = sharedRoot.canonicalFile
        return directory != root && isInside(root, directory)
    }

    fun list(requested: File?, initialPath: String): SharedFolderListing {
        val root = sharedRoot.canonicalFile
        val directory = requested ?: initialDirectory(root, initialPath)
        if (!isInside(root, directory)) throw IOException("共有ストレージ内のフォルダを選んでください。")
        val children = directory.listFiles() ?: throw IOException("フォルダを読み込めませんでした。")
        val folders = children.filter {
            Files.isDirectory(it.toPath(), NOFOLLOW_LINKS) && it.canRead() && isInside(root, it)
        }.sortedBy { it.name.lowercase() }
        return SharedFolderListing(directory, if (directory == root) null else directory.parentFile,
            folders, directory != root && directory.canWrite())
    }

    private fun initialDirectory(root: File, path: String): File {
        val file = File(path.trim())
        if (!file.isAbsolute) return root
        val canonical = runCatching { file.canonicalFile }.getOrElse { return root }
        if (!isInside(root, canonical)) return root
        return generateSequence(canonical) { it.parentFile }
            .firstOrNull { it.isDirectory && it.canRead() && isInside(root, it) } ?: root
    }

    private fun isInside(root: File, directory: File): Boolean {
        val rootPath = root.toPath()
        val candidate = directory.toPath()
        return candidate.startsWith(rootPath) && !candidate.startsWith(rootPath.resolve("Android")) &&
            rootPath.relativize(candidate).none { it.toString() == ".git" } && directory.canonicalFile == directory
    }
}
