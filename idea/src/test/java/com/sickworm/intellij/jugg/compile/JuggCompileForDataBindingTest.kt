package com.sickworm.intellij.jugg.compile

import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.databinding.DataBindingArgsManager
import com.sickworm.intellij.jugg.mock.*
import com.sickworm.intellij.jugg.project.info.LibraryDependency
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Test JuggCompiler with DataBinding when source code changes
 *
 * This test class focuses on scenarios where DataBinding depends on source code that has been modified:
 * - Variable name changes in data classes
 * - Class name changes
 * - Multiple file changes
 * - New class additions
 */
class JuggCompileForDataBindingTest {

    private val juggCompiler = JuggCompiler(context, mockParentDisposable)

    @Before
    fun init() {
        clearBuild()
        ResourceCompileTestTask().init()
        DataBindingArgsManager.isForceUseAptInTest = null
    }

    @After
    fun tearDown() {
        DataBindingArgsManager.isForceUseAptInTest = null
        DataBindingArgsManager.isKaAptRetryAptSuccess = false
        DataBindingArgsManager.isLastFallbackAptFailed = false
    }

    /**
     * Test Case 1: DataBinding with source field name changes
     *
     * Scenario:
     * - User class has changed field names: name -> userName, age -> userAge
     * - XML layout references the new field names
     * - Should compile successfully with new field names
     */
    @Test
    fun testDataBindingWithSourceFieldNameChange() {
        // Prepare source file (User.java with changed field names).
        val userSourceFile = File(
            assetsAndroidModifySourceDir,
            "app/src/main/java/com/example/myapplication/model/User.java"
        )
        assertTrue(userSourceFile.exists(), "User.java source file should exist: ${userSourceFile.absolutePath}")

        // Prepare layout file (referencing the changed field names).
        val layoutFile = File(
            assetsAndroidModifySourceDir,
            "app/src/main/res/layout/activity_user_binding_test.xml"
        )
        assertTrue(layoutFile.exists(), "Layout file should exist: ${layoutFile.absolutePath}")

        // Create a compile task with both source and layout files.
        val module = context.modules.values.first()
        val task = CompileTask(
            files = listOf(
                // Java source file.
                CompileFile(
                    CompileFile.Type.Java,
                    userSourceFile,
                    File(assetsAndroidModifySourceDir, "app/src/main/java"),
                    module
                ),
                // XML layout file.
                CompileFile(
                    CompileFile.Type.Resource,
                    layoutFile,
                    File(assetsAndroidModifySourceDir, "app/src/main/res"),
                    module
                )
            ),
            outputDir = CompileHelper.outputDir
        )

        // Run compilation.
        val result = juggCompiler.compile(task)

        // Verify the compile result.
        result.printCompileErrors()
        assertTrue(result.isAllSuccess, "Compilation should succeed with changed field names")

        // Verify output files.
        CompileHelper.checkOutputFiles(result, listOf(
            // User.dex - source compile output.
            "com/example/myapplication/model/User.dex",

            // ViewBinding base class.
            "com/example/myapplication/databinding/ActivityUserBindingTestBinding.dex",

            // DataBinding implementation class (the one that depends on source code).
            "com/example/myapplication/databinding/ActivityUserBindingTestBindingImpl.dex",

            // DataBinding Mapper class.
            "androidx/databinding/DataBinderMapperImpl.dex",
            "androidx/databinding/DataBindingComponent.dex",
            "com/example/myapplication/BR.dex",
            "com/example/myapplication/DataBinderMapperImpl.dex",
            "com/example/myapplication/DataBinderMapperImpl_Full.dex",
            "com/example/myapplication/DataBinderMapperImpl_Inc_1.dex",

            // Layout resource.
            "res/layout/activity_user_binding_test.xml",
            "resources.arsc",
        ))

        // Verify the generated Binding implementation includes the new field name.
        val bindingImplClass = File(
            CompileHelper.dexOutputDir,
            "com/example/myapplication/databinding/ActivityUserBindingTestBindingImpl.dex"
        )
        assertTrue(bindingImplClass.exists(), "BindingImpl class should be generated")

        // The .dex content cannot be inspected directly here.
        // Successful compilation shows that DataBinding recognized the new field name.
        println("✓ DataBinding successfully compiled with changed field names (userName, userAge)")
    }

