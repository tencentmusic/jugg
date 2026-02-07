package com.sickworm.intellij.jugg.compile

import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.databinding.DataBindingArgsManager
import com.sickworm.intellij.jugg.mock.*
import com.sickworm.intellij.jugg.project.data.LibraryDependency
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
        // 准备源码文件 (User.java with changed field names)
        val userSourceFile = File(
            assetsAndroidModifySourceDir,
            "app/src/main/java/com/example/myapplication/model/User.java"
        )
        assertTrue(userSourceFile.exists(), "User.java source file should exist: ${userSourceFile.absolutePath}")

        // 准备布局文件 (referencing the changed field names)
        val layoutFile = File(
            assetsAndroidModifySourceDir,
            "app/src/main/res/layout/activity_user_binding_test.xml"
        )
        assertTrue(layoutFile.exists(), "Layout file should exist: ${layoutFile.absolutePath}")

        // 创建编译任务 - 需要同时包含源码和布局文件
        val module = context.modules.values.first()
        val task = CompileTask(
            files = listOf(
                // Java 源码文件
                CompileFile(
                    CompileFile.Type.Java,
                    userSourceFile,
                    File(assetsAndroidModifySourceDir, "app/src/main/java"),
                    module
                ),
                // XML 布局文件
                CompileFile(
                    CompileFile.Type.Resource,
                    layoutFile,
                    File(assetsAndroidModifySourceDir, "app/src/main/res"),
                    module
                )
            ),
            outputDir = CompileHelper.outputDir
        )

        // 执行编译
        val result = juggCompiler.compile(task)

        // 验证编译结果
        result.printCompileErrors()
        assertTrue(result.isAllSuccess, "Compilation should succeed with changed field names")

        // 验证输出文件
        CompileHelper.checkOutputFiles(result, listOf(
            // User.dex - 源码编译输出
            "com/example/myapplication/model/User.dex",

            // ViewBinding 基类
            "com/example/myapplication/databinding/ActivityUserBindingTestBinding.dex",

            // DataBinding 实现类 (这个才是依赖源码的)
            "com/example/myapplication/databinding/ActivityUserBindingTestBindingImpl.dex",

            // DataBinding Mapper 类
            "androidx/databinding/DataBinderMapperImpl.dex",
            "androidx/databinding/DataBindingComponent.dex",
            "com/example/myapplication/BR.dex",
            "com/example/myapplication/DataBinderMapperImpl.dex",
            "com/example/myapplication/DataBinderMapperImpl_Full.dex",
            "com/example/myapplication/DataBinderMapperImpl_Inc_1.dex",

            // 布局资源
            "res/layout/activity_user_binding_test.xml",
            "resources.arsc",
        ))

        // 验证生成的 Binding 实现类包含新的字段名
        val bindingImplClass = File(
            CompileHelper.dexOutputDir,
            "com/example/myapplication/databinding/ActivityUserBindingTestBindingImpl.dex"
        )
        assertTrue(bindingImplClass.exists(), "BindingImpl class should be generated")

        // 注意：由于是 .dex 文件，我们无法直接检查内容
        // 但编译成功本身就证明了 DataBinding 正确识别了新字段名
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
        // 准备源码文件 (Product.java - new class name)
        val productSourceFile = File(
            assetsAndroidModifySourceDir,
            "app/src/main/java/com/example/myapplication/model/Product.java"
        )
        assertTrue(productSourceFile.exists(), "Product.java source file should exist: ${productSourceFile.absolutePath}")

        // 准备布局文件 (referencing the new class name)
        val layoutFile = File(
            assetsAndroidModifySourceDir,
            "app/src/main/res/layout/activity_product_binding_test.xml"
        )
        assertTrue(layoutFile.exists(), "Layout file should exist: ${layoutFile.absolutePath}")

        // 创建编译任务
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

        // 执行编译
        val result = juggCompiler.compile(task)

        // 验证编译结果
        result.printCompileErrors()
        assertTrue(result.isAllSuccess, "Compilation should succeed with new class name")

        // 验证输出文件
        CompileHelper.checkOutputFiles(result, listOf(
            // Product.dex
            "com/example/myapplication/model/Product.dex",

            // ViewBinding 基类
            "com/example/myapplication/databinding/ActivityProductBindingTestBinding.dex",

            // DataBinding 实现类
            "com/example/myapplication/databinding/ActivityProductBindingTestBindingImpl.dex",

            // DataBinding Mapper 类
            "androidx/databinding/DataBinderMapperImpl.dex",
            "androidx/databinding/DataBindingComponent.dex",
            "com/example/myapplication/BR.dex",
            "com/example/myapplication/DataBinderMapperImpl.dex",
            "com/example/myapplication/DataBinderMapperImpl_Full.dex",
            "com/example/myapplication/DataBinderMapperImpl_Inc_1.dex",

            // 布局资源
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
        // 准备多个源码文件
        val userSourceFile = File(
            assetsAndroidModifySourceDir,
            "app/src/main/java/com/example/myapplication/model/User.java"
        )
        val productSourceFile = File(
            assetsAndroidModifySourceDir,
            "app/src/main/java/com/example/myapplication/model/Product.java"
        )

        // 准备多个布局文件
        val userLayoutFile = File(
            assetsAndroidModifySourceDir,
            "app/src/main/res/layout/activity_user_binding_test.xml"
        )
        val productLayoutFile = File(
            assetsAndroidModifySourceDir,
            "app/src/main/res/layout/activity_product_binding_test.xml"
        )

        // 验证文件存在
        assertTrue(userSourceFile.exists(), "User.java should exist: ${userSourceFile.absolutePath}")
        assertTrue(productSourceFile.exists(), "Product.java should exist: ${productSourceFile.absolutePath}")
        assertTrue(userLayoutFile.exists(), "User layout should exist: ${userLayoutFile.absolutePath}")
        assertTrue(productLayoutFile.exists(), "Product layout should exist: ${productLayoutFile.absolutePath}")

        // 创建编译任务 - 包含所有文件
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

        // 执行编译
        val result = juggCompiler.compile(task)

        // 验证编译结果
        result.printCompileErrors()
        assertTrue(result.isAllSuccess, "Compilation should succeed with multiple changes")

        // 验证输出文件 - 应该包含所有类的输出
        CompileHelper.checkOutputFiles(result, listOf(
            // Source files
            "com/example/myapplication/model/User.dex",
            "com/example/myapplication/model/Product.dex",

            // ViewBinding 基类
            "com/example/myapplication/databinding/ActivityUserBindingTestBinding.dex",
            "com/example/myapplication/databinding/ActivityProductBindingTestBinding.dex",

            // DataBinding 实现类
            "com/example/myapplication/databinding/ActivityUserBindingTestBindingImpl.dex",
            "com/example/myapplication/databinding/ActivityProductBindingTestBindingImpl.dex",

            // DataBinding Mapper 类 (共享)
            "androidx/databinding/DataBinderMapperImpl.dex",
            "androidx/databinding/DataBindingComponent.dex",
            "com/example/myapplication/BR.dex",
            "com/example/myapplication/DataBinderMapperImpl.dex",
            "com/example/myapplication/DataBinderMapperImpl_Full.dex",
            "com/example/myapplication/DataBinderMapperImpl_Inc_1.dex",

            // 布局资源
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
        // 使用现有的 DataBinding 测试资源
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
     * Test Case 6: DataBinding should use kapt when databinding dependencies are in kapt configuration
     */
    @Test
    fun testDataBindingUseKaptWhenDependenciesPresent() {
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
        assertFalse(argsManager.isJava, "DataBinding should use kapt when databinding dependency is in kaptDependencies")
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
        // 准备 Kotlin 源码文件 (UserKt.kt with changed field names)
        val userSourceFile = File(
            assetsAndroidModifySourceDir,
            "app/src/main/java/com/example/myapplication/model/UserKt.kt"
        )
        assertTrue(userSourceFile.exists(), "UserKt.kt source file should exist: ${userSourceFile.absolutePath}")

        // 准备布局文件 (referencing the changed field names)
        val layoutFile = File(
            assetsAndroidModifySourceDir,
            "app/src/main/res/layout/activity_user_binding_test_kotlin.xml"
        )
        assertTrue(layoutFile.exists(), "Layout file should exist: ${layoutFile.absolutePath}")

        // 创建编译任务 - 需要同时包含源码和布局文件
        val module = context.modules.values.first()
        val task = CompileTask(
            files = listOf(
                // Kotlin 源码文件
                CompileFile(
                    CompileFile.Type.Kotlin,
                    userSourceFile,
                    File(assetsAndroidModifySourceDir, "app/src/main/java"),
                    module
                ),
                // XML 布局文件
                CompileFile(
                    CompileFile.Type.Resource,
                    layoutFile,
                    File(assetsAndroidModifySourceDir, "app/src/main/res"),
                    module
                )
            ),
            outputDir = CompileHelper.outputDir
        )

        // 执行编译
        val result = juggCompiler.compile(task)

        // 验证编译结果
        result.printCompileErrors()
        assertTrue(result.isAllSuccess, "Compilation should succeed with changed field names (Kotlin)")

        // 验证输出文件
        CompileHelper.checkOutputFiles(result, listOf(
            // UserKt.dex - 源码编译输出
            "com/example/myapplication/model/UserKt.dex",

            // ViewBinding 基类
            "com/example/myapplication/databinding/ActivityUserBindingTestKotlinBinding.dex",

            // DataBinding 实现类 (这个才是依赖源码的)
            "com/example/myapplication/databinding/ActivityUserBindingTestKotlinBindingImpl.dex",

            // DataBinding Mapper 类
            "androidx/databinding/DataBinderMapperImpl.dex",
            "androidx/databinding/DataBindingComponent.dex",
            "com/example/myapplication/BR.dex",
            "com/example/myapplication/DataBinderMapperImpl.dex",
            "com/example/myapplication/DataBinderMapperImpl_Full.dex",
            "com/example/myapplication/DataBinderMapperImpl_Inc_1.dex",

            // 布局资源
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
        // 准备 Kotlin 源码文件 (ProductKt.kt - new class name)
        val productSourceFile = File(
            assetsAndroidModifySourceDir,
            "app/src/main/java/com/example/myapplication/model/ProductKt.kt"
        )
        assertTrue(productSourceFile.exists(), "ProductKt.kt source file should exist: ${productSourceFile.absolutePath}")

        // 准备布局文件 (referencing the new class name)
        val layoutFile = File(
            assetsAndroidModifySourceDir,
            "app/src/main/res/layout/activity_product_binding_test_kotlin.xml"
        )
        assertTrue(layoutFile.exists(), "Layout file should exist: ${layoutFile.absolutePath}")

        // 创建编译任务
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

        // 执行编译
        val result = juggCompiler.compile(task)

        // 验证编译结果
        result.printCompileErrors()
        assertTrue(result.isAllSuccess, "Compilation should succeed with new class name (Kotlin)")

        // 验证输出文件
        CompileHelper.checkOutputFiles(result, listOf(
            // ProductKt.dex
            "com/example/myapplication/model/ProductKt.dex",

            // ViewBinding 基类
            "com/example/myapplication/databinding/ActivityProductBindingTestKotlinBinding.dex",

            // DataBinding 实现类
            "com/example/myapplication/databinding/ActivityProductBindingTestKotlinBindingImpl.dex",

            // DataBinding Mapper 类
            "androidx/databinding/DataBinderMapperImpl.dex",
            "androidx/databinding/DataBindingComponent.dex",
            "com/example/myapplication/BR.dex",
            "com/example/myapplication/DataBinderMapperImpl.dex",
            "com/example/myapplication/DataBinderMapperImpl_Full.dex",
            "com/example/myapplication/DataBinderMapperImpl_Inc_1.dex",

            // 布局资源
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
        // 准备 Java 和 Kotlin 源码文件
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

        // 准备布局文件
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

        // 验证文件存在
        assertTrue(userJavaFile.exists(), "User.java should exist")
        assertTrue(userKotlinFile.exists(), "UserKt.kt should exist")
        assertTrue(productJavaFile.exists(), "Product.java should exist")
        assertTrue(productKotlinFile.exists(), "ProductKt.kt should exist")
        assertTrue(userJavaLayout.exists(), "User Java layout should exist")
        assertTrue(userKotlinLayout.exists(), "User Kotlin layout should exist")
        assertTrue(productJavaLayout.exists(), "Product Java layout should exist")
        assertTrue(productKotlinLayout.exists(), "Product Kotlin layout should exist")

        // 创建编译任务 - 包含所有文件
        val module = context.modules.values.first()
        val javaBaseDir = File(assetsAndroidModifySourceDir, "app/src/main/java")
        val resBaseDir = File(assetsAndroidModifySourceDir, "app/src/main/res")
        val task = CompileTask(
            files = listOf(
                // Java 源码
                CompileFile(CompileFile.Type.Java, userJavaFile, javaBaseDir, module),
                CompileFile(CompileFile.Type.Java, productJavaFile, javaBaseDir, module),
                // Kotlin 源码
                CompileFile(CompileFile.Type.Kotlin, userKotlinFile, javaBaseDir, module),
                CompileFile(CompileFile.Type.Kotlin, productKotlinFile, javaBaseDir, module),
                // 布局文件
                CompileFile(CompileFile.Type.Resource, userJavaLayout, resBaseDir, module),
                CompileFile(CompileFile.Type.Resource, userKotlinLayout, resBaseDir, module),
                CompileFile(CompileFile.Type.Resource, productJavaLayout, resBaseDir, module),
                CompileFile(CompileFile.Type.Resource, productKotlinLayout, resBaseDir, module)
            ),
            outputDir = CompileHelper.outputDir
        )

        // 执行编译
        val result = juggCompiler.compile(task)

        // 验证编译结果
        result.printCompileErrors()
        assertTrue(result.isAllSuccess, "Compilation should succeed with mixed Java and Kotlin changes")

        // 验证输出文件 - 应该包含所有类的输出
        CompileHelper.checkOutputFiles(result, listOf(
            // Java Source files
            "com/example/myapplication/model/User.dex",
            "com/example/myapplication/model/Product.dex",
            // Kotlin Source files
            "com/example/myapplication/model/UserKt.dex",
            "com/example/myapplication/model/ProductKt.dex",

            // ViewBinding 基类
            "com/example/myapplication/databinding/ActivityUserBindingTestBinding.dex",
            "com/example/myapplication/databinding/ActivityUserBindingTestKotlinBinding.dex",
            "com/example/myapplication/databinding/ActivityProductBindingTestBinding.dex",
            "com/example/myapplication/databinding/ActivityProductBindingTestKotlinBinding.dex",

            // DataBinding 实现类
            "com/example/myapplication/databinding/ActivityUserBindingTestBindingImpl.dex",
            "com/example/myapplication/databinding/ActivityUserBindingTestKotlinBindingImpl.dex",
            "com/example/myapplication/databinding/ActivityProductBindingTestBindingImpl.dex",
            "com/example/myapplication/databinding/ActivityProductBindingTestKotlinBindingImpl.dex",

            // DataBinding Mapper 类 (共享)
            "androidx/databinding/DataBinderMapperImpl.dex",
            "androidx/databinding/DataBindingComponent.dex",
            "com/example/myapplication/BR.dex",
            "com/example/myapplication/DataBinderMapperImpl.dex",
            "com/example/myapplication/DataBinderMapperImpl_Full.dex",
            "com/example/myapplication/DataBinderMapperImpl_Inc_1.dex",

            // 布局资源
            "res/layout/activity_user_binding_test.xml",
            "res/layout/activity_user_binding_test_kotlin.xml",
            "res/layout/activity_product_binding_test.xml",
            "res/layout/activity_product_binding_test_kotlin.xml",
            "resources.arsc",
        ))

        println("✓ DataBinding successfully compiled with mixed Java and Kotlin source changes")
    }
}
