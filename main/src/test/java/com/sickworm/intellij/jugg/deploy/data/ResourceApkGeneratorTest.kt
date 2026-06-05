package com.sickworm.intellij.jugg.deploy.data

import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.compiler.ClassNode
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.FieldNode
import com.sickworm.intellij.jugg.compiler.MethodNode
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.mock.logger
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream

class ResourceApkGeneratorTest {

    @Test
    fun testResourceApkKeepsOnlyCurrentApkResourcesWhenFirstCreated() {
        val testRoot = Files.createTempDirectory("resource-apk-generator-test").toFile()
        val baseApkPath = File(testRoot, "base.apk").path
        val splitApkPath = File(testRoot, "split.apk").path
        val changedOverlay = DeployItem(
            name = "resources.arsc",
            type = CompileOutput.Type.Res,
            checksum = 1,
            content = "changed".toByteArray(),
            apkPath = baseApkPath,
            targetApkPaths = listOf(splitApkPath),
        )
        val database = RecordingDeployDataDatabase(
            fullResources = listOf(
                DeployItem("resources.arsc", CompileOutput.Type.Res, 2, "base-history".toByteArray(), baseApkPath),
                DeployItem("resources.arsc", CompileOutput.Type.Res, 3, "split-history".toByteArray(), splitApkPath),
            )
        )
        val generator = ResourceApkGenerator(
            deployDataDatabase = database,
            resourceApkDir = File(testRoot, "resource_apks"),
            logger = logger,
        )

        val deployItems = generator.getResourceApkDeployItem(
            changedOverlays = listOf(changedOverlay),
            notStagingDeployedFiles = listOf(
                createOutput(testRoot, "base_history", baseApkPath, "base-history"),
                createOutput(testRoot, "split_history", splitApkPath, "split-history"),
            ),
        )

        assertEquals(listOf(baseApkPath, splitApkPath), deployItems.map { it.apkPath }.sorted())
        deployItems.forEach { resourceApk ->
            assertEquals("changed", unzipSingleEntry(resourceApk.content, "resources.arsc"))
        }
        database.addFullResInputs.forEach { inputItems ->
            assertEquals(listOf("resources.arsc"), inputItems.map { it.name })
        }
    }

    private fun createOutput(testRoot: File, dirName: String, apkPath: String, content: String): CompileOutput {
        val baseDir = File(testRoot, dirName)
        val file = File(baseDir, "resources.arsc")
        file.parentFile.mkdirs()
        file.writeText(content)
        return CompileOutput(
            type = CompileOutput.Type.Res,
            file = file,
            baseDir = baseDir,
            apkPath = apkPath,
        )
    }

    private fun unzipSingleEntry(content: ByteArray, entryName: String): String {
        var result: String? = null
        ZipInputStream(ByteArrayInputStream(content)).use { zipInputStream ->
            while (true) {
                val entry = zipInputStream.nextEntry ?: break
                if (entry.name == entryName) {
                    result = String(zipInputStream.readAllBytes())
                }
            }
        }
        return result ?: error("Missing zip entry: $entryName")
    }

    private class RecordingDeployDataDatabase(
        private val fullResources: List<DeployItem>,
    ) : IDeployDataDatabase {
        val addFullResInputs = mutableListOf<List<DeployItem>>()

        override fun addFullRes(changedOverlays: List<DeployItem>, isNeedRes: Boolean, isNeedAsset: Boolean): List<DeployItem> {
            addFullResInputs += changedOverlays
            return changedOverlays + fullResources
        }

        override fun init(apks: List<ApkInfo>, deployedItems: List<DeployItem>): List<ParsedApkUpdateResult> = unsupported()
        override fun clearDeployedData() {
            unsupported<Unit>()
        }

        override fun commitDeployedData(juggDeployData: JuggDeployData) {
            unsupported<Unit>()
        }
        override fun isDeployedOverlaysBefore(): Boolean = unsupported()
        override fun getApkInfos(): List<ApkInfo> = unsupported()
        override fun getClassNodes(classNames: List<String>): Map<String, ClassNode> = unsupported()
        override fun getEffectedSourceAndClass(
            changedMethodRefs: List<MethodNode>,
            changedFieldRefs: List<FieldNode>,
            changedAbstractClasses: List<ClassNode>,
            changedGenericSignatureClasses: List<ClassNode>,
            maybeMinifiedRemoveClasses: ParsedDex?,
        ): List<EffectedClassNode> = unsupported()
        override fun getAllInterfacesWithDefaultMethod(interfaces: List<String>, staticInvocations: List<String>): List<String> = unsupported()
        override fun getCoreLibraryRewriteClassMap(apkFile: File): Map<String, String> = unsupported()
        override fun isEnableDesugared(): Boolean = unsupported()

        private fun <T> unsupported(): T {
            throw UnsupportedOperationException("Not used in this test")
        }
    }
}
