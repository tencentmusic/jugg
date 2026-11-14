package com.sickworm.intellij.jugg.compiler.databinding

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.CompileResult
import com.sickworm.intellij.jugg.compiler.CompileStatusHolder
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.JuggCompiler
import com.sickworm.intellij.jugg.deploy.data.ApkParser
import com.sickworm.intellij.jugg.mock.TestGlobal
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Protects matching layout and Binding DEX types across resource roots in one incremental build. */
class DataBindingResourceRootsCompileFlowTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun prepareBaseline() {
        TestGlobal.clearBuild()
    }

    @Test
    fun xmlTypeChangeUpdatesBinding() = checkBindingType(includePeer = false)

    @Test
    fun xmlTypeChangeAcrossResourceRootsUpdatesBinding() = checkBindingType(includePeer = true)

    @Test
    fun reversedResourceRootsUpdateBothBindings() = checkBindingType(includePeer = true, reverseFiles = true)

    @Test
    fun plainLayoutInAnotherRootKeepsDataBindingOutput() = checkBindingType(includePeer = true, plainPeer = true)

    @Test
    fun resourceDirectoryInputsUpdateBothBindings() = checkBindingType(includePeer = true, directoryInputs = true)

    @Test
    fun sameNamedLayoutsUseLastResourceRoot() {
        val context = TestGlobal.context
        val module = context.modules.getValue("app")
        val source = File(module.moduleRootDir, "src/main/res/layout/activity_data_binding_multi_root.xml")
        val roots = listOf(temporaryFolder.newFolder("mainRes"), temporaryFolder.newFolder("debugRes"))
        val files = roots.mapIndexed { index, root ->
            val layout = File(root, "layout/${source.name}").apply {
                parentFile.mkdirs()
                val xml = source.readText()
                writeText(if (index == 0) xml else xml.replace(
                    "com.sickworm.jugg.demo.sdk.SdkConstraintLayout", "androidx.constraintlayout.widget.ConstraintLayout"))
            }
            CompileFile(CompileFile.Type.Resource, layout, root, module)
        }
        val parent = object : Disposable { override fun dispose() {} }
        try {
            val result = JuggCompiler(context, parent).compile(CompileTask(
                files, TestGlobal.stagingDir, CompileStatusHolder.DEFAULT,
            ))
            assertBindingTypes(result, includePeer = false, plainPeer = false)
        } finally {
            Disposer.dispose(parent)
        }
    }

    private fun checkBindingType(
        includePeer: Boolean,
        reverseFiles: Boolean = false,
        plainPeer: Boolean = false,
        directoryInputs: Boolean = false,
    ) {
        val context = TestGlobal.context
        val module = context.modules.getValue("app")
        val target = File(module.moduleRootDir, "src/main/res/layout/activity_data_binding_multi_root.xml")
        val peer = if (plainPeer) {
            File(temporaryFolder.newFolder("plainRes"), "layout/activity_main.xml").apply {
                parentFile.mkdirs()
                File(module.moduleRootDir, "src/main/res/layout/activity_main.xml").copyTo(this)
            }
        } else File(module.moduleRootDir, "src/debug/res/layout/data_binding_multi_root_peer.xml")
        val files = if (includePeer) listOf(target, peer) else listOf(target)
        val originals = files.associateWith { it.readBytes() }
        val parent = object : Disposable { override fun dispose() {} }
        try {
            target.writeText(target.readText().replace(
                "com.sickworm.jugg.demo.sdk.SdkConstraintLayout",
                "androidx.constraintlayout.widget.ConstraintLayout",
            ))
            if (includePeer) {
                peer.writeText(peer.readText().replace("baseline", "changed") + "\n<!-- changed -->")
            }
            val inputs = files.mapIndexed { index, file ->
                val root = if (directoryInputs) temporaryFolder.newFolder("res$index") else file.parentFile.parentFile
                if (directoryInputs) file.copyTo(File(root, "layout/${file.name}").apply { parentFile.mkdirs() })
                CompileFile(CompileFile.Type.Resource, if (directoryInputs) root else file, root, module)
            }.let { if (reverseFiles) it.reversed() else it }
            val result = JuggCompiler(context, parent).compile(CompileTask(
                inputs,
                TestGlobal.stagingDir,
                CompileStatusHolder.DEFAULT,
            ))
            assertBindingTypes(result, includePeer, plainPeer)
        } finally {
            originals.forEach { (file, bytes) -> file.writeBytes(bytes) }
            Disposer.dispose(parent)
        }
    }

    private fun assertBindingTypes(result: CompileResult, includePeer: Boolean, plainPeer: Boolean) {
        assertTrue(result.isAllSuccess, result.toString())
        val dexFiles = result.outputs.filter { it.type == CompileOutput.Type.Dex }.map { it.file }
        val classes = ApkParser().parseDexFiles(dexFiles).classDeployItems.flatMap { it.classNodes }
        val bindingName = "Lcom/example/myapplication/databinding/ActivityDataBindingMultiRootBinding;"
        val binding = classes.singleOrNull { it.className == bindingName }
        assertTrue(binding != null, "Updated XML must deploy its updated Binding DEX; emitted: ${classes.map { it.className }}")
        assertEquals("Landroidx/constraintlayout/widget/ConstraintLayout;",
            binding.fields.single { it.name == "sdkContainer" }.type)
        val bindingNames = listOf(bindingName) + if (includePeer) {
            val peerBinding = if (plainPeer) "ActivityMainBinding" else "DataBindingMultiRootPeerBinding"
            listOf("Lcom/example/myapplication/databinding/$peerBinding;")
        } else emptyList()
        bindingNames.forEach { name ->
            assertTrue(classes.any { it.className == name }, "Updated layout must deploy $name")
            if (name == bindingName || !plainPeer) {
                assertTrue(classes.any { it.className == name.removeSuffix(";") + "Impl;" },
                    "Updated layout must deploy its updated BindingImpl DEX: $name")
            }
        }
    }
}