    /**
     * Test Case 2: DataBinding with class name change
     *
     * Scenario:
     * - Product class is a new class name (was ProductModel before)
     * - XML layout references the new class name
     * - Should compile successfully with new class name
     */
    @Test
    fun testDataBindingWithClassNameChange() {
        // Prepare source file (Product.java - new class name).
        val productSourceFile = File(
            assetsAndroidModifySourceDir,
            "app/src/main/java/com/example/myapplication/model/Product.java"
        )
        assertTrue(productSourceFile.exists(), "Product.java source file should exist: ${productSourceFile.absolutePath}")

        // Prepare layout file (referencing the new class name).
        val layoutFile = File(
            assetsAndroidModifySourceDir,
            "app/src/main/res/layout/activity_product_binding_test.xml"
        )
        assertTrue(layoutFile.exists(), "Layout file should exist: ${layoutFile.absolutePath}")

        // Create a compile task.
        val module = context.modules.values.first()
        val task = CompileTask(
            files = listOf(
                CompileFile(
                    CompileFile.Type.Java,
                    productSourceFile,
                    File(assetsAndroidModifySourceDir, "app/src/main/java"),
                    module
                ),
                CompileFile(
                    CompileFile.Type.Resource,
                    layoutFile,
                    File(assetsAndroidModifySourceDir, "app/src/main/res"),
                    module
                )
            ),
            outputDir = CompileHelper.outputDir
        )

        // Run compilation.
        val result = juggCompiler.compile(task)

        // Verify the compile result.
        result.printCompileErrors()
        assertTrue(result.isAllSuccess, "Compilation should succeed with new class name")

        // Verify output files.
        CompileHelper.checkOutputFiles(result, listOf(
            // Product.dex
            "com/example/myapplication/model/Product.dex",

            // ViewBinding base class.
            "com/example/myapplication/databinding/ActivityProductBindingTestBinding.dex",

            // DataBinding implementation class.
            "com/example/myapplication/databinding/ActivityProductBindingTestBindingImpl.dex",

            // DataBinding Mapper class.
            "androidx/databinding/DataBinderMapperImpl.dex",
            "androidx/databinding/DataBindingComponent.dex",
            "com/example/myapplication/BR.dex",
            "com/example/myapplication/DataBinderMapperImpl.dex",
            "com/example/myapplication/DataBinderMapperImpl_Full.dex",
            "com/example/myapplication/DataBinderMapperImpl_Inc_1.dex",

            // Layout resource.
            "res/layout/activity_product_binding_test.xml",
            "resources.arsc",
        ))

        println("✓ DataBinding successfully compiled with new class name (Product)")
    }

    /**
     * Test Case 3: DataBinding with multiple source changes
     *
     * Scenario:
     * - Multiple data classes with changes
     * - Multiple layouts referencing them
     * - Should compile all successfully
     */
    @Test
    fun testDataBindingWithMultipleSourceChanges() {
        // Prepare multiple source files.
        val userSourceFile = File(
            assetsAndroidModifySourceDir,
            "app/src/main/java/com/example/myapplication/model/User.java"
        )
        val productSourceFile = File(
            assetsAndroidModifySourceDir,
            "app/src/main/java/com/example/myapplication/model/Product.java"
        )

        // Prepare multiple layout files.
        val userLayoutFile = File(
            assetsAndroidModifySourceDir,
            "app/src/main/res/layout/activity_user_binding_test.xml"
        )
        val productLayoutFile = File(
            assetsAndroidModifySourceDir,
            "app/src/main/res/layout/activity_product_binding_test.xml"
        )

        // Verify the files exist.
        assertTrue(userSourceFile.exists(), "User.java should exist: ${userSourceFile.absolutePath}")
        assertTrue(productSourceFile.exists(), "Product.java should exist: ${productSourceFile.absolutePath}")
        assertTrue(userLayoutFile.exists(), "User layout should exist: ${userLayoutFile.absolutePath}")
        assertTrue(productLayoutFile.exists(), "Product layout should exist: ${productLayoutFile.absolutePath}")

        // Create a compile task containing all files.
        val module = context.modules.values.first()
        val javaBaseDir = File(assetsAndroidModifySourceDir, "app/src/main/java")
        val resBaseDir = File(assetsAndroidModifySourceDir, "app/src/main/res")
        val task = CompileTask(
            files = listOf(
                CompileFile(CompileFile.Type.Java, userSourceFile, javaBaseDir, module),
                CompileFile(CompileFile.Type.Java, productSourceFile, javaBaseDir, module),
                CompileFile(CompileFile.Type.Resource, userLayoutFile, resBaseDir, module),
                CompileFile(CompileFile.Type.Resource, productLayoutFile, resBaseDir, module)
            ),
            outputDir = CompileHelper.outputDir
        )

        // Run compilation.
        val result = juggCompiler.compile(task)

        // Verify the compile result.
        result.printCompileErrors()
        assertTrue(result.isAllSuccess, "Compilation should succeed with multiple changes")

        // Verify output files contain results for every class.
        CompileHelper.checkOutputFiles(result, listOf(
            // Source files
            "com/example/myapplication/model/User.dex",
            "com/example/myapplication/model/Product.dex",

            // ViewBinding base class.
            "com/example/myapplication/databinding/ActivityUserBindingTestBinding.dex",
            "com/example/myapplication/databinding/ActivityProductBindingTestBinding.dex",

            // DataBinding implementation class.
            "com/example/myapplication/databinding/ActivityUserBindingTestBindingImpl.dex",
            "com/example/myapplication/databinding/ActivityProductBindingTestBindingImpl.dex",

            // Shared DataBinding Mapper class.
            "androidx/databinding/DataBinderMapperImpl.dex",
            "androidx/databinding/DataBindingComponent.dex",
            "com/example/myapplication/BR.dex",
            "com/example/myapplication/DataBinderMapperImpl.dex",
            "com/example/myapplication/DataBinderMapperImpl_Full.dex",
            "com/example/myapplication/DataBinderMapperImpl_Inc_1.dex",

            // Layout resource.
            "res/layout/activity_user_binding_test.xml",
            "res/layout/activity_product_binding_test.xml",
            "resources.arsc",
        ))

        println("✓ DataBinding successfully compiled with multiple source changes")
    }

