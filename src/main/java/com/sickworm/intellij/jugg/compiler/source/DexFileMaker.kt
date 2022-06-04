package com.sickworm.intellij.jugg.compiler.source

import com.android.tools.r8.D8Command
import com.android.tools.r8.origin.Origin
import java.io.File


class DexFileMaker {

    private val isEnableDesugaring = true

    fun dex(outputDir: File, classFilesOrDir: List<File>, classpath: Collection<String>,
            androidJar: File, minApi: Int) {
        outputDir.mkdirs()

        // see https://developer.android.com/studio/command-line/d8
        val args = mutableListOf<String>()

        args.add("--file-per-class")

        args.add("--lib")
        args.add(androidJar.absolutePath)

        args.add("--min-api")
        args.add("$minApi")

        if (isEnableDesugaring) {
            // get warning without --classpath and --min-api:
            // Type `kotlin.jvm.internal.Intrinsics` was not found, it is required for default or static interface methods desugaring of `Lcom/example/myapplication/MainActivity;onCreate$lambda-0(Lcom/example/myapplication/MainActivity;Landroid/view/View;)V`
            // Type `androidx.appcompat.app.AppCompatActivity` was not found, it is required for default or static interface methods desugaring of `Lcom/example/myapplication/MainActivity;onCreate(Landroid/os/Bundle;)V`
            // it's fucking slow when classpath.size larger than 500... so better don't add --classpath
//            if (classpath.isNotEmpty()) {
//                classpath.forEach {
//                    args.add("--classpath")
//                    args.add(it)
//                }
//            }
        } else {
            args.add("--no-desugaring")
        }

        args.add("--output")
        args.add(outputDir.absolutePath)

        val filesPath = classFilesOrDir.map { it.absolutePath }
        args.addAll(filesPath)

        val command = D8Command.parse(args.toTypedArray(), Origin.root()).build()
        com.android.tools.r8.D8.run(command) // throws exceptions
    }
}