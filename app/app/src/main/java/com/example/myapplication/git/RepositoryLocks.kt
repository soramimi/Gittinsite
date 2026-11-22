package com.example.myapplication.git

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

internal object RepositoryLocks {
    private val locks = ConcurrentHashMap<File, ReentrantLock>()

    fun <T> withLock(directory: File, action: () -> T): T {
        val lock = locks.computeIfAbsent(directory.canonicalFile) { ReentrantLock() }
        requireGit(lock.tryLock(), "このリポジトリは別の操作を実行中です。")
        try {
            return action()
        } finally {
            lock.unlock()
        }
    }
}
