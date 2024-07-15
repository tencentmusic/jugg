package com.sickworm.intellij.jugg.manager

import org.junit.Assert
import kotlin.test.assertNotNull

class OverlayComparator(
    private val except: ByteArray?,
    private val actual: ByteArray?
) {

    fun compare() {
        if (except == null) {
            // not exists in apk, it's ok
            return
        }
        assertNotNull(actual)

        if (!except.contentEquals(actual)) {
            val message = """
                except size: ${except.size}, actual size: ${actual.size}
                except content:
                ${String(except)}
                actual content:
                ${String(actual)}
            """.trimIndent()
            Assert.fail(message)
        }
    }
}