package com.sickworm.intellij.jugg.mock

import com.intellij.openapi.module.Module
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.*
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jetbrains.jps.model.module.JpsModuleSourceRootType
import java.io.File

@Suppress("NonExtendableApiUsage")
class MockModuleRootManager(private val root: VirtualFile): ModuleRootManager() {

    private val roots = mapOf<JpsModuleSourceRootType<*>, List<String>>(
        JavaSourceRootType.SOURCE to listOf("src/main/java"),
        JavaResourceRootType.RESOURCE to listOf("src/main/assets", "src/main/res"),
    )

    override fun getContentRoots(): Array<VirtualFile> {
        return arrayOf(root)
    }

    override fun getSourceRoots(rootTypes: MutableSet<out JpsModuleSourceRootType<*>>): MutableList<VirtualFile> {
        return rootTypes
            .flatMap { roots[it]?: emptyList() }
            .map { MockIoVirtualFile(File(root.path, it)) }
            .toMutableList()
    }

    override fun getModule(): Module {
        TODO("Not yet implemented")
    }

    override fun getContentEntries(): Array<ContentEntry> {
        TODO("Not yet implemented")
    }

    override fun getOrderEntries(): Array<OrderEntry> {
        TODO("Not yet implemented")
    }

    override fun getSdk(): Sdk? {
        TODO("Not yet implemented")
    }

    override fun isSdkInherited(): Boolean {
        TODO("Not yet implemented")
    }

    override fun getContentRootUrls(): Array<String> {
        TODO("Not yet implemented")
    }

    override fun getExcludeRoots(): Array<VirtualFile> {
        TODO("Not yet implemented")
    }

    override fun getExcludeRootUrls(): Array<String> {
        TODO("Not yet implemented")
    }

    override fun getSourceRoots(): Array<VirtualFile> {
        TODO("Not yet implemented")
    }

    override fun getSourceRoots(includingTests: Boolean): Array<VirtualFile> {
        TODO("Not yet implemented")
    }

    override fun getSourceRoots(rootType: JpsModuleSourceRootType<*>): MutableList<VirtualFile> {
        TODO("Not yet implemented")
    }

    override fun getSourceRootUrls(): Array<String> {
        TODO("Not yet implemented")
    }

    override fun getSourceRootUrls(includingTests: Boolean): Array<String> {
        TODO("Not yet implemented")
    }

    override fun <R : Any?> processOrder(policy: RootPolicy<R>, initialValue: R): R {
        TODO("Not yet implemented")
    }

    override fun orderEntries(): OrderEnumerator {
        TODO("Not yet implemented")
    }

    override fun getDependencyModuleNames(): Array<String> {
        TODO("Not yet implemented")
    }

    override fun <T : Any?> getModuleExtension(klass: Class<T>): T {
        TODO("Not yet implemented")
    }

    override fun getModuleDependencies(): Array<Module> {
        TODO("Not yet implemented")
    }

    override fun getModuleDependencies(includeTests: Boolean): Array<Module> {
        TODO("Not yet implemented")
    }

    override fun getExternalSource(): ProjectModelExternalSource? {
        TODO("Not yet implemented")
    }

    override fun getFileIndex(): ModuleFileIndex {
        TODO("Not yet implemented")
    }

    override fun getModifiableModel(): ModifiableRootModel {
        TODO("Not yet implemented")
    }

    override fun getDependencies(): Array<Module> {
        TODO("Not yet implemented")
    }

    override fun getDependencies(includeTests: Boolean): Array<Module> {
        TODO("Not yet implemented")
    }

    override fun isDependsOn(module: Module): Boolean {
        TODO("Not yet implemented")
    }
}