    /**
     * Test Case 4: Existing DataBinding test should still work
     *
     * This ensures backward compatibility - existing DataBinding functionality
     * should not be broken by the refactoring
     */
    @Test
    fun testExistingDataBindingStillWorks() {
        // Use the existing DataBinding test resources.
        val compileTask = CompileHelper.makeTask(
            File(assetsAndroidDir, "app/src/main/res/layout/activity_data_binding_java_demo.xml")
        )

        val result = juggCompiler.compile(compileTask)

        result.printCompileErrors()
        assertTrue(result.isAllSuccess, "Existing DataBinding test should still work")

        CompileHelper.checkOutputFiles(result, listOf(
            "androidx/databinding/DataBinderMapperImpl.dex",
            "androidx/databinding/DataBindingComponent.dex",
            "com/example/myapplication/BR.dex",
            "com/example/myapplication/DataBinderMapperImpl.dex",
            "com/example/myapplication/DataBinderMapperImpl_Full.dex",
            "com/example/myapplication/DataBinderMapperImpl_Inc_1.dex",
            "com/example/myapplication/databinding/ActivityDataBindingJavaDemoBinding.dex",
            "com/example/myapplication/databinding/ActivityDataBindingJavaDemoBindingImpl.dex",
            "res/layout/activity_data_binding_java_demo.xml",
            "resources.arsc",
        ))

        println("✓ Existing DataBinding test still works - backward compatibility maintained")
    }

    @Test
    fun testBooleanVisibilityBindingAdapterFromGradleSetterStore() {
        val layoutFile = File(
            assetsAndroidDir,
            "app/src/main/res/layout/activity_data_binding_boolean_visibility_demo.xml",
        )

        val result = juggCompiler.compile(CompileHelper.makeTask(layoutFile))

        result.printCompileErrors()
        assertTrue(result.isAllSuccess, "Boolean visibility adapter compilation should succeed")
        CompileHelper.checkOutputFiles(result, listOf(
            "androidx/databinding/DataBinderMapperImpl.dex",
            "androidx/databinding/DataBindingComponent.dex",
            "com/example/myapplication/BR.dex",
            "com/example/myapplication/DataBinderMapperImpl.dex",
            "com/example/myapplication/DataBinderMapperImpl_Full.dex",
            "com/example/myapplication/DataBinderMapperImpl_Inc_1.dex",
            "com/example/myapplication/databinding/ActivityDataBindingBooleanVisibilityDemoBinding.dex",
            "com/example/myapplication/databinding/ActivityDataBindingBooleanVisibilityDemoBindingImpl.dex",
            "res/layout/activity_data_binding_boolean_visibility_demo.xml",
            "resources.arsc",
        ))
    }

