package com.sickworm.intellij.jugg.manager

import com.sickworm.intellij.jugg.mock.*
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val testSourceDirectory = "app/src/main/java/${androidApkPackage.replace('.', '/')}"

fun BasicJuggMock.changeFileAndNotify(vararg fileNamePairs: Pair<String, String>, directory: String = testSourceDirectory) {
    val pairs = fileNamePairs.map { (sourceFileName, destFileName) ->
        val sourceFile = File(assetsAndroidModifySourceDir, "$directory/$sourceFileName")
        val destFile = File(assetsAndroidDir, "$directory/$destFileName")
        sourceFile to destFile
    }
    val revertFileMark = pairs.map { (_, destFile) ->
        destFile to destFile.exists()
    }
    fileChangeEventSender.copyAndNotifyFileChanges(pairs)

    // revert
    revertFileMark.forEach { (destFile, isExist) ->
        revertFile(destFile.name, isAdd = !isExist, directory = directory)
    }
}

private fun revertFile(originFile: String, isAdd: Boolean = false, directory: String) {
    val sourceFile = File(assetsAndroidModifySourceDir, "$directory/$originFile")
    val destFile = File(assetsAndroidDir, "$directory/$originFile")
    if (isAdd) {
        destFile.delete()
        return
    }
    sourceFile.copyTo(destFile, overwrite = true)
}

fun BasicJuggMock.checkCompileResult(
    vararg fileNames: String,
    filePackageName: String = androidApkPackage,
    newClassesSize: Int = 0,
    hotFixModifiedClassesSize: Int = 0,
    hotReloadModifiedClassesSize: Int = 0,
    overlaysSize: Int = 0,
) {
    fileNames.forEach { fileName ->
        val relativePath = filePackageName.replace('.', '/')
        val className = File(fileName).nameWithoutExtension + ".class"
        val classPathFile = File(compileContextManager.compileContext.classPathDir, "$relativePath/$className")
        assertTrue(classPathFile.exists(), ".class file not exists, path: $classPathFile")
        assertTrue(classPathFile.length() > 0)

        val dexName = File(fileName).nameWithoutExtension + ".dex"
        val dexFile = File(compileContextManager.stagingDir, "classes/$relativePath/$dexName")
        assertTrue(dexFile.exists(), ".dex file not exists, path: $classPathFile")
        assertTrue(dexFile.length() > 0)
    }

    assertEquals(0, deployDataManager.getUncompiledFiles().size)
    val deployData = deployDataManager.getDeployData()
    assertEquals(1, deployData.apks.size)
    assertEquals(newClassesSize, deployData.newClasses.size)
    assertEquals(hotFixModifiedClassesSize, deployData.hotFixModifiedClasses.size)
    assertEquals(hotReloadModifiedClassesSize, deployData.hotReloadModifiedClasses.size)
    assertEquals(overlaysSize, deployData.overlays.size)
}
