package com.sickworm.intellij.jugg.compile

import com.sickworm.intellij.jugg.compiler.ModuleCompileOrderUtils
import com.sickworm.intellij.jugg.compiler.ModuleDependency
import com.sickworm.intellij.jugg.compiler.ModuleInfo
import com.sickworm.intellij.jugg.mock.mockModule
import org.junit.Test
import kotlin.system.measureTimeMillis
import kotlin.test.assertContentEquals

class ModuleCompileOrderUtilsTest {

    private val module1 = mockModule.copy(name = "module_1")

    private val module2 = mockModule.copy(name = "module_2")

    private val module3 = mockModule.copy(name = "module_3", moduleDependencies = listOf(
        ModuleDependency("module_1")
    ))

    private val module4 = mockModule.copy(name = "module_4", moduleDependencies = listOf(
        ModuleDependency("module_100")
    ))

    @Test
    fun testSingleModule() {
        val order = ModuleCompileOrderUtils.getModuleCompileOrders(setOf(module1))
        assertContentEquals(listOf(module1), order)
    }

    @Test
    fun testTwoStandaloneModules() {
        val order = ModuleCompileOrderUtils.getModuleCompileOrders(setOf(module1, module2))
        assertModuleOrder(listOf(module1, module2), order)
    }

    @Test
    fun testTwoDependModules() {
        var order = ModuleCompileOrderUtils.getModuleCompileOrders(setOf(module3, module1))
        assertModuleOrder(listOf(module1, module3), order)
        order = ModuleCompileOrderUtils.getModuleCompileOrders(setOf(module1, module3))
        assertModuleOrder(listOf(module1, module3), order)
    }


    @Test
    fun testThreeDependModules() {
        var order = ModuleCompileOrderUtils.getModuleCompileOrders(setOf(module3, module1, module2))
        assertModuleOrder(listOf(module1, module3, module2), order)
        order = ModuleCompileOrderUtils.getModuleCompileOrders(setOf(module1, module2, module3))
        assertModuleOrder(listOf(module1, module3, module2), order)
    }

    @Test
    fun testThreeDependModulesWithNonExistsModule() {
        var order = ModuleCompileOrderUtils.getModuleCompileOrders(setOf(module3, module1, module4))
        assertModuleOrder(listOf(module1, module3, module4), order)
        order = ModuleCompileOrderUtils.getModuleCompileOrders(setOf(module1, module4, module3))
        assertModuleOrder(listOf(module1, module3, module4), order)
    }

    private fun assertModuleOrder(expected: List<ModuleInfo>, actual: List<ModuleInfo>) {
        assertContentEquals(expected.map { it.name }, actual.map { it.name })
    }
}