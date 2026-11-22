package com.example.myapplication.git

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private val Context.repositoryPreferences by preferencesDataStore("repositories")

internal class RepositoryStore(context: Context) {
    private val dataStore = context.applicationContext.repositoryPreferences
    private val key = stringPreferencesKey("repositories")
    val repositories = dataStore.data.map { decode(it[key]) }

    suspend fun add(config: RepositoryConfig) {
        dataStore.edit { preferences ->
            val repositories = decode(preferences[key])
            checkAvailable(config.directory, repositories)
            preferences[key] = encode(repositories + config)
        }
    }

    suspend fun remove(id: String) {
        dataStore.edit { preferences ->
            preferences[key] = encode(decode(preferences[key]).filterNot { it.id == id })
        }
    }

    fun checkAvailable(directory: File, repositories: List<RepositoryConfig>) {
        requireGit(repositories.none {
            GitWorkTree.pathsOverlap(directory.path, it.directory.canonicalPath)
        }, "すでに登録しているフォルダと重複しています。")
    }

    private fun decode(value: String?): List<RepositoryConfig> {
        val array = JSONArray(value ?: "[]")
        return List(array.length()) { index ->
            val objectValue = array.getJSONObject(index)
            RepositoryConfig(
                id = objectValue.getString("id"),
                name = objectValue.getString("name"),
                workingDirectory = objectValue.getString("directory"),
                remoteUrl = objectValue.getString("remoteUrl"),
                branch = objectValue.getString("branch"),
                authorName = objectValue.getString("authorName"),
                authorEmail = objectValue.getString("authorEmail"),
            )
        }
    }

    private fun encode(repositories: List<RepositoryConfig>): String =
        JSONArray().apply {
            repositories.forEach { config ->
                put(JSONObject().apply {
                    put("id", config.id)
                    put("name", config.name)
                    put("directory", config.workingDirectory)
                    put("remoteUrl", config.remoteUrl)
                    put("branch", config.branch)
                    put("authorName", config.authorName)
                    put("authorEmail", config.authorEmail)
                })
            }
        }.toString()
}
