package com.sickworm.intellij.jugg.deploy.data

import com.intellij.openapi.diagnostic.Logger

object SqLiteDriverLoader {

    private var isLoaded = false

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