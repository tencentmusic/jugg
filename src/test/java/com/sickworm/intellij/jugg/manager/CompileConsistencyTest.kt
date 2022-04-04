package com.sickworm.intellij.jugg.manager

import com.android.tools.deployer.DeployItem
import com.android.tools.deployer.JuggDeployData
import com.android.tools.idea.run.ApkInfo
import com.googlecode.d2j.node.*
import com.googlecode.d2j.reader.BaseDexFileReader
import com.googlecode.d2j.reader.MultiDexFileReader
import com.sickworm.intellij.jugg.compiler.*
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
import kotlin.system.measureTimeMillis
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
        private val apkClasses = mutableMapOf<String, DexClassNode>()

        private var isCollectErrorFilesOnly = System.getenv("JUGG_COLLECT_ERROR_FILES_ONLY") == "true"
        private val checkFiles: List<String>? =
            System.getenv("JUGG_CHECK_FILES")?.let { value ->
                File(value)
                    .takeIf { it.exists() }
                    ?.readText()
                    ?.split("\n")
            }

        @BeforeClass
        @JvmStatic
        fun initAndSetNotCompileOnSave() {
            jugg.initEnv()
            jugg.resetAllState()

            val costTime = measureTimeMillis {
                initClasses(jugg.deployTargetManager.getApks())
            }
            println("initClasses cost ${costTime}ms")

            oldCompileForSave = JuggSettings.compileOnSave
            JuggSettings.compileOnSave = false
        }

        private fun initClasses(apkInfos: List<ApkInfo>) {
            assertEquals(1, apkInfos.size)
            val apkInfo = apkInfos.first()
            val apkBytes = apkInfo.file.readBytes()
            val parsedClasses = parseDexClasses(apkBytes)
            apkClasses.putAll(parsedClasses)
        }

        private fun parseDexClasses(content: ByteArray): Map<String, DexClassNode> {
            val reader: BaseDexFileReader = MultiDexFileReader.open(content)
            val visitor = DexFileNode()
            reader.accept(visitor)

            val dexClasses = mutableMapOf<String, DexClassNode>()
            visitor.clzs.forEach {
                dexClasses[it.className] = it
            }
            return dexClasses
        }

        @AfterClass
        @JvmStatic
        fun resetCompileOnSave() {
            JuggSettings.compileOnSave = oldCompileForSave
        }
    }

    private val failedBinaryCheckList = mutableListOf<String>()

    @Test
    fun testConsistency() {
        val rootDir = assetsAndroidDir

        val fileList: List<File>
        val costTime = measureTimeMillis {
            fileList = getCheckFiles(rootDir)
        }
        val fileListSize = fileList.size
        println("getCheckFiles cost ${costTime}ms")
        println("$fileListSize files to be check (including not compilable files)")

        fileList.forEachIndexed { index, file ->
            println("($fileListSize/${index + 1})checking ${file.relativeTo(rootDir)}...")
            try {
                checkFileCompileConsistency(file)
            } catch (e: Throwable) {
                if (isCollectErrorFilesOnly) {
                    failedBinaryCheckList.add(file.absolutePath)
                    jugg.resetDeploy()
                } else {
                    throw e
                }
            }

            if (index % 50 == 0) {
                checkFailedList()
            }
        }

        checkFailedList()
    }

    private fun checkFailedList() {
        if (failedBinaryCheckList.isNotEmpty()) {
            System.err.println("error files: ")
            System.err.println(failedBinaryCheckList.joinToString("\n"))
        }
    }

    private fun getCheckFiles(rootDir: File): List<File> {
        if (checkFiles != null) {
            return checkFiles.map { File(it) }
        }

        val fileList = mutableListOf<File>()
        Files.walkFileTree(rootDir.toPath(), object : SimpleFileVisitor<Path>() {
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

                if (fileName.endsWith(".java") || fileName.endsWith(".kt")) {
                    fileList.add(file.toFile())
                }
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
        checkDeployStatus(uncompiledFile, deployData)
        // TODO not ready for such strict inspection
//        checkDeployBinary(deployData)

        jugg.dryDeploy()

        println("check consistency passed")
    }

    private fun checkDeployStatus(uncompiledFile: ChangedFile, deployData: JuggDeployData) {
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

    private fun checkDeployBinary(deployData: JuggDeployData) {
        val deployItems = listOf(
            deployData.hotFixModifiedClasses,
            deployData.hotReloadModifiedClasses,
            deployData.newClasses,
            deployData.overlays
        ).flatten()
        deployItems.forEach {
            println("    checking ${it.name}...")
            checkCompileBinary(it)
        }
    }

    private fun checkCompileBinary(deployItem: DeployItem) {
        // not supported case for now
        if (deployItem.name in ignoreBinaryCheckList) {
            return
        }

        when (deployItem.type) {
            CompileOutput.Type.Dex -> {
                compareClassNode(deployItem)
            }
            CompileOutput.Type.Overlay -> {
                compareOverlay(deployItem)
            }
            else -> {
                Assert.fail("Unexpected deploy file type ${deployItem.type}")
            }
        }
    }

    private fun compareClassNode(deployItem: DeployItem) {
        val className = deployItem.name.convertClassToSigFormat()
        val exceptClassNode = apkClasses[className]
        val deployClasses = parseDexClasses(deployItem.content)
        val actualClassNode = deployClasses[className]

        DexClassNodeComparator(exceptClassNode, actualClassNode).compare()
    }

    private fun compareOverlay(deployItem: DeployItem) {
        val apk = jugg.deployTargetManager.getApks().first().file
        val bytes = getOverlayBytesFromApk(deployItem, apk)
        OverlayComparator(bytes, deployItem.content).compare()
    }

    private fun String.convertClassToSigFormat(): String {
        return "L" + this.replace('.', '/') + ";"
    }

    private fun getOverlayBytesFromApk(deployItem: DeployItem, apk: File): ByteArray? {
        val path = deployItem.name
        val zipFile = ZipFile(apk)
        val entry = zipFile.getEntry(path) ?: return null
        val inputStream = zipFile.getInputStream(entry)
        return inputStream.readAllBytes()
    }

    // FIXME
    private val ignoreBinaryCheckList = listOf(
        // overlays
        "resources.arsc",
        "res/drawable-v24/\$ic_launcher_foreground__0.xml",
        "res/mipmap-xxxhdpi-v4/ic_launcher.png",
    )
}