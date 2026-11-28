package com.aitts.v2.engine.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlanBuilderTest {

    @Test
    fun `first sync is purely additive with no deletions`() {
        val local = listOf(CloudFileNode("l1", "A.epub", "A.epub", false, 100L))
        val remote = listOf(CloudFileNode("r1", "B.epub", "r1", false, 200L))

        val plan = SyncPlanBuilder.build(
            providerLabel = "Google Drive",
            remoteFolderIdOrPath = "root",
            remoteFolderLabel = "AI-TTS-Library",
            direction = SyncDirection.TWO_WAY,
            localFiles = local,
            remoteFiles = remote,
            previousSyncedNames = emptySet()
        )

        assertFalse(plan.hasDeletions)
        assertEquals(listOf("B.epub"), plan.toPull.map { it.node.name })
        assertEquals(listOf("A.epub"), plan.toPush.map { it.node.name })
    }

    @Test
    fun `locally deleted file after sync emits pendingRemoteDelete for confirmation`() {
        val remote = listOf(
            CloudFileNode("r1", "SyncedGoneLocally.epub", "r1", false, 500L),
            CloudFileNode("r2", "NewRemote.epub", "r2", false, 600L)
        )

        val plan = SyncPlanBuilder.build(
            providerLabel = "Google Drive",
            remoteFolderIdOrPath = "folder1",
            remoteFolderLabel = "Books",
            direction = SyncDirection.TWO_WAY,
            localFiles = emptyList(),
            remoteFiles = remote,
            previousSyncedNames = setOf("syncedgonelocally.epub")
        )

        assertTrue(plan.hasDeletions)
        assertEquals(listOf("NewRemote.epub"), plan.toPull.map { it.node.name })
        assertEquals(listOf("SyncedGoneLocally.epub"), plan.pendingRemoteDeletes.map { it.name })
        assertTrue(plan.pendingLocalDeletes.isEmpty())
    }

    @Test
    fun `remotely deleted file after sync emits pendingLocalDelete only when pulling or two way`() {
        val local = listOf(CloudFileNode("l1", "GoneRemotely.epub", "GoneRemotely.epub", false, 300L))

        val twoWay = SyncPlanBuilder.build(
            providerLabel = "WebDAV",
            remoteFolderIdOrPath = "Books",
            remoteFolderLabel = "Books",
            direction = SyncDirection.TWO_WAY,
            localFiles = local,
            remoteFiles = emptyList(),
            previousSyncedNames = setOf("goneremotely.epub")
        )
        assertTrue(twoWay.hasDeletions)
        assertEquals(listOf("GoneRemotely.epub"), twoWay.pendingLocalDeletes.map { it.name })
        assertTrue(twoWay.toPush.isEmpty())

        val pushOnly = SyncPlanBuilder.build(
            providerLabel = "WebDAV",
            remoteFolderIdOrPath = "Books",
            remoteFolderLabel = "Books",
            direction = SyncDirection.PUSH,
            localFiles = local,
            remoteFiles = emptyList(),
            previousSyncedNames = setOf("goneremotely.epub")
        )
        assertFalse(pushOnly.hasDeletions)
        assertEquals(listOf("GoneRemotely.epub"), pushOnly.toPush.map { it.node.name })
    }

    @Test
    fun `declined deletion stays in manifest so next sync does not pull it back`() {
        val remote = listOf(CloudFileNode("r1", "DeletedLocally.epub", "r1", false, 500L))
        val plan = SyncPlanBuilder.build(
            providerLabel = "Google Drive",
            remoteFolderIdOrPath = "folder1",
            remoteFolderLabel = "Books",
            direction = SyncDirection.TWO_WAY,
            localFiles = emptyList(),
            remoteFiles = remote,
            previousSyncedNames = setOf("deletedlocally.epub")
        )
        val nextManifest = SyncPlanBuilder.computeNextManifestNames(
            plan = plan,
            finalLocalNames = emptySet(),
            finalRemoteNames = setOf("DeletedLocally.epub"),
            approvedLocalIds = emptySet(),
            approvedRemoteIds = emptySet()
        )
        assertTrue("deletedlocally.epub" in nextManifest)

        val nextPlan = SyncPlanBuilder.build(
            providerLabel = "Google Drive",
            remoteFolderIdOrPath = "folder1",
            remoteFolderLabel = "Books",
            direction = SyncDirection.TWO_WAY,
            localFiles = emptyList(),
            remoteFiles = remote,
            previousSyncedNames = nextManifest
        )
        assertTrue(nextPlan.toPull.isEmpty())
        assertEquals(listOf("DeletedLocally.epub"), nextPlan.pendingRemoteDeletes.map { it.name })
    }
}
