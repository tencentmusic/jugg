package com.sickworm.intellij.jugg.deploy.data

import com.sickworm.intellij.jugg.mock.assetsDir
import com.sickworm.intellij.jugg.mock.assetsLibDir
import com.sickworm.intellij.jugg.mock.AssembleAndroidProjectOnce
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassWriter
import com.sickworm.intellij.jugg.org.objectweb.asm.Opcodes
import org.junit.Test
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ClassFileParserTest {

    private val testClassesDir = File(assetsDir, "class/parser_test")

    @Test
    fun testInterfaces() {
        assertResult(
            listOf(
                File(testClassesDir, "com/sickworm/jugg/demo/testcase/defaultinterface/ImplementBaseClass2.class"),
            ),
            expectedInterfaces = listOf(
                "Lcom/sickworm/jugg/demo/testcase/defaultinterface/DefaultInterface;",
            )
        )

        assertResult(
            listOf(
                File(testClassesDir, "com/sickworm/jugg/demo/testcase/defaultinterface/ImplementBaseInterface3.class"),
            ),
            expectedInterfaces = listOf(
                "Lcom/sickworm/jugg/demo/testcase/defaultinterface/DefaultInterface;",
            )
        )


        assertResult(
            listOf(
                File(testClassesDir, "com/sickworm/jugg/demo/testcase/defaultinterface/ImplementClass3.class"),
            ),
            expectedInterfaces = listOf(
                "Lcom/sickworm/jugg/demo/testcase/defaultinterface/ImplementBaseInterface3;",
            )
        )
    }

    @Test
    fun testStaticInvocations() {
        assertResult(
            listOf(
                File(testClassesDir, "com/example/myapplication/StaticInvoke.class"),
            ),
            expectedStaticInvocations = listOf(
                "Lcom/sickworm/jugg/demo/testcase/defaultinterface/DefaultInterface;",
                "Lcom/example/myapplication/ABCBaseCC;",
            ),
        )

        assertResult(
            listOf(
                File(testClassesDir, "com/sickworm/jugg/demo/testcase/defaultinterface/InvokerClass1.class"),
            ),
            expectedStaticInvocations = listOf(
                "Lcom/sickworm/jugg/demo/testcase/defaultinterface/DefaultInterface;",
            ),
        )

    }

    @Test
    fun testExternalSuperClasses() {
        val javaClassPath = AssembleAndroidProjectOnce.getProjectInfo()
            .modules.getValue("app").buildPathInfo.javaClassPath
        val childClass = File(
            javaClassPath,
            "com/sickworm/jugg/demo/testcase/defaultinterface/ParentOverrideChildClass.class",
        )
        val baseClass = File(
            javaClassPath,
            "com/sickworm/jugg/demo/testcase/defaultinterface/ParentOverrideBaseClass.class",
        )

        assertResult(
            listOf(childClass),
            expectedInterfaces = listOf(
                "Lcom/sickworm/jugg/demo/testcase/defaultinterface/ParentOverrideChildInterface;",
            ),
            expectedSuperClasses = listOf(
                "Lcom/sickworm/jugg/demo/testcase/defaultinterface/ParentOverrideBaseClass;",
            ),
        )
        assertResult(
            listOf(childClass, baseClass),
            expectedInterfaces = listOf(
                "Lcom/sickworm/jugg/demo/testcase/defaultinterface/ParentOverrideChildInterface;",
                "Lcom/sickworm/jugg/demo/testcase/defaultinterface/ParentOverrideDefaultInterface;",
            ),
            expectedSuperClasses = listOf(
                "Lcom/sickworm/jugg/demo/testcase/defaultinterface/ParentOverrideRootClass;",
            ),
        )
    }

    @Test
    fun testJars() {
        assertResult(
            listOf(
                File(assetsLibDir, "rxjava-3.0.12.jar"),
            ),
            expectedInterfaces = listOf(
                "Ljava/lang/annotation/Annotation;",
                "Lorg/reactivestreams/Publisher;",
                "Ljava/util/function/Function;",
                "Ljava/util/function/Supplier;",
                "Ljava/lang/Runnable;",
                "Lorg/reactivestreams/Subscriber;",
                "Ljava/lang/AutoCloseable;",
                "Ljava/util/concurrent/Callable;",
                "Ljava/util/Comparator;",
                "Lorg/reactivestreams/Subscription;",
                "Ljava/util/function/BiConsumer;",
                "Ljava/util/concurrent/Future;",
                "Ljava/util/Iterator;",
                "Ljava/lang/Iterable;",
                "Ljava/util/concurrent/ThreadFactory;",
                "Ljava/lang/Comparable;",
                "Ljava/io/Serializable;",
                "Ljava/util/List;",
                "Ljava/util/RandomAccess;",
                "Lorg/reactivestreams/Processor;",
            ),
            expectedStaticInvocations = listOf(
                "Ljava/lang/Enum;",
                "Ljava/util/Objects;",
                "Ljava/lang/Boolean;",
                "Ljava/lang/Math;",
                "Ljava/lang/Integer;",
                "Ljava/lang/Long;",
                "Ljava/util/Spliterators;",
                "Ljava/util/stream/StreamSupport;",
                "Ljava/lang/Thread;",
                "Ljava/lang/System;",
                "Ljava/util/Collections;",
                "Ljava/util/Arrays;",
                "Ljava/lang/Character;",
                "Ljava/lang/String;",
                "Ljava/lang/Runtime;",
                "Ljava/util/concurrent/Executors;",
                "Ljava/lang/reflect/Array;",
            ),
        )
    }

    @Test
    fun `analyze bytes exposes annotations and header`() {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/Entry", null, "test/Base", null)
        writer.visitAnnotation("Ldagger/hilt/android/AndroidEntryPoint;", false).visitEnd()
        writer.visitEnd()
        val bytes = writer.toByteArray()

        val analysis = ClassFileParser.analyze(bytes)
        val header = ClassFileParser.analyzeHeader(bytes)

        assertEquals("Ltest/Entry;", analysis.className)
        assertEquals("Ltest/Base;", analysis.superClass)
        assertEquals(
            setOf("Ldagger/hilt/android/AndroidEntryPoint;"),
            analysis.annotationDescriptors,
        )
        assertEquals(analysis.className, header.className)
        assertEquals(analysis.superClass, header.superClass)
        assertEquals(analysis.annotationDescriptors, header.annotationDescriptors)
    }

    private fun assertResult(classFiles: List<File>,
                             expectedInterfaces: List<String> = emptyList(),
                             expectedStaticInvocations: List<String> = emptyList(),
                             expectedSuperClasses: List<String> = emptyList()) {
        val classParser = ClassFileParser(classFiles)
        classParser.parse()

        assertContentEquals(
            expectedInterfaces,
            classParser.interfaces
        )

        assertContentEquals(
            expectedStaticInvocations,
            classParser.staticInvocationRefs
        )

        assertContentEquals(
            expectedSuperClasses,
            classParser.externalSuperClasses,
        )
    }
}
