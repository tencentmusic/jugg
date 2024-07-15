@file:Suppress("NonExtendableApiUsage")

package com.sickworm.intellij.jugg.mock

import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.*
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.vfs.VirtualFile
import org.mockito.Mockito
import java.io.File

class MockLibraryOrderEntry(private val path: String) : LibraryOrderEntry {
    override fun isSynthetic(): Boolean {
        return true
    }

    override fun compareTo(other: OrderEntry?): Int {
        return 0
    }

    override fun getPresentableName(): String {
        return ""
    }

    override fun isValid(): Boolean {
        return true
    }

    val mockModule = Mockito.mock(Module::class.java)

    override fun getOwnerModule(): Module {
        return mockModule
    }

    override fun <R : Any?> accept(policy: RootPolicy<R>, initialValue: R?): R {
        return initialValue!!
    }

    override fun getRootFiles(type: OrderRootType): Array<VirtualFile> {
        return arrayOf(MockIoVirtualFile(File(path)))
    }

    override fun getRootUrls(type: OrderRootType): Array<String> {
        return emptyArray()
    }

    override fun isExported(): Boolean {
        return true
    }

    override fun setExported(value: Boolean) {

    }

    override fun getScope(): DependencyScope {
        return DependencyScope.COMPILE
    }

    override fun setScope(scope: DependencyScope) {

    }

    override fun getLibrary(): Library? {
        return null
    }

    override fun isModuleLevel(): Boolean {
        return true
    }

    override fun getLibraryLevel(): String {
        return ""
    }

    override fun getLibraryName(): String? {
        return null
    }
}