package com.sickworm.intellij.jugg.compile

import com.sickworm.intellij.jugg.compiler.overlay.DexPackageRenamer
import com.sickworm.intellij.jugg.deploy.data.ApkParser
import com.sickworm.intellij.jugg.mock.buildDir
import com.sickworm.intellij.jugg.mock.clearBuild
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

class DexPackageRenamerTest {

    @Before
    fun before() {
        clearBuild()
    }

    @Test
    fun test() {
        val dexFile = File("src/test/assets/dex/com/example/myapplication/R\$dimen.dex").absoluteFile
        val newPackageName = "com.sickworm"
        val outputDexFile = DexPackageRenamer(dexFile, newPackageName).generate(buildDir)
        assert(outputDexFile.exists())

        val parsedDex = ApkParser().parseDexFiles(listOf(outputDexFile))
        assertEquals(1, parsedDex.classDeployItems.size)
        val classNode = parsedDex.classDeployItems.first().classNode

        assertEquals("Lcom/sickworm/R\$dimen;", classNode.className)
    }
}