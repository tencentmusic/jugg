package com.sickworm.intellij.jugg.manager

import com.android.tools.idea.run.ApkInfo
import com.googlecode.d2j.node.*
import com.googlecode.d2j.reader.BaseDexFileReader
import com.googlecode.d2j.reader.MultiDexFileReader
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.ide.JuggSettings
import com.sickworm.intellij.jugg.manager.utils.ListFiles
import com.sickworm.intellij.jugg.mock.assetsAndroidDir
import com.sickworm.intellij.jugg.project.ChangedFile
import org.junit.AfterClass
import org.junit.Assert
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * scan all the compilable files and check the compilre result is match the result
 */
class CompileConsistencyTest {

    companion object {
        /**
         * Compile consistency level description:
         * Level-1: compilable
         * Level-2: and same class structure
         * Level-3: and same byte code
         */
        private const val consistencyLevel: Int = 1

        private val jugg = MockJugg()
        private var oldCompileForSave = false
        private var firstTimeDeployOverlays = true
        private val apkClasses = mutableMapOf<String, DexClassNode>()

        private var isCollectErrorFilesOnly = System.getenv("JUGG_COLLECT_ERROR_FILES_ONLY") == "true"

        @BeforeClass
        @JvmStatic
        fun initAndSetNotCompileOnSave() {
            jugg.resetAllState()

            oldCompileForSave = JuggSettings.compileOnSave
            JuggSettings.compileOnSave = false
        }

        private fun initClasses(apkInfos: List<ApkInfo>) {
            assertEquals(1, apkInfos.size)
            val apkInfo = apkInfos.first()
            val apkBytes = apkInfo.files.first().apkFile.readBytes()
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
            println("(${index + 1}/$fileListSize)checking ${file.relativeTo(rootDir)}...")
            try {
                checkFileCompileConsistency(file)
                println("check consistency passed")
            } catch (e: Throwable) {
                println("check consistency failed")
                if (isCollectErrorFilesOnly) {
                    failedBinaryCheckList.add(file.absolutePath)
                    jugg.resetDeploy()
                } else {
                    throw e
                }
            }

            if (index % 200 == 0) {
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
        val checkFiles: List<String>? = System.getenv("JUGG_CHECK_FILES")?.let { value ->
            File(value)
                .takeIf { it.exists() }
                ?.readText()
                ?.split("\n")
        }
        if (checkFiles != null) {
            return checkFiles.map { File(it) }
        }

        return ListFiles.listFileOrderedByNameLastChar(rootDir)
    }

    private fun checkFileCompileConsistency(file: File) {
        jugg.notifyFileChanges(listOf(file))

        val changedFiles = jugg.deployFileManager.getUncompiledFiles()
        if (changedFiles.isEmpty()) {
            println("not a compilable file, ignore")
            return
        }
        assertEquals(1, changedFiles.size)
        val changedFile = changedFiles.first()

        jugg.compileChangedFiles()

        if (firstTimeDeployOverlays) {
            if (changedFile.type == CompileFile.Type.Resource ||
                changedFile.type == CompileFile.Type.Asset) {
                println("FirstTime deploy overlays needs full deployment, dry deploy and recompile again.")
                firstTimeDeployOverlays = false
                jugg.dryDeploy()
                checkFileCompileConsistency(file)
                return
            }
        }

        if (consistencyLevel >= 1) {
            checkCompileStatus()
        }

        val deployData = jugg.deployFileManager.getDeployData()

        if (consistencyLevel >= 2) {
            checkDeployStatus(changedFile, deployData)
        }

        if (consistencyLevel >= 3) {
            checkDeployBinary(deployData)
        }


        jugg.dryDeploy()
    }

    private fun checkCompileStatus() {
        assertEquals(0, jugg.deployFileManager.getUncompiledFiles().size, "not all files are compiled")
    }

    private fun checkDeployStatus(changedFile: ChangedFile, deployData: JuggDeployData) {
        val errorMessage = deployData.toString()

        when (changedFile.type) {
            CompileFile.Type.Java, CompileFile.Type.Kotlin -> {
                assertEquals(0, deployData.newClasses.size, errorMessage)
                assertEquals(0, deployData.hotFixModifiedClasses.size, errorMessage)
                assertTrue(deployData.hotReloadModifiedClasses.isNotEmpty(), errorMessage) // >= 1
                assertEquals(0, deployData.overlays.size, errorMessage)
            }
            CompileFile.Type.Resource, CompileFile.Type.Asset -> {
                assertEquals(0, deployData.newClasses.size, errorMessage)
                assertEquals(0, deployData.hotFixModifiedClasses.size, errorMessage)
                if (changedFile.type == CompileFile.Type.Resource) {
                    val rFileSize = 13 // R, R$drawable, etc.
                    assertEquals(rFileSize, deployData.hotReloadModifiedClasses.size, errorMessage)
                    overlayCommonFiles.forEach { name ->
                        assertNotNull(deployData.overlays.find { it.name == name })
                    }
                    val remainFileSize = deployData.overlays.size - overlayCommonFiles.size
                    // why >= 1?
                    // e.g. compile res/drawable-v24/ic_launcher_foreground.xml
                    // will get ic_launcher_foreground.xml and $ic_launcher_foreground__0.xml
                    assertTrue(remainFileSize >= 1, errorMessage)
                    assertTrue(deployData.overlays.isNotEmpty(), errorMessage) // >= 1
                } else {
                    assertEquals(0, deployData.hotReloadModifiedClasses.size, errorMessage)
                    assertEquals(1, deployData.overlays.size, errorMessage)
                }
            }
            else -> {
                Assert.fail("Unexpected compile file type ${changedFile.type} for file ${changedFile.file}")
            }
        }
    }

    private fun checkDeployBinary(deployData: JuggDeployData) {
        if (apkClasses.isEmpty()) {
            println("start initClasses")
            val costTime = measureTimeMillis {
                initClasses(jugg.deployTargetManager.getApks())
            }
            println("initClasses cost ${costTime}ms")
        }

        val deployItems = listOf(
            deployData.hotFixModifiedClasses.map { it.deployItem },
            deployData.hotReloadModifiedClasses.map { it.deployItem },
            deployData.newClasses.map { it.deployItem },
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
        val apk = jugg.deployTargetManager.getApks().first().files.first().apkFile
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

    private val overlayCommonFiles = listOf(
        "resources.arsc",
        "AndroidManifest.xml"
    )
}