package com.sickworm.intellij.jugg.ide

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.Icon

class JuggAndroidTestLineMarkerContributorTest {

    @Test
    fun `app androidTest path is supported`() {
        assertTrue(
            JuggAndroidTestLineMarkerContributor.isSupportedAndroidTestPath(
                "/project/app/src/androidTest/java/com/example/FooTest.kt"
            )
        )
    }

    @Test
    fun `unit test path is not supported`() {
        assertFalse(
            JuggAndroidTestLineMarkerContributor.isSupportedAndroidTestPath(
                "/project/app/src/test/java/com/example/FooTest.kt"
            )
        )
    }

    @Test
    fun `library androidTest path is not supported in phase three`() {
        assertFalse(
            JuggAndroidTestLineMarkerContributor.isSupportedAndroidTestPath(
                "/project/library1/src/androidTest/java/com/example/FooTest.kt"
            )
        )
    }

    @Test
    fun `java owner with junit annotation is supported`() {
        assertTrue(
            JuggAndroidTestLineMarkerContributor.hasJUnitTestAnnotation(
                JavaTestOwner(),
            )
        )
    }

    @Test
    fun `kotlin owner with junit annotation is supported`() {
        assertTrue(
            JuggAndroidTestLineMarkerContributor.hasJUnitTestAnnotation(
                KotlinTestOwner(),
            )
        )
    }

    @Test
    fun `kotlin owner falls back to annotation entries when annotations are empty`() {
        assertTrue(
            JuggAndroidTestLineMarkerContributor.hasJUnitTestAnnotation(
                KotlinOwnerWithEmptyAnnotations(),
            )
        )
    }

    @Test
    fun `annotation owner is found by walking parent chain`() {
        val owner = FakeOwner()
        val modifier = FakeNode(parent = owner)
        val annotation = FakeNode(parent = modifier)
        val leaf = FakeNode(parent = annotation)

        val found = JuggAndroidTestLineMarkerContributor.findAnnotatedElementOwner(
            leaf,
        ) { current ->
            when (current) {
                is FakeNode -> current.parent
                else -> null
            }
        }

        assertSame(owner, found)
    }

    @Test
    fun `class owner is found when class contains junit test`() {
        val testMethod = FakeKotlinFunction(
            name = "targetContextUsesAppPackage",
            parent = null,
            annotations = listOf(KotlinTestAnnotation()),
        )
        val owner = FakeKotlinClass(
            name = "com.example.myapplication.AppLogicInstrumentedTest",
            children = listOf(testMethod),
        )
        val leaf = FakeLeaf(parent = owner)

        val found = JuggAndroidTestLineMarkerContributor.findAnnotatedElementOwner(
            leaf,
        ) { current ->
            when (current) {
                is FakeLeaf -> current.parent
                else -> null
            }
        }

        assertSame(owner, found)
    }


    @Test
    fun `line marker is allowed only on owner name identifier`() {
        val owner = FakeOwner()
        val nameIdentifier = FakeLeaf(parent = owner)
        val bodyLeaf = FakeLeaf(parent = owner)

        assertTrue(
            JuggAndroidTestLineMarkerContributor.isOwnerNameIdentifier(
                element = nameIdentifier,
                owner = owner,
            ) { current ->
                when (current) {
                    owner -> nameIdentifier
                    else -> null
                }
            }
        )
        assertFalse(
            JuggAndroidTestLineMarkerContributor.isOwnerNameIdentifier(
                element = bodyLeaf,
                owner = owner,
            ) { current ->
                when (current) {
                    owner -> nameIdentifier
                    else -> null
                }
            }
        )
    }

    @Test
    fun `class name identifier is allowed when class contains junit test`() {
        val testMethod = FakeKotlinFunction(
            name = "targetContextUsesAppPackage",
            parent = null,
            annotations = listOf(KotlinTestAnnotation()),
        )
        val testClass = FakeKotlinClass(
            name = "com.example.myapplication.AppLogicInstrumentedTest",
            children = listOf(testMethod),
        )
        val nameIdentifier = FakeLeaf(parent = testClass)

        assertTrue(
            JuggAndroidTestLineMarkerContributor.isAndroidTestEntryNameIdentifier(
                element = nameIdentifier,
                owner = testClass,
                readNameIdentifier = { current ->
                    when (current) {
                        testClass -> nameIdentifier
                        else -> null
                    }
                },
            )
        )
    }

