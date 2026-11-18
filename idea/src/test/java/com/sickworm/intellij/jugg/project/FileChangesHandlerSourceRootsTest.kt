package com.sickworm.intellij.jugg.project

import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import kotlin.test.assertEquals

class FileChangesHandlerSourceRootsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `filters undeclared sibling directories and preserves declared source roots`() {
        TestGlobal.init()
        val projectRoot = temporaryFolder.root
        val moduleRoot = temporaryFolder.newFolder("app")
        val mainRoot = temporaryFolder.newFolder("app", "src")
        val hostRoot = temporaryFolder.newFolder("app", "src_host")
        temporaryFolder.newFolder("app", "src_test", "unit")
        val module = ModuleInfo.virtualModule.copy(
            projectRootDir = projectRoot,
            moduleRootDir = moduleRoot,
            sourceDirs = listOf(mainRoot, hostRoot),
        )
        val context = mock<ICompileContext> { on { modules }.thenReturn(mapOf(module.name to module)) }
        val handler = FileChangesHandler(projectRoot, projectRoot.resolve("build/jugg"), mock())
        handler.init(context)
        val mainFile = temporaryFolder.newFile("app/src/Main.kt")
        val hostFile = temporaryFolder.newFile("app/src_host/Host.java")
        val testFile = temporaryFolder.newFile("app/src_test/unit/HostTest.kt")

        val changed = handler.filter(listOf(mainFile, hostFile, testFile))

        assertEquals(listOf(
            ChangedFile(CompileFile.Type.Kotlin, mainFile, mainRoot, module),
            ChangedFile(CompileFile.Type.Java, hostFile, hostRoot, module),
        ), changed)
    }
}
