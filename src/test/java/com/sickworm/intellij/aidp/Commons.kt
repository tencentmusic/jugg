package com.sickworm.intellij.aidp

import java.io.File

val buildDir: String = File("src/test/build").absolutePath
val assetsDir: String = File("src/test/assets").absolutePath
val assetsJavaDir = "$assetsDir/java"
val assetsLibDir = "$assetsDir/lib"
val assetsClassDir = "$assetsDir/class"
val assetsAndroidDir = "$assetsDir/android"