    @Test
    fun `line marker uses jugg run configuration icon`() {
        val icon = FakeIcon()

        assertSame(
            icon,
            JuggAndroidTestLineMarkerContributor.lineMarkerIcon(icon),
        )
    }

    @Test
    fun `run action uses jugg run configuration icon`() {
        val icon = FakeIcon()

        val action = JuggAndroidTestLineMarkerContributor.createRunAction(icon)

        assertSame(icon, action.templatePresentation.icon)
    }

    @Test
    fun `kotlin function target reads containing class from parent chain`() {
        val owner = FakeKotlinFunction(
            name = "targetContextUsesAppPackage",
            parent = FakeClassBody(
                parent = FakeKotlinClass(name = "com.example.myapplication.AppLogicInstrumentedTest")
            ),
            annotations = listOf(KotlinTestAnnotation()),
        )

        val target = JuggAndroidTestLineMarkerContributor.resolveAndroidTestTarget(
            owner,
            ownerParent = { current ->
                when (current) {
                    is FakeLeaf -> current.parent
                    else -> null
                }
            },
        )

        assertTrue(target.testClass == "com.example.myapplication.AppLogicInstrumentedTest")
        assertTrue(target.testMethod == "targetContextUsesAppPackage")
        assertTrue(target.displayName == "com.example.myapplication.AppLogicInstrumentedTest#targetContextUsesAppPackage")
    }

    @Test
    fun `kotlin function fq name does not replace containing class name`() {
        val owner = FakeKotlinFunction(
            name = "targetContextUsesAppPackage",
            parent = FakeClassBody(
                parent = FakeKotlinClass(name = "com.example.myapplication.AppLogicInstrumentedTest")
            ),
            fqName = "com.example.myapplication.AppLogicInstrumentedTest.targetContextUsesAppPackage",
            annotations = listOf(KotlinTestAnnotation()),
        )

        val target = JuggAndroidTestLineMarkerContributor.resolveAndroidTestTarget(
            owner,
            ownerParent = { current ->
                when (current) {
                    is FakeLeaf -> current.parent
                    else -> null
                }
            },
        )

        assertTrue(target.testClass == "com.example.myapplication.AppLogicInstrumentedTest")
        assertTrue(target.testMethod == "targetContextUsesAppPackage")
        assertTrue(target.displayName == "com.example.myapplication.AppLogicInstrumentedTest#targetContextUsesAppPackage")
    }

    @Test
    fun `kotlin function fq name with method suffix is trimmed to class name`() {
        val owner = FakeKotlinFunction(
            name = "targetContextUsesAppPackage",
            parent = null,
            fqName = "com.example.myapplication.AppLogicInstrumentedTest.targetContextUsesAppPackage",
            annotations = listOf(KotlinTestAnnotation()),
        )

        val target = JuggAndroidTestLineMarkerContributor.resolveAndroidTestTarget(
            owner,
            ownerParent = { null },
        )

        assertTrue(target.testClass == "com.example.myapplication.AppLogicInstrumentedTest")
        assertTrue(target.testMethod == "targetContextUsesAppPackage")
        assertTrue(target.displayName == "com.example.myapplication.AppLogicInstrumentedTest#targetContextUsesAppPackage")
    }

    @Test
    fun `kotlin class target runs all tests in class`() {
        val owner = FakeKotlinClass(
            name = "com.example.myapplication.AppLogicInstrumentedTest",
            children = listOf(
                FakeKotlinFunction(
                    name = "targetContextUsesAppPackage",
                    parent = null,
                    annotations = listOf(KotlinTestAnnotation()),
                ),
            ),
        )

        val target = JuggAndroidTestLineMarkerContributor.resolveAndroidTestTarget(
            owner,
            ownerParent = { null },
        )

        assertTrue(target.testClass == "com.example.myapplication.AppLogicInstrumentedTest")
        assertTrue(target.testMethod == null)
        assertTrue(target.displayName == "com.example.myapplication.AppLogicInstrumentedTest")
    }

