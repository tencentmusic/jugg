package com.sickworm.intellij.jugg.ai.mcp.actions

/**
 * RecordSessionRegistry stores active screenrecord sessions by sessionId and device serial.
 */
object RecordSessionRegistry {

    /**
     * RecordSession carries runtime metadata for one in-progress screen recording.
     */
    data class RecordSession(
        val sessionId: String,
        val serial: String,
        val pid: String,
        val remoteFile: String,
        val localFilePath: String,
        val startedAtMs: Long,
        val launchMode: String,
        val hostProcess: Process? = null,
    )

    private val lock = Any()
    private val sessionById: MutableMap<String, RecordSession> = mutableMapOf()
    private val sessionIdBySerial: MutableMap<String, String> = mutableMapOf()

    fun findById(sessionId: String): RecordSession? {
        synchronized(lock) {
            return sessionById[sessionId]
        }
    }

    fun findBySerial(serial: String): RecordSession? {
        synchronized(lock) {
            val existingSessionId = sessionIdBySerial[serial] ?: return null
            return sessionById[existingSessionId]
        }
    }

    /**
     * Register a session only when the same serial does not already have an active one.
     */
    fun registerIfAbsent(session: RecordSession): Boolean {
        synchronized(lock) {
            if (sessionIdBySerial.containsKey(session.serial)) {
                return false
            }
            sessionById[session.sessionId] = session
            sessionIdBySerial[session.serial] = session.sessionId
            return true
        }
    }

    fun remove(sessionId: String): RecordSession? {
        synchronized(lock) {
            val removed = sessionById.remove(sessionId) ?: return null
            sessionIdBySerial.remove(removed.serial)
            return removed
        }
    }
}