    @Test
    fun testProjectDependencyBindingAdapterFromGradleSetterStore() {
        val layoutFile = File(
            assetsAndroidDir,
            "app/src/main/res/layout/activity_data_binding_library_adapter_demo.xml",
        )

        val result = juggCompiler.compile(CompileHelper.makeTask(layoutFile))

        result.printCompileErrors()
        assertTrue(result.isAllSuccess, "Project dependency BindingAdapter compilation should succeed")
        CompileHelper.checkOutputFiles(result, listOf(
            "androidx/databinding/DataBinderMapperImpl.dex",
            "androidx/databinding/DataBindingComponent.dex",
            "com/example/myapplication/BR.dex",
            "com/example/myapplication/DataBinderMapperImpl.dex",
            "com/example/myapplication/DataBinderMapperImpl_Full.dex",
            "com/example/myapplication/DataBinderMapperImpl_Inc_1.dex",
            "com/example/myapplication/databinding/ActivityDataBindingLibraryAdapterDemoBinding.dex",
            "com/example/myapplication/databinding/ActivityDataBindingLibraryAdapterDemoBindingImpl.dex",
            "res/layout/activity_data_binding_library_adapter_demo.xml",
            "resources.arsc",
        ))
    }

    @Test
    fun testNewBindingAdapterAndLayoutCompileIncrementally() {
        val adapterFile = File(
            assetsAndroidModifySourceDir,
            "app/src/main/java/com/sickworm/jugg/demo/testcase/databinding/IncrementalBindingAdapters.kt",
        )
        val layoutFile = File(
            assetsAndroidModifySourceDir,
            "app/src/main/res/layout/activity_data_binding_incremental_setter_store.xml",
        )
        val module = context.modules.values.first()
        val task = CompileTask(
            files = listOf(
                CompileFile(
                    CompileFile.Type.Kotlin,
                    adapterFile,
                    File(assetsAndroidModifySourceDir, "app/src/main/java"),
                    module,
                ),
                CompileFile(
                    CompileFile.Type.Resource,
                    layoutFile,
                    File(assetsAndroidModifySourceDir, "app/src/main/res"),
                    module,
                ),
            ),
            outputDir = CompileHelper.outputDir,
        )

        val result = juggCompiler.compile(task)

        result.printCompileErrors()
        assertTrue(
            result.isAllSuccess,
            "A newly added BindingAdapter should be available to a layout in the same incremental compile",
        )
        val argsManager = DataBindingArgsManager(context, module)
        val generatedStore = argsManager.kotlinAdapterKaptAarOutDir
            .walkTopDown()
            .firstOrNull { it.name.endsWith("-setter_store.json") }
        assertTrue(generatedStore != null, "Isolated KAPT should generate a Kotlin adapter setter store")
        assertTrue(
            generatedStore.readText().contains("juggIncrementalVisibility"),
            "The isolated KAPT setter store should contain the new Kotlin BindingAdapter",
        )
        CompileHelper.checkOutputFiles(result, listOf(
            "com/sickworm/jugg/demo/testcase/databinding/IncrementalBindingAdapters.dex",
            "com/example/myapplication/databinding/ActivityDataBindingIncrementalSetterStoreBinding.dex",
            "com/example/myapplication/databinding/ActivityDataBindingIncrementalSetterStoreBindingImpl.dex",
            "com/example/myapplication/DataBinderMapperImpl_Inc_1.dex",
            "res/layout/activity_data_binding_incremental_setter_store.xml",
            "resources.arsc",
        ))
    }

    @Test
    fun testNewBindingAdapterIsReusedByNextIncrementalCompile() {
        val adapterFile = File(
            assetsAndroidModifySourceDir,
            "app/src/main/java/com/sickworm/jugg/demo/testcase/databinding/IncrementalBindingAdapters.kt",
        )
        val baselineLayout = File(
            assetsAndroidDir,
            "app/src/main/res/layout/activity_data_binding_boolean_visibility_demo.xml",
        )
        val secondLayout = File(
            assetsAndroidModifySourceDir,
            "app/src/main/res/layout/activity_data_binding_incremental_setter_store_second.xml",
        )
        // A newly added layout shares the resource root of the layouts it is compiled with; a layout
        // from another resource root would restart the DataBinding layout info of this root.
        val baselineLayoutInModifySource = File(
            assetsAndroidModifySourceDir,
            "app/src/main/res/layout/${baselineLayout.name}",
        )
        baselineLayoutInModifySource.writeText(baselineLayout.readText())
        try {
            val module = context.modules.values.first()
            val firstResult = juggCompiler.compile(
                CompileTask(
                    files = listOf(CompileFile(
                        CompileFile.Type.Kotlin,
                        adapterFile,
                        File(assetsAndroidModifySourceDir, "app/src/main/java"),
                        module,
                    )),
                    outputDir = CompileHelper.outputDir,
                ),
            )
            firstResult.printCompileErrors()
            assertTrue(firstResult.isAllSuccess, "Adapter-only compile should generate the setter store")

            val secondResult = juggCompiler.compile(
                CompileHelper.makeTask(secondLayout, baselineLayoutInModifySource),
            )

            secondResult.printCompileErrors()
            assertTrue(secondResult.isAllSuccess, "The next incremental compile should reuse the generated setter store")
            CompileHelper.checkOutputFiles(secondResult, listOf(
                "com/example/myapplication/databinding/ActivityDataBindingIncrementalSetterStoreSecondBinding.dex",
                "com/example/myapplication/databinding/ActivityDataBindingIncrementalSetterStoreSecondBindingImpl.dex",
                "com/example/myapplication/databinding/ActivityDataBindingBooleanVisibilityDemoBindingImpl.dex",
                "res/layout/activity_data_binding_incremental_setter_store_second.xml",
                "res/layout/activity_data_binding_boolean_visibility_demo.xml",
                "resources.arsc",
            ))
        } finally {
            baselineLayoutInModifySource.delete()
        }
    }