    @Test
    fun `class owner with getName returns null testMethod regardless of hasChildren`() {
        // Regression: real KtClass has getName(), which must not leak into testMethod
        val owner = FakeKotlinClass(
            name = "com.example.myapplication.AppLogicInstrumentedTest",
            children = emptyList(), // no children ensures hasChildren is false
        )

        val target = JuggAndroidTestLineMarkerContributor.resolveAndroidTestTarget(
            owner,
            ownerParent = { null },
        )

        assertTrue(target.testClass == "com.example.myapplication.AppLogicInstrumentedTest")
        assertTrue(target.testMethod == null)
    }

    @Test
    fun `class owner with self-referencing getContainingClass keeps testMethod null`() {
        val owner = FakeKotlinClassWithSelfContainingClass(
            name = "com.example.myapplication.AppLogicInstrumentedTest",
            children = listOf(
                FakeKotlinFunction(
                    name = "targetContextUsesAppPackage",
                    parent = null,
                    annotations = listOf(KotlinTestAnnotation()),
                ),
            ),
        )

        val target = JuggAndroidTestLineMarkerContributor.resolveAndroidTestTarget(
            owner,
            ownerParent = { null },
        )

        assertTrue(target.testClass == "com.example.myapplication.AppLogicInstrumentedTest")
        assertTrue(target.testMethod == null)
    }

    @Test
    fun `method owner with self-referencing getContainingClass uses parent chain fallback`() {
        val owner = FakeKotlinFunctionWithSelfContainingClass(
            name = "targetContextUsesAppPackage",
            parent = FakeClassBody(
                parent = FakeKotlinClass(name = "com.example.myapplication.AppLogicInstrumentedTest")
            ),
            fqName = "com.example.myapplication.AppLogicInstrumentedTest.targetContextUsesAppPackage",
            annotations = listOf(KotlinTestAnnotation()),
        )

        val target = JuggAndroidTestLineMarkerContributor.resolveAndroidTestTarget(
            owner,
            ownerParent = { current ->
                when (current) {
                    is FakeLeaf -> current.parent
                    else -> null
                }
            },
        )

        assertTrue(target.testClass == "com.example.myapplication.AppLogicInstrumentedTest")
        assertTrue(target.testMethod == "targetContextUsesAppPackage")
    }

    @Test
    fun `method owner without containing class falls back to fq name derivation`() {
        // Regression: method owner where parent chain fails, must derive class from fqName
        val owner = FakeKotlinFunction(
            name = "targetContextUsesAppPackage",
            parent = null,
            fqName = "com.example.myapplication.AppLogicInstrumentedTest.targetContextUsesAppPackage",
            annotations = listOf(KotlinTestAnnotation()),
        )

        val target = JuggAndroidTestLineMarkerContributor.resolveAndroidTestTarget(
            owner,
            ownerParent = { null },
        )

        assertTrue(target.testClass == "com.example.myapplication.AppLogicInstrumentedTest")
        assertTrue(target.testMethod == "targetContextUsesAppPackage")
    }


    @Test
    fun `class target maps to class scope`() {
        val owner = FakeKotlinClass(
            name = "com.example.FooTest",
            children = listOf(FakeKotlinFunction("testBar", null, annotations = listOf(KotlinTestAnnotation()))),
        )

        val target = JuggAndroidTestLineMarkerContributor.resolveAndroidTestTarget(owner) { null }

        assertTrue(target.toScope() == AndroidTestScope.CLASS)
    }

    @Test
    fun `method target maps to method scope`() {
        val owner = FakeKotlinFunction(
            name = "testBar",
            parent = FakeClassBody(FakeKotlinClass("com.example.FooTest")),
            annotations = listOf(KotlinTestAnnotation()),
        )

        val target = JuggAndroidTestLineMarkerContributor.resolveAndroidTestTarget(owner) { current ->
            when (current) {
                is FakeLeaf -> current.parent
                else -> null
            }
        }

        assertTrue(target.toScope() == AndroidTestScope.METHOD)
    }

