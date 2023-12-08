package com.sickworm.intellij.jugg.project

import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.ModuleInfo
import java.io.File

/**
 * Handle file changes, filter the files that need to be compiled, and convert it to [ChangedFile]
 */
interface IFileChangesHandler {
    
    fun init(compileContext: ICompileContext)
    /**
     * Filter files and convert to [ChangedFile] if it is compilable
     */
    fun filter(file: List<File>): List<ChangedFile>

    fun checkBuildFileChanged(files: List<File>): Pair<Boolean, String>

}

data class ChangedFile(
    val type: CompileFile.Type,
    val file: File,
    val baseDir: File,
    val module: ModuleInfo,
    var compiledTimes: Int = 0,
) {

    val hasCompiledOnce: Boolean get() = compiledTimes > 0

    override fun toString(): String {
        return """
            ChangedFile(
                type=$type,
                file=$file,
                baseDir=$baseDir,
                module=${module.name}
            )
        """.trimIndent()
    }
}