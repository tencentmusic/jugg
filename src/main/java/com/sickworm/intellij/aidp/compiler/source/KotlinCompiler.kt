package com.sickworm.intellij.aidp.compiler.source

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.aidp.compiler.Result
import com.sickworm.intellij.aidp.compiler.*
import com.sickworm.intellij.aidp.listFilesRecursively
import io.github.classgraph.ClassGraph
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import java.io.*
import java.net.URL
import java.net.URLClassLoader

class KotlinCompiler(private val logger: Logger): ICompiler {
    override val supportedTypes = listOf(CompileFile.Type.Kotlin)

    private val kotlinCompile = KotlinCompileIsolate()

    override fun compile(task: CompileTask): CompileResult {
        checkCanCompile(task)
        checkOutputDirIsEmpty(task)
        task.outputDir.mkdirs()

        val outputStream = ByteArrayOutputStream()
        val printStream = PrintStream(outputStream)

        val dependencies = task.files.map { it.dependencyPaths }.flatten().toSet()

        // TODO read from project
        val packageName = "com.tencent.wesing.camerasource.example"
        val flavor = "main"
        val resourcePath = "/Users/wormchen/IdeaProjects/TMEVideoRecord/app/src/main/res"
        val command = mutableListOf<String>(
            "-Xplugin=/Users/wormchen/IdeaProjects/android-incremental-deploy-plugin/src/main/resources/kotlin_compile/kotlin-android-extensions-1.4.32.jar",
            "-P", "plugin:org.jetbrains.kotlin.android:package=${packageName}",
            "-P", "plugin:org.jetbrains.kotlin.android:variant=${flavor};${resourcePath}",
            "-jvm-target", "1.8",
            "-no-stdlib",
            "-no-reflect",
            "-d", task.outputDir.absolutePath,
        )
        if (dependencies.isNotEmpty()) {
            command.add("-cp")
            command.add(dependencies.joinToString(File.pathSeparator))
        }
        command.add(task.files.joinToString(separator = " ") { it.file.absolutePath })
        logger.debug(command.toString())
        kotlinCompile.exec(printStream, command.toTypedArray())
        val cmd = "/Users/wormchen/IdeaProjects/studio-master-dev/prebuilts/tools/common/kotlin-plugin-ij/Kotlin/kotlinc/bin/kotlinc"
        val realCmd = cmd + " " + command.joinToString(" ")
        logger.debug(realCmd)
//        val process = Runtime.getRuntime().exec(realCmd)
//        val output = readOutput(process.inputStream)
//        println("output $output")
//        val errorOutput = readOutput(process.errorStream)
//        println("errorOutput $errorOutput")
//        process.waitFor()
        logger.warn("compile: ${String(outputStream.toByteArray())}")

        // TODO check error
        val outputs = task.outputDir.listFilesRecursively().mapNotNull {
            if (it.extension == "kotlin_module") return@mapNotNull null
            CompileOutput(CompileOutput.Type.Class, it, task.outputDir)
        }

        return CompileResult(task, listOf(Result.success(task.files[0])), outputs)
    }

    private fun readOutput(stream: InputStream): String {
        val ins = BufferedReader(InputStreamReader(stream))
        val stringBuilder = StringBuilder()
        while (true) {
            val line = ins.readLine() ?: break
            stringBuilder.append(line)
            stringBuilder.append('\n')
        }
        ins.close()
        return stringBuilder.toString()
    }
}

/**
 * invoke K2JVMCompiler in isolate class loader
 */
class KotlinCompileIsolate {

    private val classLoader: ClassLoader = getIsolateClassLoader()

    fun exec(printStream: PrintStream, args: Array<String>) {
//        val thread = Thread() {
//            try {
//                println("1")
//                val descriptorVisibilitiesClass = classLoader.loadClass("org.jetbrains.kotlin.descriptors.DescriptorVisibilities")
//                Thread.currentThread().contextClassLoader = classLoader
//                val compileClass = classLoader.loadClass("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
//                val compileInstance = compileClass.declaredConstructors[0].newInstance()
//                val method = compileClass.getMethod("exec", PrintStream::class.java, Array<String>::class.java)
//                method.invoke(compileInstance, printStream, args)
//                println("2")
//            } catch (e: Throwable) {
//                e.printStackTrace()
//            }
//        }
//        thread.start()
//        thread.join()
        K2JVMCompiler().exec(printStream, *args)
    }

    companion object {

        private val requiredLibraries = listOf(
            "annotations-13.0.jar", // plugin
            "annotations-19.0.0.jar", // test
            "kotlin-reflect-1.4.32.jar",
            "kotlin-stdlib-1.4.32.jar",
            "kotlin-stdlib-common-1.4.32.jar",
            "kotlin-stdlib-jdk7-1.4.32.jar",
            "kotlin-stdlib-jdk8-1.4.32.jar"
        )

        private val embeddedLibraries = listOf(
            "kotlin_compile/kotlin-compiler-1.4.32.jar",
//            "kotlin_compile/kotlin-compiler-embeddable-1.4.32.jar",
//            "kotlin_compile/kotlin-plugin.jar",
            "kotlin_compile/trove4j-1.0.20181211.jar",
        )

        private val pluginsLibraries = listOf(
            "kotlin_compile/kotlin-android-extensions-1.3.72.jar",
        )

        private fun getIsolateClassLoader(): ClassLoader {
            val libraryClasspath: List<URL> = ClassGraph().classpathURLs.filter {
                requiredLibraries.contains(File(it.file).name)
            }
            val saveDir = File(PathManager.getSystemPath(), "aidp")
            val compilerClasspath = embeddedLibraries.map {
                val outputJar = File(saveDir, it)
                if (!outputJar.exists()) {
                    outputJar.parentFile.mkdirs()
                    this::class.java.classLoader.getResource(it)!!.openStream().use { ins ->
                        FileOutputStream(outputJar).use { ous ->
                            ins.copyTo(ous)
                        }
                    }
                }

                outputJar.toURI().toURL()
            }

            return URLClassLoader((libraryClasspath + compilerClasspath).toTypedArray(), null)
        }
    }
}