    @Test
    fun testJavaBindingAdapterAndLayoutCompileIncrementally() {
        val adapterFile = File(
            assetsAndroidModifySourceDir,
            "app/src/main/java/com/sickworm/jugg/demo/testcase/databinding/IncrementalJavaBindingAdapters.java",
        )
        val layoutFile = File(
            assetsAndroidModifySourceDir,
            "app/src/main/res/layout/activity_data_binding_incremental_java_setter_store.xml",
        )

        val module = context.modules.values.first()
        val result = juggCompiler.compile(
            CompileTask(
                listOf(
                    CompileFile(
                        CompileFile.Type.Java,
                        adapterFile,
                        File(assetsAndroidModifySourceDir, "app/src/main/java"),
                        module,
                    ),
                    CompileFile(
                        CompileFile.Type.Resource,
                        layoutFile,
                        File(assetsAndroidModifySourceDir, "app/src/main/res"),
                        module,
                    ),
                ),
                CompileHelper.outputDir,
            ),
        )

        result.printCompileErrors()
        assertTrue(result.isAllSuccess, "Java BindingAdapter should be processed by APT")
        CompileHelper.checkOutputFiles(result, listOf(
            "com/sickworm/jugg/demo/testcase/databinding/IncrementalJavaBindingAdapters.dex",
            "com/example/myapplication/databinding/ActivityDataBindingIncrementalJavaSetterStoreBindingImpl.dex",
            "res/layout/activity_data_binding_incremental_java_setter_store.xml",
            "resources.arsc",
        ))
    }

    /**
     * Test Case 5: DataBinding with source change - negative test
     *
     * Scenario:
     * - Layout references a field that doesn't exist in the data class
     * - Should fail with appropriate error message
     */
    @Test
    fun testDataBindingWithInvalidFieldReference() {
        // This test will be implemented after the main refactoring is done
        // to ensure proper error handling

        // For now, we'll skip it
        println("⊘ Skipping negative test - to be implemented after refactoring")
    }

    /**
     * Test Case 6: DataBinding should keep using apt when databinding dependencies are in kapt configuration
     */
    @Test
    fun testDataBindingAlwaysUsesApt() {
        val module = context.modules.values.first()
        val fakeKaptJar = File(buildDir, "tmp/databinding-compiler-7.2.2.jar")
        fakeKaptJar.parentFile.mkdirs()
        fakeKaptJar.writeText("fake")

        val kaptDependency = LibraryDependency(
            name = "androidx.databinding:databinding-compiler:7.2.2",
            file = fakeKaptJar,
        )
        val moduleWithKapt = module.copy(kaptDependencies = listOf(kaptDependency))

        val argsManager = DataBindingArgsManager(context, moduleWithKapt)
        assertTrue(argsManager.isJava, "DataBinding should use apt even when databinding dependency is in kaptDependencies")
    }

