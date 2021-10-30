package com.sickworm.intellij.jugg.mock

import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.Condition
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.messages.MessageBus
import org.picocontainer.PicoContainer
import java.io.File
import java.lang.RuntimeException
import java.nio.file.Path

@Suppress("UnstableApiUsage")
class MockModule(root: File): Module {

    private val virtualFile = MockIoVirtualFile(root)

    private val manager = MockModuleRootManager(virtualFile)

    override fun <T : Any?> getComponent(interfaceClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        if (interfaceClass == ModuleRootManager::class.java) {
            return manager as T
        }
        TODO("Not yet implemented")
    }

    override fun getModuleFile(): VirtualFile {
        return virtualFile
    }

    override fun getName(): String {
        return virtualFile.name
    }

    override fun <T : Any?> getUserData(key: Key<T>): T? {
        TODO("Not yet implemented")
    }

    override fun <T : Any?> putUserData(key: Key<T>, value: T?) {
        TODO("Not yet implemented")
    }

    override fun dispose() {
        TODO("Not yet implemented")
    }


    override fun getPicoContainer(): PicoContainer {
        TODO("Not yet implemented")
    }

    override fun getMessageBus(): MessageBus {
        TODO("Not yet implemented")
    }

    override fun isDisposed(): Boolean {
        TODO("Not yet implemented")
    }

    override fun getDisposed(): Condition<*> {
        TODO("Not yet implemented")
    }

    override fun createError(error: Throwable, pluginId: PluginId): RuntimeException {
        TODO("Not yet implemented")
    }

    override fun createError(message: String, pluginId: PluginId): RuntimeException {
        TODO("Not yet implemented")
    }

    override fun createError(
        message: String,
        pluginId: PluginId,
        attachments: MutableMap<String, String>?
    ): RuntimeException {
        TODO("Not yet implemented")
    }

    override fun <T : Any> loadClass(className: String, pluginDescriptor: PluginDescriptor): Class<T> {
        TODO("Not yet implemented")
    }

    override fun getModuleNioFile(): Path {
        TODO("Not yet implemented")
    }

    override fun getProject(): Project {
        TODO("Not yet implemented")
    }

    override fun isLoaded(): Boolean {
        TODO("Not yet implemented")
    }

    override fun setOption(key: String, value: String?) {
        TODO("Not yet implemented")
    }

    override fun getOptionValue(key: String): String? {
        TODO("Not yet implemented")
    }

    override fun getModuleScope(): GlobalSearchScope {
        TODO("Not yet implemented")
    }

    override fun getModuleScope(includeTests: Boolean): GlobalSearchScope {
        TODO("Not yet implemented")
    }

    override fun getModuleWithLibrariesScope(): GlobalSearchScope {
        TODO("Not yet implemented")
    }

    override fun getModuleWithDependenciesScope(): GlobalSearchScope {
        TODO("Not yet implemented")
    }

    override fun getModuleContentScope(): GlobalSearchScope {
        TODO("Not yet implemented")
    }

    override fun getModuleContentWithDependenciesScope(): GlobalSearchScope {
        TODO("Not yet implemented")
    }

    override fun getModuleWithDependenciesAndLibrariesScope(includeTests: Boolean): GlobalSearchScope {
        TODO("Not yet implemented")
    }

    override fun getModuleWithDependentsScope(): GlobalSearchScope {
        TODO("Not yet implemented")
    }

    override fun getModuleTestsWithDependentsScope(): GlobalSearchScope {
        TODO("Not yet implemented")
    }

    override fun getModuleRuntimeScope(includeTests: Boolean): GlobalSearchScope {
        TODO("Not yet implemented")
    }
}