package com.example.myapplication.git

import java.io.File

internal data class RepositoryConfig(
    val id: String,
    val name: String,
    val workingDirectory: String,
    val remoteUrl: String,
    val branch: String,
    val authorName: String,
    val authorEmail: String,
) {
    val directory get() = File(workingDirectory)
}
