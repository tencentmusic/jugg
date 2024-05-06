package com.sickworm.intellij.jugg.deploy.data

import com.sickworm.intellij.jugg.mock.assetsDir
import com.sickworm.intellij.jugg.mock.assetsLibDir
import org.junit.Test
import java.io.File
import kotlin.test.assertContentEquals

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
    fun testJars() {
        assertResult(
            listOf(
                File(assetsLibDir, "rxjava-3.0.12.jar"),
            ),
            expectedInterfaces = listOf(
                "Ljava/lang/annotation/Annotation;",
                "Lorg/reactivestreams/Publisher;",
                "Lorg/reactivestreams/Subscriber;",
                "Ljava/lang/Runnable;",
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

    private fun assertResult(classFiles: List<File>,
                             expectedInterfaces: List<String> = emptyList(),
                             expectedStaticInvocations: List<String> = emptyList()) {
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
    }
}