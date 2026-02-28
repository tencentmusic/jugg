package com.sickworm.intellij.jugg.mock

import com.intellij.openapi.extensions.ExtensionPoint
import com.intellij.openapi.extensions.Extensions
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkAdditionalData
import com.intellij.openapi.projectRoots.SdkModificator
import com.intellij.openapi.projectRoots.SdkType
import com.intellij.openapi.projectRoots.SdkTypeId
import com.intellij.openapi.roots.RootProvider
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.android.sdk.AndroidSdkType


@Suppress("NonExtendableApiUsage")
class MockAndroid30Sdk: Sdk {

    companion object {
        init {
            Extensions.getRootArea()
                .registerExtensionPoint("com.intellij.sdkType",
                    "com.intellij.openapi.projectRoots.SdkType",
                    ExtensionPoint.Kind.INTERFACE);
            val ep: ExtensionPoint<SdkType> =
                Extensions.getRootArea().getExtensionPoint<SdkType>("com.intellij.sdkType")
            ep.registerExtension(AndroidSdkType())
        }
    }

    override fun getName(): String {
        return "Android 30"
    }

    override fun getVersionString(): String {
        return "30"
    }

    override fun getHomePath(): String {
        return androidHome.absolutePath
    }

    override fun <T : Any?> getUserData(key: Key<T>): T? {
        TODO("Not yet implemented")
    }

    override fun <T : Any?> putUserData(key: Key<T>, value: T?) {
        TODO("Not yet implemented")
    }

    override fun getSdkType(): SdkTypeId {
        return AndroidSdkType.getInstance()
    }

    override fun getHomeDirectory(): VirtualFile {
        return MockIoVirtualFile(androidHome)
    }

    override fun getRootProvider(): RootProvider {
        TODO("Not yet implemented")
    }

    override fun getSdkModificator(): SdkModificator {
        TODO("Not yet implemented")
    }

    override fun getSdkAdditionalData(): SdkAdditionalData? {
        TODO("Not yet implemented")
    }

    override fun clone(): Any {
        TODO("Not yet implemented")
    }
}