    /**
     * Test Case 7: DataBinding with Kotlin source field name changes
     *
     * Scenario:
     * - UserKt class has changed field names: name -> userName, age -> userAge
     * - XML layout references the new field names
     * - Should compile successfully with new field names
     */
    @Test
    fun testDataBindingWithSourceFieldNameChange_Kotlin() {
        // Prepare Kotlin source file (UserKt.kt with changed field names).
        val userSourceFile = File(
            assetsAndroidModifySourceDir,
            "app/src/main/java/com/example/myapplication/model/UserKt.kt"
        )
        assertTrue(userSourceFile.exists(), "UserKt.kt source file should exist: ${userSourceFile.absolutePath}")

        // Prepare layout file (referencing the changed field names).
        val layoutFile = File(
            assetsAndroidModifySourceDir,
            "app/src/main/res/layout/activity_user_binding_test_kotlin.xml"
        )
        assertTrue(layoutFile.exists(), "Layout file should exist: ${layoutFile.absolutePath}")

        // Create a compile task with both source and layout files.
        val module = context.modules.values.first()
        val task = CompileTask(
            files = listOf(
                // Kotlin source file.
                CompileFile(
                    CompileFile.Type.Kotlin,
                    userSourceFile,
                    File(assetsAndroidModifySourceDir, "app/src/main/java"),
                    module
                ),
                // XML layout file.
                CompileFile(
                    CompileFile.Type.Resource,
                    layoutFile,
                    File(assetsAndroidModifySourceDir, "app/src/main/res"),
                    module
                )
            ),
            outputDir = CompileHelper.outputDir
        )

        // Run compilation.
        val result = juggCompiler.compile(task)

        // Verify the compile result.
        result.printCompileErrors()
        assertTrue(result.isAllSuccess, "Compilation should succeed with changed field names (Kotlin)")

        // Verify output files.
        CompileHelper.checkOutputFiles(result, listOf(
            // UserKt.dex - source compile output.
            "com/example/myapplication/model/UserKt.dex",

            // ViewBinding base class.
            "com/example/myapplication/databinding/ActivityUserBindingTestKotlinBinding.dex",

            // DataBinding implementation class (the one that depends on source code).
            "com/example/myapplication/databinding/ActivityUserBindingTestKotlinBindingImpl.dex",

            // DataBinding Mapper class.
            "androidx/databinding/DataBinderMapperImpl.dex",
            "androidx/databinding/DataBindingComponent.dex",
            "com/example/myapplication/BR.dex",
            "com/example/myapplication/DataBinderMapperImpl.dex",
            "com/example/myapplication/DataBinderMapperImpl_Full.dex",
            "com/example/myapplication/DataBinderMapperImpl_Inc_1.dex",

            // Layout resource.
            "res/layout/activity_user_binding_test_kotlin.xml",
            "resources.arsc",
        ))

        println("✓ DataBinding successfully compiled with changed field names (Kotlin: userName, userAge)")
    }

    /**
     * Test Case 8: DataBinding with Kotlin class name change
     *
     * Scenario:
     * - ProductKt class is a new class name (was ProductModelKt before)
     * - XML layout references the new class name
     * - Should compile successfully with new class name
     */
    @Test
    fun testDataBindingWithClassNameChange_Kotlin() {
        // Prepare Kotlin source file (ProductKt.kt - new class name).
        val productSourceFile = File(
            assetsAndroidModifySourceDir,
            "app/src/main/java/com/example/myapplication/model/ProductKt.kt"
        )
        assertTrue(productSourceFile.exists(), "ProductKt.kt source file should exist: ${productSourceFile.absolutePath}")

        // Prepare layout file (referencing the new class name).
        val layoutFile = File(
            assetsAndroidModifySourceDir,
            "app/src/main/res/layout/activity_product_binding_test_kotlin.xml"
        )
        assertTrue(layoutFile.exists(), "Layout file should exist: ${layoutFile.absolutePath}")

        // Create a compile task.
        val module = context.modules.values.first()
        val task = CompileTask(
            files = listOf(
                CompileFile(
                    CompileFile.Type.Kotlin,
                    productSourceFile,
                    File(assetsAndroidModifySourceDir, "app/src/main/java"),
                    module
                ),
                CompileFile(
                    CompileFile.Type.Resource,
                    layoutFile,
                    File(assetsAndroidModifySourceDir, "app/src/main/res"),
                    module
                )
            ),
            outputDir = CompileHelper.outputDir
        )

        // Run compilation.
        val result = juggCompiler.compile(task)

        // Verify the compile result.
        result.printCompileErrors()
        assertTrue(result.isAllSuccess, "Compilation should succeed with new class name (Kotlin)")

        // Verify output files.
        CompileHelper.checkOutputFiles(result, listOf(
            // ProductKt.dex
            "com/example/myapplication/model/ProductKt.dex",

            // ViewBinding base class.
            "com/example/myapplication/databinding/ActivityProductBindingTestKotlinBinding.dex",

            // DataBinding implementation class.
            "com/example/myapplication/databinding/ActivityProductBindingTestKotlinBindingImpl.dex",

            // DataBinding Mapper class.
            "androidx/databinding/DataBinderMapperImpl.dex",
            "androidx/databinding/DataBindingComponent.dex",
            "com/example/myapplication/BR.dex",
            "com/example/myapplication/DataBinderMapperImpl.dex",
            "com/example/myapplication/DataBinderMapperImpl_Full.dex",
            "com/example/myapplication/DataBinderMapperImpl_Inc_1.dex",

            // Layout resource.
            "res/layout/activity_product_binding_test_kotlin.xml",
            "resources.arsc",
        ))

        println("✓ DataBinding successfully compiled with new class name (Kotlin: ProductKt)")
    }

