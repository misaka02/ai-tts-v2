package com.aitts.v2.engine.cloud

import kotlinx.serialization.Serializable

enum class SyncDeleteSide {
    LOCAL,
    REMOTE
}

@Serializable
data class SyncManifest(
    val syncedFileNames: Set<String> = emptySet(),
    val updatedAt: Long = 0L
)

data class SyncDeleteCandidate(
    val id: String,
    val name: String,
    val path: String,
    val side: SyncDeleteSide,
    val sizeBytes: Long,
    val modifiedTimeMs: Long = 0L,
    val reason: String
)

data class SyncFileAction(
    val node: CloudFileNode
)

data class SyncPlan(
    val providerLabel: String,
    val remoteFolderIdOrPath: String,
    val remoteFolderLabel: String,
    val direction: SyncDirection,
    val toPull: List<SyncFileAction> = emptyList(),
    val toPush: List<SyncFileAction> = emptyList(),
    val pendingLocalDeletes: List<SyncDeleteCandidate> = emptyList(),
    val pendingRemoteDeletes: List<SyncDeleteCandidate> = emptyList(),
    val stateNode: CloudFileNode? = null
) {
    val hasDeletions: Boolean
        get() = pendingLocalDeletes.isNotEmpty() || pendingRemoteDeletes.isNotEmpty()
}

/**
 * Computes two-phase sync plans so deletions are strictly isolated into explicit
 * `pendingLocalDeletes` / `pendingRemoteDeletes` lists and never run automatically.
 */
object SyncPlanBuilder {

    fun build(
        providerLabel: String,
        remoteFolderIdOrPath: String,
        remoteFolderLabel: String,
        direction: SyncDirection,
        localFiles: List<CloudFileNode>,
        remoteFiles: List<CloudFileNode>,
        previousSyncedNames: Set<String>,
        stateNode: CloudFileNode? = null
    ): SyncPlan {
        val normalizedManifest = previousSyncedNames.map { it.lowercase() }.toSet()
        val localByName = localFiles.associateBy { it.name.lowercase() }
        val remoteByName = remoteFiles.associateBy { it.name.lowercase() }

        val toPull = mutableListOf<SyncFileAction>()
        val toPush = mutableListOf<SyncFileAction>()
        val pendingLocalDeletes = mutableListOf<SyncDeleteCandidate>()
        val pendingRemoteDeletes = mutableListOf<SyncDeleteCandidate>()

        for ((key, remote) in remoteByName) {
            if (key in localByName) continue
            val wasPreviouslySynced = key in normalizedManifest
            if (wasPreviouslySynced && direction != SyncDirection.PULL) {
                pendingRemoteDeletes.add(
                    SyncDeleteCandidate(
                        id = remote.id,
                        name = remote.name,
                        path = remote.path,
                        side = SyncDeleteSide.REMOTE,
                        sizeBytes = remote.sizeBytes,
                        modifiedTimeMs = remote.modifiedTimeMs,
                        reason = "本地同步目录已移除此文件，勾选确认后将移入云端回收站"
                    )
                )
            } else if (direction != SyncDirection.PUSH) {
                toPull.add(SyncFileAction(remote))
            }
        }

        for ((key, local) in localByName) {
            if (key in remoteByName) continue
            val wasPreviouslySynced = key in normalizedManifest
            if (wasPreviouslySynced && direction != SyncDirection.PUSH) {
                pendingLocalDeletes.add(
                    SyncDeleteCandidate(
                        id = local.id,
                        name = local.name,
                        path = local.path,
                        side = SyncDeleteSide.LOCAL,
                        sizeBytes = local.sizeBytes,
                        modifiedTimeMs = local.modifiedTimeMs,
                        reason = "云端同步目录已移除此文件，勾选确认后将删除本地同步副本"
                    )
                )
            } else if (direction != SyncDirection.PULL) {
                toPush.add(SyncFileAction(local))
            }
        }

        return SyncPlan(
            providerLabel = providerLabel,
            remoteFolderIdOrPath = remoteFolderIdOrPath,
            remoteFolderLabel = remoteFolderLabel,
            direction = direction,
            toPull = toPull,
            toPush = toPush,
            pendingLocalDeletes = pendingLocalDeletes,
            pendingRemoteDeletes = pendingRemoteDeletes,
            stateNode = stateNode
        )
    }

    fun computeNextManifestNames(
        plan: SyncPlan,
        finalLocalNames: Set<String>,
        finalRemoteNames: Set<String>,
        approvedLocalIds: Set<String>,
        approvedRemoteIds: Set<String>
    ): Set<String> {
        val synced = (finalLocalNames.map { it.lowercase() }.toSet() intersect finalRemoteNames.map { it.lowercase() }.toSet())
        val declinedLocal = plan.pendingLocalDeletes
            .filter { it.id !in approvedLocalIds && it.name.lowercase() in finalLocalNames.map { n -> n.lowercase() } }
            .map { it.name.lowercase() }
        val declinedRemote = plan.pendingRemoteDeletes
            .filter { it.id !in approvedRemoteIds && it.name.lowercase() in finalRemoteNames.map { n -> n.lowercase() } }
            .map { it.name.lowercase() }
        return synced + declinedLocal + declinedRemote
    }
}
