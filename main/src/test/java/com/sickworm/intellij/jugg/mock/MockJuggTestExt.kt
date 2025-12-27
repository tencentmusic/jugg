package com.sickworm.intellij.jugg.mock

import com.sickworm.intellij.jugg.mock.*
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val testSourceDirectory = "app/src/main/java/${TestGlobal.packageName.replace('.', '/')}"

fun changeAndRevert(
    vararg fileNamePairs: Pair<String, String>,
    directory: String = testSourceDirectory,
    block: (List<File>) -> Unit,
) {
    // locate file path
    val filePairs = fileNamePairs.map { (sourceFileName, destFileName) ->
        val sourceFile = File(TestGlobal.modifySourceDir, "$directory/$sourceFileName")
        val destFile = File(TestGlobal.projectRootDir, "$directory/$destFileName")
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
            val sourceFile = File(TestGlobal.modifySourceDir, "$directory/${originFile.name}")
            val destFile = File(TestGlobal.projectRootDir, "$directory/${originFile.name}")
            if (!isExist) {
                destFile.delete()
                return@forEach
            }
            sourceFile.copyTo(destFile, overwrite = true)
        }
    }
}
