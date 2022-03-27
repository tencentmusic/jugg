package com.sickworm.intellij.jugg.manager

import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.isResourceValueFile
import com.sickworm.intellij.jugg.ide.JuggSettings
import com.sickworm.intellij.jugg.mock.assetsAndroidDir
import org.junit.AfterClass
import org.junit.Assert
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
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

        val fileList = mutableListOf<File>()
        val rootDir = assetsAndroidDir
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

        println("${fileList.size} files to be check (including not compilable files)")

        for (file in fileList) {
            println("checking ${file.relativeTo(rootDir)}...")
            checkFileCompileConsistency(file)
        }
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

        val deployData = jugg.deployDataManager.getDeployData()
        val errorMessage = deployData.toString()

        when (uncompiledFile.type) {
            CompileFile.Type.Java, CompileFile.Type.Kotlin -> {
                assertEquals(0, deployData.newClasses.size, errorMessage)
                assertEquals(0, deployData.hotFixModifiedClasses.size, errorMessage)
                assertTrue(deployData.hotReloadModifiedClasses.isNotEmpty(), errorMessage) // >= 1
                assertEquals(0, deployData.overlays.size, errorMessage)
            }
            CompileFile.Type.Resource, CompileFile.Type.Asset -> {
                if (firstTimeDeployOverlays) {
                    println("FirstTime deploy overlays needs full deployment, dry deploy and recompile again.")
                    firstTimeDeployOverlays = false
                    jugg.dryDeploy()
                    checkFileCompileConsistency(file)
                    return
                }
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

        jugg.dryDeploy()
    }
}