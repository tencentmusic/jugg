package com.sickworm.intellij.jugg.manager

import com.android.tools.deployer.DeployItem
import com.android.tools.deployer.JuggDeployData
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.isResourceValueFile
import com.sickworm.intellij.jugg.ide.JuggSettings
import com.sickworm.intellij.jugg.mock.assetsAndroidDir
import com.sickworm.intellij.jugg.project.ChangedFile
import org.junit.AfterClass
import org.junit.Assert
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * scan all the compilable files and check the compilre result is match the result
 */
class CompileConsistencyTest {

    companion object {
        private val jugg = MockJugg()
        private var oldCompileForSave = false
        private var firstTimeDeployOverlays = true

        @BeforeClass
        @JvmStatic
        fun initAndSetNotCompileOnSave() {
            jugg.initEnv()
            jugg.resetAllState()

            oldCompileForSave = JuggSettings.compileOnSave
            JuggSettings.compileOnSave = false
        }

        @AfterClass
        @JvmStatic
        fun resetCompileOnSave() {
            JuggSettings.compileOnSave = oldCompileForSave
        }
    }

    @Test
    fun testConsistency() {

        val rootDir = assetsAndroidDir
        val fileList = getCheckFiles(rootDir)
        println("${fileList.size} files to be check (including not compilable files)")

        for (file in fileList) {
            println("checking ${file.relativeTo(rootDir)}...")
            checkFileCompileConsistency(file)
        }

        System.err.println(failedBinaryCheckList.toList())
    }

    private fun getCheckFiles(rootDir: File): List<File> {
        val fileList = mutableListOf<File>()
        Files.walkFileTree(rootDir.toPath(), object: SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val fileName = dir.fileName.toString()
                if (fileName == "build") {
                    return FileVisitResult.SKIP_SUBTREE
                }
                if (fileName.startsWith(".")) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val fileName = file.fileName.toString()
                if (fileName.startsWith(".")) {
                    return FileVisitResult.CONTINUE
                }
                // TODO remove, see [JuggInternalException.resValuesNotSupported}
                if (file.toFile().isResourceValueFile) {
                    return FileVisitResult.CONTINUE
                }
                fileList.add(file.toFile())
                return FileVisitResult.CONTINUE
            }
        })
        return fileList
    }

    private fun checkFileCompileConsistency(file: File) {
        jugg.notifyFileChanges(listOf(file))

        val uncompiledFiles = jugg.deployDataManager.getUncompiledFiles()
        if (uncompiledFiles.isEmpty()) {
            println("not a compilable file, ignore")
            return
        }
        assertEquals(1, uncompiledFiles.size)
        val uncompiledFile = uncompiledFiles.first()

        jugg.compileChangedFiles()

        if (firstTimeDeployOverlays) {
            if (uncompiledFile.type == CompileFile.Type.Resource || uncompiledFile.type == CompileFile.Type.Asset) {
                println("FirstTime deploy overlays needs full deployment, dry deploy and recompile again.")
                firstTimeDeployOverlays = false
                jugg.dryDeploy()
                checkFileCompileConsistency(file)
                return
            }
        }

        val deployData = jugg.deployDataManager.getDeployData()
        checkDeployData(uncompiledFile, deployData)
        val apk = jugg.deployTargetManager.getApks().first().file
        val deployItems = listOf(
            deployData.hotFixModifiedClasses,
            deployData.hotReloadModifiedClasses,
            deployData.newClasses,
            deployData.overlays
        ).flatten()
        deployItems.forEach {
            checkCompileBinary(it, apk)
        }

        jugg.dryDeploy()

        println("check consistency passed")
    }

    private fun checkDeployData(uncompiledFile: ChangedFile, deployData: JuggDeployData) {
        val errorMessage = deployData.toString()

        when (uncompiledFile.type) {
            CompileFile.Type.Java, CompileFile.Type.Kotlin -> {
                assertEquals(0, deployData.newClasses.size, errorMessage)
                assertEquals(0, deployData.hotFixModifiedClasses.size, errorMessage)
                assertTrue(deployData.hotReloadModifiedClasses.isNotEmpty(), errorMessage) // >= 1
                assertEquals(0, deployData.overlays.size, errorMessage)
            }
            CompileFile.Type.Resource, CompileFile.Type.Asset -> {
                assertEquals(0, deployData.newClasses.size, errorMessage)
                assertEquals(0, deployData.hotFixModifiedClasses.size, errorMessage)
                if (uncompiledFile.type == CompileFile.Type.Resource) {
                    val rFileSize = 13 // R, R$drawable, etc.
                    assertEquals(rFileSize, deployData.hotReloadModifiedClasses.size, errorMessage)
                    // TODO compile res/drawable-v24/ic_launcher_foreground.xml will get extra res/drawable-v24/$ic_launcher_foreground__0.xml
                    // TODO so disable exactly number of overlay size for now
//                    val overlaysSize = 3 // resources.arsc, resource, AndroidManifest.xml
//                    assertEquals(overlaysSize, deployData.overlays.size, errorMessage)
                    assertTrue(deployData.overlays.isNotEmpty(), errorMessage) // >= 1
                } else {
                    assertEquals(0, deployData.hotReloadModifiedClasses.size, errorMessage)
                    assertEquals(1, deployData.overlays.size, errorMessage)
                }

            }
            else -> {
                Assert.fail("Unexpected compile file type ${uncompiledFile.type} for file ${uncompiledFile.file}")
            }
        }
    }

    private fun checkCompileBinary(deployItem: DeployItem, apk: File) {
        when (deployItem.type) {
            CompileOutput.Type.Dex -> {
                val bytes = getClassBytesFromApk(deployItem, apk)
                compareBinary(deployItem, bytes, deployItem.content)
            }
            CompileOutput.Type.Overlay -> {
                val bytes = getOverlayBytesFromApk(deployItem, apk)
                compareBinary(deployItem, bytes, deployItem.content)
            }
            else -> {
                Assert.fail("Unexpected deploy file type ${deployItem.type}")
            }
        }
    }

    private fun getClassBytesFromApk(deployItem: DeployItem, apk: File): ByteArray {
        // TODO
        return deployItem.content
    }

    private fun getOverlayBytesFromApk(deployItem: DeployItem, apk: File): ByteArray? {
        val path = deployItem.name
        val zipFile = ZipFile(apk)
        val entry = zipFile.getEntry(path) ?: return null
        val inputStream = zipFile.getInputStream(entry)
        return inputStream.readAllBytes()
    }

    private val failedBinaryCheckList = mutableListOf<String>()

    private fun compareBinary(deployItem: DeployItem, except: ByteArray?, actual: ByteArray) {
        // Currently not supported case
        // TODO support ignoreBinaryCheckList
        if (deployItem.name in ignoreBinaryCheckList) {
            return
        }

        if (except == null) {
            // not exists in apk, it's ok
            return
        }

        if (!except.contentEquals(actual)) {
            val message = """
                file: ${deployItem.name}
                except size: ${except.size}, actual size: ${actual.size}
                except content:
                ${String(except)}
                actual content:
                ${String(actual)}
            """.trimIndent()
            Assert.fail(message)
            failedBinaryCheckList.add(deployItem.name)
        }
    }

    private val ignoreBinaryCheckList = listOf(
        "resources.arsc",
        "res/drawable-v24/\$ic_launcher_foreground__0.xml",
        "res/mipmap-xxxhdpi-v4/ic_launcher.png",
    )
}