    @Test
    fun `gutter options persist selected app run config name`() {
        val target = JuggAndroidTestLineMarkerContributor.Companion.AndroidTestTarget(
            testClass = "com.example.FooTest",
            testMethod = "testBar",
            displayName = "com.example.FooTest#testBar",
        )
        val options = JuggAndroidTestRunConfigurationOptions()

        JuggAndroidTestLineMarkerContributor.Companion.applyTargetOptions(options, target, "appDebug")

        assertTrue(options.testScope == AndroidTestScope.METHOD)
        assertTrue(options.testClass == "com.example.FooTest")
        assertTrue(options.testMethod == "testBar")
        assertTrue(options.appRunConfigurationName == "appDebug")
    }

    private class JavaTestOwner {
        @Suppress("unused")
        fun getAnnotations(): Array<JavaTestAnnotation> = arrayOf(JavaTestAnnotation())
    }

    private class JavaTestAnnotation {
        @Suppress("unused")
        fun getQualifiedName(): String = "org.junit.Test"
    }

    private class KotlinTestOwner {
        @Suppress("unused")
        fun getAnnotationEntries(): List<KotlinTestAnnotation> = listOf(KotlinTestAnnotation())
    }

    private class KotlinTestAnnotation {
        @Suppress("unused")
        fun getText(): String = "@Test"
    }

    private class KotlinOwnerWithEmptyAnnotations {
        @Suppress("unused")
        fun getAnnotations(): Array<JavaTestAnnotation> = emptyArray()

        @Suppress("unused")
        fun getAnnotationEntries(): List<KotlinTestAnnotation> = listOf(KotlinTestAnnotation())
    }

    private open class FakeLeaf(
        val parent: Any?,
    )

    private class FakeNode(parent: Any?) : FakeLeaf(parent)

    private class FakeOwner : FakeLeaf(null) {
        @Suppress("unused")
        fun getAnnotationEntries(): List<KotlinTestAnnotation> = listOf(KotlinTestAnnotation())
    }

    private class FakeKotlinFunction(
        private val name: String,
        parent: Any?,
        private val fqName: String? = null,
        private val annotations: List<KotlinTestAnnotation> = emptyList(),
    ) : FakeLeaf(parent) {
        @Suppress("unused")
        fun getName(): String = name

        @Suppress("unused")
        fun getFqName(): String? = fqName

        @Suppress("unused")
        fun getAnnotationEntries(): List<KotlinTestAnnotation> = annotations
    }

    private class FakeClassBody(parent: Any?) : FakeLeaf(parent)

    private class FakeKotlinClass(
        private val name: String,
        private val children: List<Any> = emptyList(),
    ) : FakeLeaf(null) {
        @Suppress("unused")
        fun getFqName(): String = name

        @Suppress("unused")
        fun getName(): String = name.substringAfterLast(".")

        @Suppress("unused")
        fun getChildren(): Array<Any> = children.toTypedArray()
    }


    private class FakeKotlinClassWithSelfContainingClass(
        private val name: String,
        private val children: List<Any> = emptyList(),
    ) : FakeLeaf(null) {
        @Suppress("unused")
        fun getFqName(): String = name

        @Suppress("unused")
        fun getName(): String = name.substringAfterLast(".")

        @Suppress("unused")
        fun getContainingClass(): Any = this

        @Suppress("unused")
        fun getChildren(): Array<Any> = children.toTypedArray()
    }

    private class FakeKotlinFunctionWithSelfContainingClass(
        private val name: String,
        parent: Any?,
        private val fqName: String? = null,
        private val annotations: List<KotlinTestAnnotation> = emptyList(),
    ) : FakeLeaf(parent) {
        @Suppress("unused")
        fun getName(): String = name

        @Suppress("unused")
        fun getFqName(): String? = fqName

        @Suppress("unused")
        fun getContainingClass(): Any = this

        @Suppress("unused")
        fun getAnnotationEntries(): List<KotlinTestAnnotation> = annotations
    }

    private class FakeIcon : Icon {
        override fun paintIcon(c: java.awt.Component?, g: java.awt.Graphics?, x: Int, y: Int) = Unit

        override fun getIconWidth(): Int = 16

        override fun getIconHeight(): Int = 16
    }
}
