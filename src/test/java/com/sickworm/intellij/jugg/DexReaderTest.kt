package com.sickworm.intellij.jugg

import com.googlecode.d2j.node.DexFileNode
import com.googlecode.d2j.reader.BaseDexFileReader
import com.googlecode.d2j.reader.DexFileReader
import com.googlecode.d2j.reader.MultiDexFileReader
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals

class DexReaderTest {

    @Test
    @Throws(IOException::class)
    fun test() {
        val startTime = System.currentTimeMillis()
        val apkBuffer = File("src/test/assets/android/app-debug.apk").readBytes()
        assertEquals(2709793, apkBuffer.size)

        val reader: BaseDexFileReader = MultiDexFileReader.open(apkBuffer)
        val costTime = System.currentTimeMillis() - startTime
        println("costTime: $costTime")

        val startTime2 = System.currentTimeMillis()
        val visitor = DexFileNode()
        reader.accept(visitor, DexFileReader.SKIP_CODE)

        val classCount = visitor.clzs.size
        assertEquals(2394, classCount)
        val methodCount = visitor.clzs.sumBy { it.methods?.size?: 0 }
        assertEquals(19352, methodCount)

        val fieldCount = visitor.clzs.sumBy { it.fields?.size?: 0 }
        assertEquals(12291, fieldCount)

        val costTime2 = System.currentTimeMillis() - startTime2
        println("costTime2: $costTime2")
    }
}