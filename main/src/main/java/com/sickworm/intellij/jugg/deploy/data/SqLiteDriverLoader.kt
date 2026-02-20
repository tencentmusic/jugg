package com.sickworm.intellij.jugg.deploy.data

import com.intellij.openapi.diagnostic.Logger

/**
 * SqLiteDriverLoader lazily loads the SQLite JDBC driver once per process.
 */
object SqLiteDriverLoader {

    private var isLoaded = false

    @Synchronized
    fun load(logger: Logger) {
        if (isLoaded) {
            return
        }
        logger.debug("Loading SQLite JDBC driver...")

        try {
            Class.forName("org.sqlite.JDBC")
            logger.debug("Driver loaded!")
        } catch (e: ClassNotFoundException) {
            logger.error("Cannot find the driver in the classpath!", e)
        }
        isLoaded = true
    }
}
