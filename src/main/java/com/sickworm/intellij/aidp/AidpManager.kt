package com.sickworm.intellij.aidp

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import java.io.File

class AidpManager(private val project: Project,
                  private val projectDir: String
): Disposable {

    private val fileChangesManager = FileChangesManager(project, projectDir)
    private val compiler = AidpCompiler()
    private val outputDir = File("$projectDir/build/aidp")

    fun start() {
        fileChangesManager.startListen(object: FileChangesListener {
            override fun onFileChanges(changeFiles: List<ChangeFileInfo>) {
                val compileFiles = changeFiles.map { CompileFileInfo(VfsUtil.virtualToIoFile(it.file)) }
                compiler.compile(compileFiles, outputDir)
            }
        })
    }

    override fun dispose() {
    }
}