    /**
     * Test Case 9: DataBinding with mixed Java and Kotlin source changes
     *
     * Scenario:
     * - Both Java and Kotlin data classes with changes
     * - Multiple layouts referencing them
     * - Should compile all successfully
     */
    @Test
    fun testDataBindingWithMixedJavaKotlinSourceChanges() {
        // Prepare Java and Kotlin source files.
        val userJavaFile = File(
            assetsAndroidModifySourceDir,
            "app/src/main/java/com/example/myapplication/model/User.java"
        )
        val userKotlinFile = File(
            assetsAndroidModifySourceDir,
            "app/src/main/java/com/example/myapplication/model/UserKt.kt"
        )
        val productJavaFile = File(
            assetsAndroidModifySourceDir,
            "app/src/main/java/com/example/myapplication/model/Product.java"
        )
        val productKotlinFile = File(
            assetsAndroidModifySourceDir,
            "app/src/main/java/com/example/myapplication/model/ProductKt.kt"
        )

        // Prepare layout file.
        val userJavaLayout = File(
            assetsAndroidModifySourceDir,
            "app/src/main/res/layout/activity_user_binding_test.xml"
        )
        val userKotlinLayout = File(
            assetsAndroidModifySourceDir,
            "app/src/main/res/layout/activity_user_binding_test_kotlin.xml"
        )
        val productJavaLayout = File(
            assetsAndroidModifySourceDir,
            "app/src/main/res/layout/activity_product_binding_test.xml"
        )
        val productKotlinLayout = File(
            assetsAndroidModifySourceDir,
            "app/src/main/res/layout/activity_product_binding_test_kotlin.xml"
        )

        // Verify the files exist.
        assertTrue(userJavaFile.exists(), "User.java should exist")
        assertTrue(userKotlinFile.exists(), "UserKt.kt should exist")
        assertTrue(productJavaFile.exists(), "Product.java should exist")
        assertTrue(productKotlinFile.exists(), "ProductKt.kt should exist")
        assertTrue(userJavaLayout.exists(), "User Java layout should exist")
        assertTrue(userKotlinLayout.exists(), "User Kotlin layout should exist")
        assertTrue(productJavaLayout.exists(), "Product Java layout should exist")
        assertTrue(productKotlinLayout.exists(), "Product Kotlin layout should exist")

        // Create a compile task containing all files.
        val module = context.modules.values.first()
        val javaBaseDir = File(assetsAndroidModifySourceDir, "app/src/main/java")
        val resBaseDir = File(assetsAndroidModifySourceDir, "app/src/main/res")
        val task = CompileTask(
            files = listOf(
                // Java source.
                CompileFile(CompileFile.Type.Java, userJavaFile, javaBaseDir, module),
                CompileFile(CompileFile.Type.Java, productJavaFile, javaBaseDir, module),
                // Kotlin source.
                CompileFile(CompileFile.Type.Kotlin, userKotlinFile, javaBaseDir, module),
                CompileFile(CompileFile.Type.Kotlin, productKotlinFile, javaBaseDir, module),
                // Layout file.
                CompileFile(CompileFile.Type.Resource, userJavaLayout, resBaseDir, module),
                CompileFile(CompileFile.Type.Resource, userKotlinLayout, resBaseDir, module),
                CompileFile(CompileFile.Type.Resource, productJavaLayout, resBaseDir, module),
                CompileFile(CompileFile.Type.Resource, productKotlinLayout, resBaseDir, module)
            ),
            outputDir = CompileHelper.outputDir
        )

        // Run compilation.
        val result = juggCompiler.compile(task)

        // Verify the compile result.
        result.printCompileErrors()
        assertTrue(result.isAllSuccess, "Compilation should succeed with mixed Java and Kotlin changes")

        // Verify output files contain results for every class.
        CompileHelper.checkOutputFiles(result, listOf(
            // Java Source files
            "com/example/myapplication/model/User.dex",
            "com/example/myapplication/model/Product.dex",
            // Kotlin Source files
            "com/example/myapplication/model/UserKt.dex",
            "com/example/myapplication/model/ProductKt.dex",

            // ViewBinding base class.
            "com/example/myapplication/databinding/ActivityUserBindingTestBinding.dex",
            "com/example/myapplication/databinding/ActivityUserBindingTestKotlinBinding.dex",
            "com/example/myapplication/databinding/ActivityProductBindingTestBinding.dex",
            "com/example/myapplication/databinding/ActivityProductBindingTestKotlinBinding.dex",

            // DataBinding implementation class.
            "com/example/myapplication/databinding/ActivityUserBindingTestBindingImpl.dex",
            "com/example/myapplication/databinding/ActivityUserBindingTestKotlinBindingImpl.dex",
            "com/example/myapplication/databinding/ActivityProductBindingTestBindingImpl.dex",
            "com/example/myapplication/databinding/ActivityProductBindingTestKotlinBindingImpl.dex",

            // Shared DataBinding Mapper class.
            "androidx/databinding/DataBinderMapperImpl.dex",
            "androidx/databinding/DataBindingComponent.dex",
            "com/example/myapplication/BR.dex",
            "com/example/myapplication/DataBinderMapperImpl.dex",
            "com/example/myapplication/DataBinderMapperImpl_Full.dex",
            "com/example/myapplication/DataBinderMapperImpl_Inc_1.dex",

            // Layout resource.
            "res/layout/activity_user_binding_test.xml",
            "res/layout/activity_user_binding_test_kotlin.xml",
            "res/layout/activity_product_binding_test.xml",
            "res/layout/activity_product_binding_test_kotlin.xml",
            "resources.arsc",
        ))

        println("✓ DataBinding successfully compiled with mixed Java and Kotlin source changes")
    }

