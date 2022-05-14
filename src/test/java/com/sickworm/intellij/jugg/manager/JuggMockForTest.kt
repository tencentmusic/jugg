package com.sickworm.intellij.jugg.manager

import com.sickworm.intellij.jugg.mock.*
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val testSourceDirectory = "app/src/main/java/${androidApkPackage.replace('.', '/')}"

fun MockJugg.changeFileAndNotify(vararg fileNamePairs: Pair<String, String>, directory: String = testSourceDirectory) {
    val currentSize = deployFileManager.getCompiledFiles().size

    changeAndRevert(*fileNamePairs, directory = directory) {
        fileChangesDetector.notifyFileChanges(it) // will do compile
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

fun MockJugg.checkCompileResult(
    vararg fileNames: String,
    filePackageName: String = androidApkPackage,
    newClassesSize: Int = 0,
    hotFixModifiedClassesSize: Int = 0,
    hotReloadModifiedClassesSize: Int = 0,
    overlaysSize: Int = 0,
) {
    fileNames.forEach { fileName ->
        val relativePath = filePackageName.replace('.', '/')
        val dexName = File(fileName).nameWithoutExtension + ".dex"
        val dexFile = File(compileContextManager.stagingDir, "classes/$relativePath/$dexName")
        assertTrue(dexFile.exists(), ".dex file not exists, path: ${compileContextManager.stagingDir}")
        assertTrue(dexFile.length() > 0)
    }

    assertEquals(0, deployFileManager.getUncompiledFiles().size)
    val deployData = deployFileManager.getDeployData()
    assertEquals(1, deployData.apks.size)
    assertEquals(newClassesSize, deployData.newClasses.size)
    assertEquals(hotFixModifiedClassesSize, deployData.hotFixModifiedClasses.size)
    assertEquals(hotReloadModifiedClassesSize, deployData.hotReloadModifiedClasses.size)
    assertEquals(overlaysSize, deployData.overlays.size)
}
