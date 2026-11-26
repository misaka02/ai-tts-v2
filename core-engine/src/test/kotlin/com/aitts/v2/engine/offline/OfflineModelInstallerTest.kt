package com.aitts.v2.engine.offline

import com.aitts.v2.engine.network.HttpClientManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OfflineModelInstallerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `extractArchiveStrict blocks ZipSlip traversal`() {
        val installer = OfflineModelInstaller(tempFolder.newFolder("models"), HttpClientManager())
        val evilZip = tempFolder.newFile("evil.zip")
        ZipOutputStream(FileOutputStream(evilZip)).use { zos ->
            zos.putNextEntry(ZipEntry("../../evil.txt"))
            zos.write("bad".toByteArray())
            zos.closeEntry()
        }

        val outDir = tempFolder.newFolder("out")
        var caught = false
        try {
            installer.extractArchiveStrict(evilZip, outDir)
        } catch (_: SecurityException) {
            caught = true
        }
        assertTrue(caught)
    }

    @Test
    fun `isModelInstalled rejects directory missing tokens or onnx even if ready flag exists`() {
        val root = tempFolder.newFolder("models2")
        val installer = OfflineModelInstaller(root, HttpClientManager())
        val modelDir = File(root, "fake-model").apply { mkdirs() }
        File(modelDir, OfflineModelInstaller.READY_FLAG).writeText("READY_V2")

        assertFalse(installer.isModelInstalled("fake-model"))

        File(modelDir, "model.onnx").writeBytes(byteArrayOf(1, 2, 3))
        assertFalse(installer.isModelInstalled("fake-model"))

        File(modelDir, "tokens.txt").writeText("a 0\n")
        assertTrue(installer.isModelInstalled("fake-model"))
    }
}
