package com.example.myapplication.git

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand.FastForwardMode
import org.eclipse.jgit.api.MergeResult.MergeStatus
import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.SubmoduleConfig.FetchRecurseSubmodulesMode
import org.eclipse.jgit.transport.RefLeaseSpec
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.TagOpt

internal object GitRemote {
    const val TIMEOUT_SECONDS = 20

    fun fetch(
        git: Git,
        url: String,
        branchRef: String,
        trackingRef: String,
        callback: TransportConfigCallback? = null,
    ): ObjectId {
        val result = git.fetch().setRemote(url).setRefSpecs(RefSpec("$branchRef:$trackingRef"))
            .setRecurseSubmodules(FetchRecurseSubmodulesMode.NO)
            .setTagOpt(TagOpt.NO_TAGS).setTimeout(TIMEOUT_SECONDS)
            .setTransportConfigCallback(callback).call()
        val remote = result.getAdvertisedRef(branchRef)?.objectId
            ?: throw GitOperationException("リモートに指定したブランチがありません。")
        val update = result.getTrackingRefUpdate(trackingRef)
        requireGit((update == null || update.result in setOf(RefUpdate.Result.NEW, RefUpdate.Result.NO_CHANGE,
            RefUpdate.Result.FAST_FORWARD)) && git.repository.resolve(trackingRef) == remote,
            "リモート追跡ブランチを安全に更新できませんでした。")
        return remote
    }

    fun push(
        git: Git,
        url: String,
        branchRef: String,
        callback: TransportConfigCallback? = null,
        expectedRemote: ObjectId? = null,
    ) {
        val command = git.push().setRemote(url).setRefSpecs(RefSpec("$branchRef:$branchRef"))
            .setForce(false).setTimeout(TIMEOUT_SECONDS).setTransportConfigCallback(callback)
        if (expectedRemote != null) {
            command.setRefLeaseSpecs(RefLeaseSpec(branchRef, expectedRemote.name()))
        }
        val updates = command.call().flatMap { it.remoteUpdates }
        val update = updates.singleOrNull { it.remoteName == branchRef }
        requireGit(update?.status in setOf(RemoteRefUpdate.Status.OK, RemoteRefUpdate.Status.UP_TO_DATE),
            "push が拒否されました（${update?.status?.name ?: "結果不明"}）。ローカルの変更は保持します。")
    }

    fun fastForward(git: Git, remote: ObjectId) {
        val result = git.merge().include(remote).setFastForward(FastForwardMode.FF_ONLY).call()
        requireGit(result.mergeStatus == MergeStatus.FAST_FORWARD || result.mergeStatus == MergeStatus.ALREADY_UP_TO_DATE,
            "fast-forward できません（${result.mergeStatus.name}）。")
    }
}