    /**
     * Test Case 10: DataBinding with Kotlin source in kapt-to-apt fallback mode
     *
     * Scenario (reproduces the bug from commit d4e58febe):
     * - kapt failed on first run and fell back to apt (isKaAptRetryAptSuccess = true)
     * - User changes a Kotlin data class that is referenced in DataBinding layout XML
     * - The apt-only annotation processor cannot see Kotlin class fields because .class is not yet compiled
     * - DataBinding mapper generation fails in prepareSourceCompile, and doModuleCompile should retry:
     *   1. Skip DataBinding mapper, compile Kotlin/Java sources first
     *   2. After Kotlin .class files are available, retry DataBinding mapper generation
     *   3. Compile should succeed
     *
     * Before fix: prepareSourceCompile failure caused doModuleCompile to return immediately,
     * skipping compileLanguageStagesWithRetry entirely.
     */
    @Test
    fun testDataBindingKotlinWithKaptToAptFallback_shouldRetryAfterLanguageCompile() {
        // Simulate: kapt failed and fell back to apt
        DataBindingArgsManager.isForceUseAptInTest = true

        // Use the same Kotlin DataBinding scenario as testDataBindingWithSourceFieldNameChange_Kotlin
        val userSourceFile = File(
            assetsAndroidModifySourceDir,
            "app/src/main/java/com/example/myapplication/model/UserKt.kt"
        )
        assertTrue(userSourceFile.exists(), "UserKt.kt source file should exist: ${userSourceFile.absolutePath}")

        val layoutFile = File(
            assetsAndroidModifySourceDir,
            "app/src/main/res/layout/activity_user_binding_test_kotlin.xml"
        )
        assertTrue(layoutFile.exists(), "Layout file should exist: ${layoutFile.absolutePath}")

        val module = context.modules.values.first()
        val task = CompileTask(
            files = listOf(
                CompileFile(
                    CompileFile.Type.Kotlin,
                    userSourceFile,
                    File(assetsAndroidModifySourceDir, "app/src/main/java"),
                    module
                ),
                CompileFile(
                    CompileFile.Type.Resource,
                    layoutFile,
                    File(assetsAndroidModifySourceDir, "app/src/main/res"),
                    module
                )
            ),
            outputDir = CompileHelper.outputDir
        )

        val result = juggCompiler.compile(task)

        result.printCompileErrors()
        assertTrue(
            result.isAllSuccess,
            "DataBinding Kotlin compile with kapt-to-apt fallback should succeed through " +
                "prepareSourceCompile retry after language compilation"
        )

        CompileHelper.checkOutputFiles(result, listOf(
            "com/example/myapplication/model/UserKt.dex",
            "com/example/myapplication/databinding/ActivityUserBindingTestKotlinBinding.dex",
            "com/example/myapplication/databinding/ActivityUserBindingTestKotlinBindingImpl.dex",
            "androidx/databinding/DataBinderMapperImpl.dex",
            "androidx/databinding/DataBindingComponent.dex",
            "com/example/myapplication/BR.dex",
            "com/example/myapplication/DataBinderMapperImpl.dex",
            "com/example/myapplication/DataBinderMapperImpl_Full.dex",
            "com/example/myapplication/DataBinderMapperImpl_Inc_1.dex",
            "res/layout/activity_user_binding_test_kotlin.xml",
            "resources.arsc",
        ))

        println("✓ DataBinding Kotlin compile with kapt-to-apt fallback succeeded through retry")
    }
}
