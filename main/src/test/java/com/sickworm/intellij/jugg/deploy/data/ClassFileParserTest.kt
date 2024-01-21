package com.sickworm.intellij.jugg.deploy.data

import com.sickworm.intellij.jugg.mock.assetsDir
import org.junit.Test
import java.io.File
import kotlin.test.assertContentEquals

class ClassFileParserTest {

    private val testClassesDir = File(assetsDir, "class/parser_test")

    @Test
    fun testInterfaces() {
        assertInterfaces(
            listOf(
                File(testClassesDir, "com/sickworm/jugg/demo/testcase/defaultinterface/ImplementBaseClass2.class"),
            ),
            listOf(
                "Lcom/sickworm/jugg/demo/testcase/defaultinterface/DefaultInterface;",
            )
        )

        assertInterfaces(
            listOf(
                File(testClassesDir, "com/sickworm/jugg/demo/testcase/defaultinterface/ImplementBaseInterface3.class"),
            ),
            listOf(
                "Lcom/sickworm/jugg/demo/testcase/defaultinterface/DefaultInterface;",
            )
        )


        assertInterfaces(
            listOf(
                File(testClassesDir, "com/sickworm/jugg/demo/testcase/defaultinterface/ImplementClass3.class"),
            ),
            listOf(
                "Lcom/sickworm/jugg/demo/testcase/defaultinterface/ImplementBaseInterface3;",
            )
        )
    }

    private fun assertInterfaces(classFiles: List<File>, expected: List<String>) {
        val classParser = ClassFileParser(classFiles)
        classParser.parse()
        assert(classParser.interfaces.isNotEmpty())

        assertContentEquals(
            expected,
            classParser.interfaces
        )
    }

    @Test
    fun testStaticInvocations() {
        assertStaticInvocations(
            listOf(
                File(testClassesDir, "com/example/myapplication/StaticInvoke.class"),
            ),
            listOf(
                "Lcom/sickworm/jugg/demo/testcase/defaultinterface/DefaultInterface;",
                "Lcom/example/myapplication/ABCBaseCC;",
            ),
        )

        assertStaticInvocations(
            listOf(
                File(testClassesDir, "com/sickworm/jugg/demo/testcase/defaultinterface/InvokerClass1.class"),
            ),
            listOf(
                "Lcom/sickworm/jugg/demo/testcase/defaultinterface/DefaultInterface;",
            ),
        )

    }

    private fun assertStaticInvocations(classFiles: List<File>, expected: List<String>) {
        val classParser = ClassFileParser(classFiles)
        classParser.parse()
        assert(classParser.staticInvocationRefs.isNotEmpty())
        assertContentEquals(
            expected,
            classParser.staticInvocationRefs
        )
    }
}