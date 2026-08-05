package com.sickworm.intellij.jugg.manager

import com.sickworm.intellij.jugg.mock.*
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val testSourceDirectory = "app/src/main/java/${androidApkPackage.replace('.', '/')}"

fun MockJugg.changeFileAndNotify(vararg fileNamePairs: Pair<String, String>, directory: String = testSourceDirectory) {
    assertTrue(deployStateManager.deployState.isReadyIncCompile)

    val currentSize = deployFileManager.getCompiledFiles().size
    changeAndRevert(*fileNamePairs, directory = directory) {
        fileChangesDetector.notifyFileChanges(it)
        juggManager.compileChanges()
    }
    assertEquals(currentSize + fileNamePairs.size, deployFileManager.getCompiledFiles().size)
}

fun changeAndRevert(
    vararg fileNamePairs: Pair<String, String>,
    directory: String = testSourceDirectory,
    block: (List<File>) -> Unit,
) {
    // locate file path
    val filePairs = fileNamePairs.map { (sourceFileName, destFileName) ->
        val sourceFile = File(assetsAndroidModifySourceDir, "$directory/$sourceFileName")
        val destFile = File(assetsAndroidDir, "$directory/$destFileName")
        sourceFile to destFile
    }
    val revertFileMark = filePairs.map { (_, destFile) ->
        destFile to destFile.exists()
    }

    // copy
    filePairs.forEach { (sourceFile, destFile) ->
        sourceFile.copyTo(destFile, overwrite = true)
    }

    try {
        // run block
        val files = filePairs.map { it.second }
        block(files)
    } finally {
        // revert
        revertFileMark.forEach { (originFile, isExist) ->
            val sourceFile = File(assetsAndroidModifySourceDir, "$directory/${originFile.name}")
            val destFile = File(assetsAndroidDir, "$directory/${originFile.name}")
            if (!isExist) {
                destFile.delete()
                return@forEach
            }
            sourceFile.copyTo(destFile, overwrite = true)
        }
    }
}

fun changeAndRevert(file: File, oldContent: String, newContent: String, block: () -> Unit) {
    val text = file.readText()
    val newText = text.replace(oldContent, newContent)
    try {
        file.writeText(newText)
        file.setLastModified(maxOf(System.currentTimeMillis(), file.lastModified() + 1_000))
        block()
    } finally {
        file.writeText(text)
        file.setLastModified(maxOf(System.currentTimeMillis(), file.lastModified() + 1_000))
    }
}

fun MockJugg.checkCompileResult(
    vararg fileNames: String,
    filePackageName: String = androidApkPackage,
    newClassesSize: Int = 0,
    hotFixModifiedClassesSize: Int = 0,
    hotReloadModifiedClassesSize: Int = 0,
    overlaysSize: Int = 0,
    apksSize: Int = 1,
) {
    fileNames.forEach { fileName ->
        val relativePath = filePackageName.replace('.', '/')
        val dexName = File(fileName).nameWithoutExtension + ".dex"
        val dexFile = File(pathManager.stagingDir, "classes/$relativePath/$dexName")
        assertTrue(dexFile.exists(), ".dex file not exists, path: ${pathManager.stagingDir}")
        assertTrue(dexFile.length() > 0)
    }

    assertEquals(0, deployFileManager.getUncompiledFiles().size)
    val deployData = deployFileManager.getDeployData()
    assertEquals(apksSize, deployData.apks.size)
    assertEquals(
        listOf(newClassesSize, hotFixModifiedClassesSize, hotReloadModifiedClassesSize).joinToString(),
        listOf(deployData.newClasses.size, deployData.hotFixModifiedClasses.size, deployData.hotReloadModifiedClasses.size).joinToString()
    )
    assertEquals(overlaysSize, deployData.overlays.size)
}
