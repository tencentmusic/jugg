package com.sickworm.intellij.jugg.deploy.run

import com.android.tools.deployer.Version
import com.android.tools.deploy.proto.Deploy
import com.android.tools.idea.protobuf.ByteString
import com.sickworm.intellij.jugg.project.runtime.JuggResourceManager
import com.sickworm.intellij.jugg.project.runtime.PreparedRuntimeResource
import java.io.File
import java.security.MessageDigest

/** Prepares the fixed Quail installer bundle and rejects Java/protocol version mismatches. */
object StandaloneDeployerResources {

    fun prepare(juggVersion: String): PreparedRuntimeResource {
        val prepared = JuggResourceManager(
            classLoader = StandaloneDeployerResources::class.java.classLoader,
        ).prepare(
            resourceRoot = "deployer/quail",
            targetRelativePath = "runtime/$juggVersion/deployer/quail",
        )
        check(prepared.metadata.protocolVersion == Version.hash()) {
            "Standalone deployer protocol mismatch: Java=${Version.hash()}, installer=${prepared.metadata.protocolVersion}"
        }
        verifyProtocolDependencies(prepared)
        return prepared
    }

    private fun verifyProtocolDependencies(prepared: PreparedRuntimeResource) {
        val owners = mapOf(
            "deploy_java_proto.jar" to Deploy::class.java,
            "studio-proto.jar" to ByteString::class.java,
        )
        val dependencyNames = prepared.metadata.protocolDependencies.map { it.path.substringAfterLast('/') }
        check(dependencyNames.size == owners.size && dependencyNames.toSet() == owners.keys) {
            "Standalone protocol dependency set mismatch: expected=${owners.keys}, actual=$dependencyNames"
        }
        prepared.metadata.protocolDependencies.forEach { dependency ->
            val name = dependency.path.substringAfterLast('/')
            val owner = owners[name] ?: error("Unknown standalone protocol dependency: ${dependency.path}")
            val source = File(owner.protectionDomain.codeSource.location.toURI())
            check(source.isFile && source.name == name && source.sha256() == dependency.sha256) {
                "Standalone protocol dependency mismatch: ${dependency.path}"
            }
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
