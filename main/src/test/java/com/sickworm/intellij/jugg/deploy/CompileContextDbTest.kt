package com.sickworm.intellij.jugg.deploy

import com.jetbrains.rd.util.measureTimeMillis
import com.sickworm.intellij.jugg.manager.MockJugg
import com.sickworm.intellij.jugg.mock.*
import org.junit.Test

class CompileContextDbTest {

    @Test
    fun testReInitAfterFullCompiled() {
        val db = CompileContextDb(buildDir, projectInfo.projectRoot, logger)
        val jugg = MockJugg()
        jugg.compileContextManager.initCompileContext()
        val costTime = measureTimeMillis {
            db.copyFullCompileOutput(projectInfo.apkInfos, jugg.compileContextManager.compileContext.modules)
        }
        println("copyFullCompileOutput cost time: ${costTime}ms")
    }

}