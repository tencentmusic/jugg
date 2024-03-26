package com.sickworm.intellij.jugg.compile

import com.googlecode.d2j.DexConstants
import com.googlecode.d2j.reader.DexFileReader
import com.sickworm.intellij.jugg.compiler.overlay.DexPackageRenamer
import com.sickworm.intellij.jugg.deploy.asmSigFormat
import com.sickworm.intellij.jugg.deploy.data.ApkParser
import com.sickworm.intellij.jugg.deploy.data.classNode
import com.sickworm.intellij.jugg.mock.buildDir
import com.sickworm.intellij.jugg.mock.clearBuild
import org.junit.Before
import org.junit.Test
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassReader
import com.sickworm.intellij.jugg.org.objectweb.asm.tree.ClassNode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DexPackageRenamerTest {

    @Before
    fun before() {
        clearBuild()
    }

    @Test
    fun test() {
//        var dexFile = File("src/test/assets/dex/com/example/myapplication/R.dex").absoluteFile
//        doTest(dexFile)
//        dexFile = File("src/test/assets/dex/com/example/myapplication/R\$dimen.dex").absoluteFile
//        doTest(dexFile)
        val dexFile = File("src/test/assets/dex/com/example/myapplication/R\$styleable.dex").absoluteFile
        doTest(dexFile)
    }

    private fun doTest(dexFile: File) {
        val newPackageName = "com.sickworm"
        val newOwnerName = "Lcom/sickworm/${dexFile.nameWithoutExtension};"
        val (outputDexFile, outputClassFile) = DexPackageRenamer(dexFile, newPackageName).generate(buildDir, buildDir)
        assertTrue(outputDexFile.exists())

        val originParsedDex = ApkParser().parseDexFiles(listOf(dexFile))
        assertEquals(1, originParsedDex.classDeployItems.size)
        val originClassNode = originParsedDex.classDeployItems.first().classNode

        val parsedDex = ApkParser().parseDexFiles(listOf(outputDexFile))
        assertEquals(1, parsedDex.classDeployItems.size)
        val classNode = parsedDex.classDeployItems.first().classNode
        assertEquals(newOwnerName, classNode.className)
        assertEquals(originClassNode.fields.size, classNode.fields.size)

        val sortedFields = classNode.fields.sortedBy { it.name }
        originClassNode.fields.sortedBy { it.name }.forEachIndexed { index, it ->
            assertEquals(it.name, sortedFields[index].name)
            assertEquals(it.type, sortedFields[index].type)
            assertEquals(it.access, sortedFields[index].access)
            assertEquals(newOwnerName, sortedFields[index].owner)
        }
        val sortedMethods = classNode.methods.sortedBy { it.name }
        originClassNode.methods.sortedBy { it.name }.forEachIndexed { index, it ->
            assertEquals(it.name, sortedMethods[index].name)
            assertEquals(it.desc, sortedMethods[index].desc)
            assertEquals(it.access or DexConstants.ACC_PRIVATE or DexConstants.ACC_PUBLIC,
                sortedMethods[index].access or DexConstants.ACC_PRIVATE or DexConstants.ACC_PUBLIC,
            ) // ignore ACC_PRIVATE and ACC_PUBLIC, see DexPackageRenamer.visitMethod
            assertEquals(newOwnerName, sortedMethods[index].owner)
        }
        assertEquals(originClassNode.interfaceNames, classNode.interfaceNames)
        assertEquals(originClassNode.superClass, classNode.superClass)

        DexFileReader(outputDexFile.readBytes()).accept(DexFileOwnerChecker(originClassNode.className), 0)

        assertTrue(outputClassFile.exists())
        val reader = ClassReader(outputClassFile.readBytes())
        val asmClassNode = ClassNode()
        reader.accept(asmClassNode, 0)

        assertEquals(classNode.fields.size, asmClassNode.fields.size)
        val asmSortedFields = asmClassNode.fields.sortedBy { it.name }
        originClassNode.fields.sortedBy { it.name }.forEachIndexed { index, it ->
            assertEquals(it.name, asmSortedFields[index].name)
            assertEquals(it.type, asmSortedFields[index].desc)
            assertEquals(it.access, asmSortedFields[index].access)
        }

        assertEquals(classNode.methods.size, asmClassNode.methods.size)
        val asmSortedMethods = asmClassNode.methods.sortedBy { it.name }
        originClassNode.methods.sortedBy { it.name }.forEachIndexed { index, it ->
            assertEquals(it.name, asmSortedMethods[index].name)
            assertEquals(it.desc, asmSortedMethods[index].desc)
            // Dex flag and class dex are different after ACC_ENUM
            assertEquals(it.access or DexConstants.ACC_PRIVATE or DexConstants.ACC_PUBLIC and 32727,
                sortedMethods[index].access or DexConstants.ACC_PRIVATE or DexConstants.ACC_PUBLIC and 32727,
            ) // ignore ACC_PRIVATE and ACC_PUBLIC, see DexPackageRenamer.visitMethod
        }

        assertEquals(originClassNode.interfaceNames.map { it.asmSigFormat }, asmClassNode.interfaces)
        assertEquals(originClassNode.superClass.asmSigFormat, asmClassNode.superName)
    }
}

